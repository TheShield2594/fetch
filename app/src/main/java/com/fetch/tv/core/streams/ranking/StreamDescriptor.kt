package com.fetch.tv.core.streams.ranking

import androidx.compose.runtime.Immutable

enum class StreamResolution(val height: Int, val label: String) {
    UHD_2160(2160, "4K"),
    QHD_1440(1440, "1440p"),
    FHD_1080(1080, "1080p"),
    HD_720(720, "720p"),
    SD_576(576, "576p"),
    SD_480(480, "480p"),
    SD_360(360, "360p"),
    UNKNOWN(0, "")
}

enum class VideoCodec(val label: String) {
    AV1("AV1"),
    HEVC("HEVC"),
    AVC("H.264"),
    VP9("VP9"),
    VC1("VC-1"),
    MPEG2("MPEG-2"),
    UNKNOWN("")
}

enum class HdrFormat(val label: String) {
    DOLBY_VISION("DV"),
    HDR10_PLUS("HDR10+"),
    HDR10("HDR10"),
    HLG("HLG")
}

enum class AudioFormat(val label: String, val isLossless: Boolean, val isPassthroughOnly: Boolean) {
    TRUEHD("TrueHD", isLossless = true, isPassthroughOnly = true),
    DTS_HD_MA("DTS-HD MA", isLossless = true, isPassthroughOnly = true),
    DTS_X("DTS:X", isLossless = true, isPassthroughOnly = true),
    DTS("DTS", isLossless = false, isPassthroughOnly = false),
    EAC3("DD+", isLossless = false, isPassthroughOnly = false),
    AC3("DD", isLossless = false, isPassthroughOnly = false),
    FLAC("FLAC", isLossless = true, isPassthroughOnly = false),
    OPUS("Opus", isLossless = false, isPassthroughOnly = false),
    AAC("AAC", isLossless = false, isPassthroughOnly = false),
    MP3("MP3", isLossless = false, isPassthroughOnly = false)
}

enum class ReleaseSource(val label: String, val qualityRank: Int) {
    REMUX("Remux", 9),
    BLURAY("BluRay", 8),
    WEB_DL("WEB-DL", 7),
    WEBRIP("WEBRip", 6),
    HDTV("HDTV", 4),
    DVD("DVD", 3),
    TELESYNC("TS", 1),
    CAM("CAM", 0),
    UNKNOWN("", 5)
}

/**
 * Structured view of a raw addon stream, parsed from its name, title, description and
 * behavior hints ("4K HDR DV 58GB [RD+] 🔥" → resolution, HDR, size, cached).
 */
@Immutable
data class StreamDescriptor(
    val resolution: StreamResolution = StreamResolution.UNKNOWN,
    val videoCodec: VideoCodec = VideoCodec.UNKNOWN,
    val hdr: Set<HdrFormat> = emptySet(),
    /** Dolby Vision profile number when the text names one (5, 7, 8). */
    val dolbyVisionProfile: Int? = null,
    val audio: Set<AudioFormat> = emptySet(),
    val hasAtmos: Boolean = false,
    /** Highest channel layout mentioned, e.g. 2, 6 (5.1), 8 (7.1). */
    val audioChannels: Int? = null,
    val sizeBytes: Long? = null,
    val source: ReleaseSource = ReleaseSource.UNKNOWN,
    val is3d: Boolean = false,
    val bitDepth: Int? = null,
    /** True when the addon marks the file as already cached on a debrid service. */
    val isDebridCached: Boolean = false,
    val seeders: Int? = null,
    val releaseGroup: String? = null
) {
    val isHdr: Boolean get() = hdr.isNotEmpty()
    val hasDolbyVision: Boolean get() = HdrFormat.DOLBY_VISION in hdr

    /** Short chips for the picker, most important first. */
    fun summaryLabels(): List<String> = buildList {
        resolution.label.takeIf { it.isNotEmpty() }?.let(::add)
        if (hasDolbyVision) add(dolbyVisionProfile?.let { "DV P$it" } ?: "DV")
        hdr.filter { it != HdrFormat.DOLBY_VISION }.forEach { add(it.label) }
        videoCodec.label.takeIf { it.isNotEmpty() }?.let(::add)
        audio.minByOrNull { it.ordinal }?.let { add(if (hasAtmos) "${it.label} Atmos" else it.label) }
        source.label.takeIf { it.isNotEmpty() }?.let(::add)
        sizeBytes?.let { add(formatSize(it)) }
    }

    companion object {
        val Empty = StreamDescriptor()

        fun formatSize(bytes: Long): String {
            val gb = bytes / 1_073_741_824.0
            return if (gb >= 1.0) "%.1f GB".format(gb) else "%d MB".format(bytes / 1_048_576)
        }
    }
}
