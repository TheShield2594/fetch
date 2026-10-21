package com.fetch.tv.core.streams.ranking

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probes the hardware once and caches what it can decode, so stream ranking never
 * auto-plays something that would fail or show wrong colours on this TV.
 */
@Singleton
class AndroidDeviceDecodeCapabilities @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    @Volatile
    private var cached: DeviceDecodeCapabilities? = null

    fun get(): DeviceDecodeCapabilities = cached ?: synchronized(this) {
        cached ?: runCatching(::probe)
            .onFailure { Log.w(TAG, "Capability probe failed; assuming a capable device", it) }
            .getOrDefault(DeviceDecodeCapabilities.Permissive)
            .also { cached = it }
    }

    /** Call when the HDMI sink may have changed (e.g. a different TV or AVR was connected). */
    fun invalidate() {
        cached = null
    }

    private fun probe(): DeviceDecodeCapabilities {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun hasDecoder(mime: String) = codecs.any { info -> info.supportedTypes.any { it.equals(mime, true) } }

        val decodable = buildSet {
            if (hasDecoder("video/avc")) add(VideoCodec.AVC)
            if (hasDecoder("video/hevc")) add(VideoCodec.HEVC)
            if (hasDecoder("video/av01")) add(VideoCodec.AV1)
            if (hasDecoder("video/x-vnd.on2.vp9")) add(VideoCodec.VP9)
            if (hasDecoder("video/mpeg2")) add(VideoCodec.MPEG2)
            if (hasDecoder("video/wvc1") || hasDecoder("video/x-ms-wmv")) add(VideoCodec.VC1)
        }

        val dvProfiles = buildSet {
            codecs.filter { info -> info.supportedTypes.any { it.equals(DV_MIME, true) } }.forEach { info ->
                val levels = runCatching { info.getCapabilitiesForType(DV_MIME).profileLevels }.getOrNull().orEmpty()
                levels.forEach { level ->
                    when (level.profile) {
                        CodecProfileLevel.DolbyVisionProfileDvheStn -> add(5)
                        CodecProfileLevel.DolbyVisionProfileDvheDtb -> add(7)
                        CodecProfileLevel.DolbyVisionProfileDvheSt -> add(8)
                        CodecProfileLevel.DolbyVisionProfileDvavSe -> add(9)
                    }
                }
                // Some DV decoders (e.g. Mi Box S) advertise none of the profiles they handle.
                if (levels.isEmpty()) add(8)
            }
        }

        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val hdr = buildSet {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                display?.hdrCapabilities?.supportedHdrTypes?.forEach { type ->
                    when (type) {
                        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> add(HdrFormat.DOLBY_VISION)
                        Display.HdrCapabilities.HDR_TYPE_HDR10 -> add(HdrFormat.HDR10)
                        Display.HdrCapabilities.HDR_TYPE_HLG -> add(HdrFormat.HLG)
                        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
                        ) {
                            add(HdrFormat.HDR10_PLUS)
                        }
                    }
                }
            }
        }

        val maxHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            display?.supportedModes?.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 2160
        } else {
            2160
        }
        val maxResolution = StreamResolution.entries
            .filter { it != StreamResolution.UNKNOWN }
            .firstOrNull { it.height <= maxHeight + 40 } ?: StreamResolution.FHD_1080

        return DeviceDecodeCapabilities(
            maxResolution = maxResolution,
            decodableCodecs = decodable,
            // A DV decoder is only useful if the panel (or the TV it's connected to) shows DV.
            hdrFormats = hdr,
            dolbyVisionProfiles = if (HdrFormat.DOLBY_VISION in hdr) dvProfiles else emptySet(),
            passthroughAudio = probePassthrough(),
            softwareAudio = probeSoftwareAudio(),
            dolbyVisionWithHdr10PlusBroken = Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
                Build.MODEL in DV_HDR10_PLUS_BLACK_SCREEN_MODELS
        ).also { Log.i(TAG, "Device decode capabilities: $it") }
    }

    private fun probePassthrough(): Set<AudioFormat> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptySet()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptySet()
        val encodings = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .flatMap { it.encodings.toList() }
            .toSet()
        return buildSet {
            if (PlatformAudioFormat.ENCODING_AC3 in encodings) add(AudioFormat.AC3)
            if (PlatformAudioFormat.ENCODING_E_AC3 in encodings) add(AudioFormat.EAC3)
            if (PlatformAudioFormat.ENCODING_DTS in encodings) add(AudioFormat.DTS)
            if (PlatformAudioFormat.ENCODING_DTS_HD in encodings) {
                add(AudioFormat.DTS_HD_MA)
                add(AudioFormat.DTS_X)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                PlatformAudioFormat.ENCODING_DOLBY_TRUEHD in encodings
            ) {
                add(AudioFormat.TRUEHD)
            }
        }
    }

    private fun probeSoftwareAudio(): Set<AudioFormat> {
        val available = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        if (!available) return emptySet()
        return SOFTWARE_AUDIO_MIME_TYPES
            .filter { (_, mimeType) -> runCatching { FfmpegLibrary.supportsFormat(mimeType) }.getOrDefault(false) }
            .keys
    }

    private companion object {
        const val TAG = "DeviceDecodeCaps"
        const val DV_MIME = "video/dolby-vision"

        /**
         * Fire TV Stick 4K (2018, AFTMM) and Fire TV Stick 4K 2nd gen (AFTKM): black screen on
         * Dolby Vision + HDR10+ files (NuvioMedia/NuvioTV#3996). Extend as more are confirmed.
         */
        val DV_HDR10_PLUS_BLACK_SCREEN_MODELS = setOf("AFTMM", "AFTKM")

        val SOFTWARE_AUDIO_MIME_TYPES = mapOf(
            AudioFormat.TRUEHD to MimeTypes.AUDIO_TRUEHD,
            AudioFormat.DTS_HD_MA to MimeTypes.AUDIO_DTS_HD,
            AudioFormat.DTS_X to MimeTypes.AUDIO_DTS_X
        )
    }
}
