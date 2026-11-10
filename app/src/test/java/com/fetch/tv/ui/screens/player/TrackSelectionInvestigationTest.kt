package com.fetch.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.source.chunk.MediaChunk
import android.text.TextUtils
import android.os.SystemClock
import android.content.Context
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.RendererCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.VideoCapabilities
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Tracks
import kotlinx.coroutines.flow.MutableStateFlow

class TrackSelectionInvestigationTest {

    @Test
    fun testTrackSelectionWithDecoderCapabilityLimits() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val seq = firstArg<CharSequence?>()
            seq == null || seq.isEmpty()
        }

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        val format1080p = Format.Builder().setId("1").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(4500000).setWidth(1920).setHeight(1080).build()
        val format720p = Format.Builder().setId("2").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(2150000).setWidth(1280).setHeight(720).build()
        val format480p = Format.Builder().setId("3").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(1200000).setWidth(854).setHeight(480).build()

        val trackGroup = TrackGroup(format1080p, format720p, format480p)
        
        // Simulating that the device only supports 480p (Index 2) as H.264 High Profile @ Level 4.0
        // is unsupported for higher resolutions on this TV device.
        val supportedTracks = intArrayOf(2) // Only 480p index

        val bandwidthMeter = mockk<BandwidthMeter>(relaxed = true)
        every { bandwidthMeter.bitrateEstimate } returns 25_000_000L // 25 Mbps (Plenty of bandwidth)
        every { bandwidthMeter.timeToFirstByteEstimateUs } returns C.TIME_UNSET

        val adaptiveSelection = AdaptiveTrackSelection(
            trackGroup,
            supportedTracks,
            bandwidthMeter
        )

        adaptiveSelection.updateSelectedTrack(0, 0, C.TIME_UNSET, emptyList(), emptyArray())

        val selectedIndex = adaptiveSelection.selectedIndex
        val selectedFormat = adaptiveSelection.getFormat(selectedIndex)

        
        assertEquals(480, selectedFormat.height)
    }

    @Test
    fun testTrackSelectorHlsResolutionBypass() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0

        val format1080pAvc8Bit = Format.Builder()
            .setId("1080p-avc")
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .setWidth(1920)
            .setHeight(1080)
            .build()

        val format1080pHevc10Bit = Format.Builder()
            .setId("1080p-hevc-10bit")
            .setSampleMimeType("video/hevc")
            .setCodecs("hev1.2.4.L150.B0.main10")
            .setWidth(1920)
            .setHeight(1080)
            .build()

        val mappedTrackInfo = mockk<MappedTrackInfo>()
        every { mappedTrackInfo.rendererCount } returns 1
        every { mappedTrackInfo.getRendererType(0) } returns C.TRACK_TYPE_VIDEO
        
        val trackGroupAvc = TrackGroup(format1080pAvc8Bit)
        val trackGroupHevc10 = TrackGroup(format1080pHevc10Bit)
        val trackGroups = TrackGroupArray(trackGroupAvc, trackGroupHevc10)
        every { mappedTrackInfo.getTrackGroups(0) } returns trackGroups

        val capabilitiesAvc = RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES)
        val capabilitiesHevc10 = RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES)
        val rendererFormatSupports = arrayOf(
            arrayOf(
                intArrayOf(capabilitiesAvc),
                intArrayOf(capabilitiesHevc10)
            )
        )

        val context = mockk<Context>(relaxed = true)
        val adaptiveTrackSelectionFactory = mockk<AdaptiveTrackSelection.Factory>(relaxed = true)
        
        val isHls = true
        val selector = object : DefaultTrackSelector(context, adaptiveTrackSelectionFactory) {
            public override fun selectAllTracks(
                mappedTrackInfo: MappedTrackInfo,
                rendererFormatSupports: Array<out Array<out IntArray>>,
                rendererMixedMimeTypeAdaptationSupports: IntArray,
                params: Parameters
            ): Array<ExoTrackSelection.Definition?> {
                if (isHls) {
                    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                        if (mappedTrackInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO) {
                            val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                            for (groupIndex in 0 until trackGroups.length) {
                                val group = trackGroups[groupIndex]
                                  for (trackIndex in 0 until group.length) {
                                      val format = group.getFormat(trackIndex)
                                      val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                                      val formatSupport = RendererCapabilities.getFormatSupport(support)
                                      if (formatSupport == C.FORMAT_EXCEEDS_CAPABILITIES) {
                                          val mime = format.sampleMimeType
                                          val isAvcOrHevc = mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265
                                          val isAtMost1080p = format.width <= 1920 && format.height <= 1080
                                          val codecs = format.codecs?.lowercase() ?: ""
                                          val is10Bit = codecs.contains("main10") || codecs.contains("hevc.2") || codecs.contains("hev2")
                                          val isHdr = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084
                                          val isStandard8Bit = !is10Bit && !isHdr

                                          if (isAvcOrHevc && isAtMost1080p && isStandard8Bit) {
                                              rendererFormatSupports[rendererIndex][groupIndex][trackIndex] =
                                                  RendererCapabilities.create(
                                                      C.FORMAT_HANDLED,
                                                      RendererCapabilities.getAdaptiveSupport(support),
                                                      RendererCapabilities.getTunnelingSupport(support),
                                                      RendererCapabilities.getHardwareAccelerationSupport(support),
                                                      RendererCapabilities.getDecoderSupport(support)
                                                  )
                                          }
                                      }
                                  }
                              }
                          }
                      }
                  }
                  return arrayOfNulls(mappedTrackInfo.rendererCount)
              }
          }

        selector.selectAllTracks(
            mappedTrackInfo,
            rendererFormatSupports,
            intArrayOf(),
            DefaultTrackSelector.Parameters.DEFAULT_WITHOUT_CONTEXT
        )

        val finalSupportAvc = RendererCapabilities.getFormatSupport(rendererFormatSupports[0][0][0])
        assertEquals(C.FORMAT_HANDLED, finalSupportAvc)

        val finalSupportHevc10 = RendererCapabilities.getFormatSupport(rendererFormatSupports[0][1][0])
        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, finalSupportHevc10)
    }

    @Test
    fun testBuildStreamInfoDataWithActiveVideoFormat() {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val mockExoPlayer = mockk<ExoPlayer>(relaxed = true)
        
        // Active format is the cropped format returned by the decoder/player (1918x802)
        val activeFormat = Format.Builder()
            .setId("1")
            .setWidth(1918)
            .setHeight(802)
            .setPeakBitrate(1800000)
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .build()

        // Manifest format contains the original uncropped resolution (1920x1080)
        val manifestFormat = Format.Builder()
            .setId("1")
            .setWidth(1920)
            .setHeight(1080)
            .setPeakBitrate(1800000)
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .build()

        // Construct real currentTracks structure using media3 class constructors
        val mediaTrackGroup = TrackGroup(manifestFormat)
        val realGroup = Tracks.Group(mediaTrackGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        val mockTracks = Tracks(com.google.common.collect.ImmutableList.of(realGroup))
        
        every { mockExoPlayer.videoFormat } returns activeFormat
        every { mockExoPlayer.currentTracks } returns mockTracks
        every { controller._exoPlayer } returns mockExoPlayer

        // Mock other controller properties
        every { controller._uiState } returns MutableStateFlow(PlayerUiState(
            currentStreamName = "Test Stream",
            detectedFrameRate = 23.976f
        ))
        every { controller.playbackTimeline } returns MutableStateFlow(PlaybackTimelineState())
        every { controller.currentAddonName } returns "Test Addon"
        every { controller.currentAddonLogo } returns "logo.png"
        every { controller.currentStreamDescription } returns "Description"
        every { controller.currentFilename } returns "file.mkv"
        every { controller.currentVideoSize } returns 1024L
        every { controller.currentVideoWidth } returns 1920
        every { controller.currentVideoHeight } returns 1080
        every { controller.currentVideoBitrate } returns 4500000
        every { controller.currentVideoCodec } returns "HEVC"
        every { controller.currentInternalPlayerEngine } returns com.fetch.tv.data.local.InternalPlayerEngine.EXOPLAYER

        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.getString(any()) } returns "mocked_string"
        every { mockContext.resources } returns mockk(relaxed = true)
        every { controller.context } returns mockContext

        val streamInfo = controller.buildStreamInfoData()

        // Verify that the uncropped manifest/header resolution is matched and resolved
        assertEquals(1920, streamInfo.videoWidth)
        assertEquals(1080, streamInfo.videoHeight)
        assertEquals(1800000, streamInfo.videoBitrate)
        assertEquals("AVC", streamInfo.videoCodec)
    }

    @Test
    fun testFormatResolution() {
        // Cropped widescreen / letterboxed resolutions
        assertEquals("1918 × 802 (1080p)", formatResolution(1918, 802))
        assertEquals("854 × 357 (480p)", formatResolution(854, 357))
        assertEquals("1278 × 530 (720p)", formatResolution(1278, 530))
        assertEquals("3836 × 1600 (4K)", formatResolution(3836, 1600))

        // Standard resolutions
        assertEquals("1920 × 1080 (1080p)", formatResolution(1920, 1080))
        assertEquals("1280 × 720 (720p)", formatResolution(1280, 720))
        assertEquals("854 × 480 (480p)", formatResolution(854, 480))
        assertEquals("3840 × 2160 (4K)", formatResolution(3840, 2160))

        // Portrait / vertical resolutions
        assertEquals("1080 × 1920 (1080p)", formatResolution(1080, 1920))
        assertEquals("720 × 1280 (720p)", formatResolution(720, 1280))

        // Custom / fallback resolutions
        assertEquals("640 × 360 (360p)", formatResolution(640, 360))
        assertEquals("720 × 576 (576p)", formatResolution(720, 576))
    }

    @Test
    fun testDolbyVisionExtractorsFactoryFormatRewriting() {
        val mockExtractor = mockk<androidx.media3.extractor.mp4.Mp4Extractor>(relaxed = true)
        val delegateFactory = androidx.media3.extractor.ExtractorsFactory { arrayOf(mockExtractor) }

        val factory = com.fetch.tv.core.player.DolbyVisionExtractorsFactory(
            delegate = delegateFactory,
            config = com.fetch.tv.core.player.DolbyVisionConversionConfig(active = false),
            stripDvRpu = true
        )

        val extractors = factory.createExtractors()
        assertEquals(1, extractors.size)
        val wrappedExtractor = extractors[0]

        val mockExtractorOutput = mockk<androidx.media3.extractor.ExtractorOutput>(relaxed = true)
        val mockTrackOutput = mockk<androidx.media3.extractor.TrackOutput>(relaxed = true)
        every { mockExtractorOutput.track(any(), C.TRACK_TYPE_VIDEO) } returns mockTrackOutput

        val extractorOutputSlot = io.mockk.slot<androidx.media3.extractor.ExtractorOutput>()
        every { mockExtractor.init(capture(extractorOutputSlot)) } returns Unit

        wrappedExtractor.init(mockExtractorOutput)

        val wrappedTrackOutput = extractorOutputSlot.captured.track(1, C.TRACK_TYPE_VIDEO)

        val formatSlot = io.mockk.slot<Format>()
        every { mockTrackOutput.format(capture(formatSlot)) } returns Unit

        // 1. Dolby Vision format -> should be rewritten to HEVC (H.265)
        val dvFormat = Format.Builder()
            .setId("1")
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.07.06")
            .build()

        wrappedTrackOutput.format(dvFormat)
        assertEquals(MimeTypes.VIDEO_H265, formatSlot.captured.sampleMimeType)

        // 2. Standard H.264 format -> should NOT be rewritten to HEVC (H.265)
        val avcFormat = Format.Builder()
            .setId("2")
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.64001F")
            .build()

        wrappedTrackOutput.format(avcFormat)
        assertEquals(MimeTypes.VIDEO_H264, formatSlot.captured.sampleMimeType)
    }
}
