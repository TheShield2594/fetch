package com.fetch.tv.ui.screens.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.fetch.tv.data.local.PlayerSettings
import com.fetch.tv.ui.screens.settings.MemoryBudget

/**
 * Centralizes all Fetch ExoPlayer performance enhancements behind a single toggle.
 *
 * When [enabled] is `true`, the helper applies:
 * - A target buffer sized to the RAM tier and capped by the heap budget
 * - Extended buffer durations (200–280 s) with a 12 s back-buffer
 * - 50 Mbps initial bandwidth estimate
 * - Scrubbing mode for faster seeks (disables audio/metadata, boosts codec rate)
 * - In-buffer seek detection to suppress transient buffering UI
 * - HTTP/2 with an 8-connection pool for networking
 *
 * When [enabled] is `false`, stock ExoPlayer defaults are used everywhere.
 */
@androidx.media3.common.util.UnstableApi
object FetchExoPlayerPerformanceHelper {

    /** Whether Fetch performance enhancements are active. Set from [PlayerSettingsDataStore]. */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            val supported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            val newValue = value && supported
            field = newValue
        }

    // Rebuilding this on a settings change handed already built data sources a pool that nothing
    // else uses, so warmed connections were never reused. One instance for the process avoids that.
    val sharedConnectionPool: okhttp3.ConnectionPool = okhttp3.ConnectionPool(
        FETCH_SHARED_POOL_MAX_IDLE,
        3,
        java.util.concurrent.TimeUnit.MINUTES
    )

    // ─── Constants ────────────────────────────────────────────────────────────
    const val DEFAULT_FETCH_ALLOCATOR_SEGMENT_SIZE = 64 * 1024        // 64 KB

    const val DEFAULT_FETCH_TARGET_BUFFER_MB = 175
    const val DEFAULT_FETCH_TARGET_BUFFER_BYTES = DEFAULT_FETCH_TARGET_BUFFER_MB * 1024 * 1024
    const val DEFAULT_FETCH_MIN_BUFFER_MS = 15_000
    const val DEFAULT_FETCH_MAX_BUFFER_MS = 45_000
    // A short back buffer keeps a small seek back off the network.
    const val DEFAULT_FETCH_BACK_BUFFER_MS = 15_000
    const val DEFAULT_FETCH_INITIAL_BITRATE_ESTIMATE = 50_000_000L     // 50 Mbps
    // Parallel chunk fetching keeps more sockets alive than the old cap of 8, which was evicting
    // live chunk connections mid playback and forcing cold reopens.
    const val FETCH_SHARED_POOL_MAX_IDLE = 32
    private const val BACK_BUFFER_TARGET_SHARE_NUM = 1L
    private const val BACK_BUFFER_TARGET_SHARE_DEN = 2L

    // ─── Customization Variables ──────────────────────────────────────────────
    @Volatile
    var minBufferMs: Int = DEFAULT_FETCH_MIN_BUFFER_MS

    @Volatile
    var maxBufferMs: Int = DEFAULT_FETCH_MAX_BUFFER_MS

    @Volatile
    var bufferForPlaybackMs: Int = 3_000

    @Volatile
    var bufferForPlaybackAfterRebufferMs: Int = 3_000

    @Volatile
    var backBufferMs: Int = DEFAULT_FETCH_BACK_BUFFER_MS

    @Volatile
    var targetBufferSizeMb: Int = 250

    // The allocator recycles internally, so only its own counters show what is actually held.
    @Volatile
    var liveAllocator: DefaultAllocator? = null

    @Volatile
    var calculatedMemoryUsageMb: Int = 0

    /**
     * Updates the performance helper with customized settings from PlayerSettings.
     */
    fun updateSettings(settings: PlayerSettings, context: Context) {
        val customBuffers = settings.bufferEngineEnabled
        val bufferSettings = settings.bufferSettings
        
        minBufferMs = if (customBuffers) bufferSettings.minBufferMs else DEFAULT_FETCH_MIN_BUFFER_MS
        maxBufferMs = if (customBuffers) bufferSettings.maxBufferMs else DEFAULT_FETCH_MAX_BUFFER_MS
        bufferForPlaybackMs = if (customBuffers) bufferSettings.bufferForPlaybackMs else 3_000
        bufferForPlaybackAfterRebufferMs = if (customBuffers) bufferSettings.bufferForPlaybackAfterRebufferMs else 3_000
        backBufferMs = if (customBuffers) bufferSettings.backBufferDurationMs else DEFAULT_FETCH_BACK_BUFFER_MS

        val safeLimitMb = getSafeBufferLimitMb(context)
        targetBufferSizeMb = if (customBuffers && !settings.bufferBudgetManaged) {
            val storedSize = bufferSettings.targetBufferSizeMb
            if (!settings.allowLargeTargetBuffer && storedSize > safeLimitMb) {
                safeLimitMb
            } else {
                storedSize
            }
        } else {
            safeLimitMb
        }

        val effectiveBufferMb = when {
            settings.fetchPerformanceModeEnabled -> {
                if (customBuffers && !settings.bufferBudgetManaged) {
                    MemoryBudget.effectiveBufferMb(bufferSettings.targetBufferSizeMb)
                } else {
                    safeLimitMb
                }
            }
            customBuffers -> {
                if (settings.bufferBudgetManaged) MemoryBudget.budgetMb
                else MemoryBudget.effectiveBufferMb(bufferSettings.targetBufferSizeMb)
            }
            else -> MemoryBudget.defaultBufferSizeMb
        }
        calculatedMemoryUsageMb = MemoryBudget.totalUsageMb(
            effectiveBufferMb,
            settings.parallelConnectionCount,
            Math.ceil(settings.parallelChunkSizeKb / 1024.0).toInt(),
            settings.useParallelConnections && settings.parallelNetworkEnabled
        )
    }

    private const val SEEK_BACK_BUFFER_THRESHOLD_MS = 10_000L
    private const val SEEK_BACKWARD_TOLERANCE_MS = 2_000L
    const val SEEK_SUPPRESS_TIMEOUT_MS = 800L

    // ─── LoadControl ──────────────────────────────────────────────────────────

    /**
     * Helper to read system memory directly from /proc/meminfo as a reliable fallback.
     */
    private fun getRamFromMemInfo(): Long {
        return try {
            val file = java.io.File("/proc/meminfo")
            if (file.exists()) {
                file.useLines { lines ->
                    val firstLine = lines.firstOrNull() ?: ""
                    val match = java.util.regex.Pattern.compile("\\d+").matcher(firstLine)
                    if (match.find()) {
                        match.group().toLong() * 1024L
                    } else {
                        0L
                    }
                }
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    @Volatile
    private var cachedDevicePhysicalRamBytes: Long = 0L

    /**
     * Clears the cached RAM size. Useful for testing.
     */
    fun clearCache() {
        cachedDevicePhysicalRamBytes = 0L
    }

    /**
     * Gets the total physical memory of the device in bytes.
     */
    fun getDevicePhysicalRamBytes(context: Context): Long {
        if (cachedDevicePhysicalRamBytes > 0L) {
            return cachedDevicePhysicalRamBytes
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (activityManager != null) {
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            if (memoryInfo.totalMem > 0L) {
                cachedDevicePhysicalRamBytes = memoryInfo.totalMem
                return memoryInfo.totalMem
            }
        }
        val ram = getRamFromMemInfo()
        if (ram > 0L) {
            cachedDevicePhysicalRamBytes = ram
        }
        return ram
    }

    /**
     * Gets the total physical memory of the device in GB.
     */
    fun getDevicePhysicalRamGb(context: Context): Double {
        val totalBytes = getDevicePhysicalRamBytes(context)
        return totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    }

    /**
     * Gets a friendly, marketed description of the device physical memory.
     * Uses mid-point boundaries adjusted for up to 20% hardware reservations.
     */
    fun getFriendlyRamLabel(context: Context): String {
        val totalMem = getDevicePhysicalRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            totalMem <= 0L -> "Unknown"
            totalMem < 1.15 * gb -> "1 GB"
            totalMem < 1.45 * gb -> "1.5 GB"
            totalMem < 2.3 * gb -> "2 GB"
            totalMem < 3.2 * gb -> "3 GB"
            totalMem < 4.8 * gb -> "4 GB"
            totalMem < 6.8 * gb -> "6 GB"
            totalMem < 9.6 * gb -> "8 GB"
            totalMem < 13.8 * gb -> "12 GB"
            else -> "16 GB"
        }
    }

    /**
     * Target buffer size limit in MB for the device's RAM tier, before the heap cap in [getSafeBufferLimitMb].
     */
    fun getSafeNativeMemoryLimitMb(context: Context): Int {
        val totalMem = getDevicePhysicalRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            totalMem <= 0L -> 200 // Safe default
            totalMem < 1.15 * gb -> 100
            totalMem < 1.45 * gb -> 200
            totalMem < 2.3 * gb -> 200
            totalMem < 3.2 * gb -> 500
            totalMem < 4.8 * gb -> 1000
            totalMem < 6.8 * gb -> 1600
            else -> 2000
        }
    }

    /**
     * Largest buffer the player may plan for: the RAM tier limit, capped by the Java heap budget.
     * Media3's allocator and the parallel chunk buffers both live on the Java heap.
     */
    fun getSafeBufferLimitMb(context: Context): Int =
        getSafeNativeMemoryLimitMb(context).coerceAtMost(MemoryBudget.budgetMb)

    /** Warning threshold for the buffer slider, capped like [getSafeBufferLimitMb]. */
    fun getWarningBufferLimitMb(context: Context): Int =
        getWarningNativeMemoryLimitMb(context).coerceAtMost(MemoryBudget.budgetMb)

    /**
     * Warning buffer size in MB for the device's RAM tier, before the heap cap.
     */
    fun getWarningNativeMemoryLimitMb(context: Context): Int {
        val totalMem = getDevicePhysicalRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            totalMem <= 0L -> 260
            totalMem < 1.15 * gb -> 130
            totalMem < 1.45 * gb -> 260
            totalMem < 2.3 * gb -> 260
            totalMem < 3.2 * gb -> 650
            totalMem < 4.8 * gb -> 1200
            totalMem < 6.8 * gb -> 2000
            else -> 2500
        }
    }

    // Both buffers hold bitrate times duration, so half of minBufferMs gives the back buffer a third of the target whatever the stream.
    private fun effectiveBackBufferMs(): Int {
        if (backBufferMs <= 0) return 0
        val ceiling = (minBufferMs.toLong() * BACK_BUFFER_TARGET_SHARE_NUM / BACK_BUFFER_TARGET_SHARE_DEN).toInt()
        return backBufferMs.coerceAtMost(ceiling)
    }

    /**
     * Builds a [DefaultLoadControl] tuned for Fetch performance when enabled,
     * or a standard ExoPlayer [DefaultLoadControl] when disabled.
     */
    fun buildLoadControl(context: Context? = null, chunkOverheadMb: Int = 0): DefaultLoadControl {
        return if (enabled) {
            val effectiveTargetBufferMb = (targetBufferSizeMb - chunkOverheadMb)
                .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
            val targetBufferBytes = (effectiveTargetBufferMb.toLong() * 1024L * 1024L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val allocator = DefaultAllocator(true, DEFAULT_FETCH_ALLOCATOR_SEGMENT_SIZE, 0)
            liveAllocator = allocator
            android.util.Log.i(
                "ExoPerformance",
                "buildLoadControl: targetBufferSizeMb=$targetBufferSizeMb, chunkOverheadMb=$chunkOverheadMb, effectiveTargetBufferMb=$effectiveTargetBufferMb, targetBytes=$targetBufferBytes, backBufferMs=${effectiveBackBufferMs()} (set=$backBufferMs)"
            )
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setTargetBufferBytes(targetBufferBytes)
                .setBufferDurationsMs(
                    minBufferMs,
                    maxBufferMs,
                    bufferForPlaybackMs,
                    bufferForPlaybackAfterRebufferMs
                )
                // The byte target has to gate everything the allocator holds, or the back buffer
                // is charged on top of it and the configured size is not a limit at all.
                .setPrioritizeTimeOverSizeThresholds(false)
                // Forward buffer protects playback and the back buffer only protects a seek back,
                // so the back buffer is the side that gives way when both cannot fit the target.
                .setBackBuffer(effectiveBackBufferMs(), true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setTargetBufferBytes(100 * 1024 * 1024)
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    70_000,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    5_000
                )
                .build()
        }
    }

    // ─── BandwidthMeter ───────────────────────────────────────────────────────

    /**
     * Builds a [DefaultBandwidthMeter] with an aggressive initial estimate when
     * enabled, or the platform default when disabled.
     */
    fun buildBandwidthMeter(context: Context): DefaultBandwidthMeter {
        return if (enabled) {
            DefaultBandwidthMeter.Builder(context)
                .setInitialBitrateEstimate(DEFAULT_FETCH_INITIAL_BITRATE_ESTIMATE)
                .build()
        } else {
            DefaultBandwidthMeter.Builder(context).build()
        }
    }

    // ─── Seek / Scrubbing ─────────────────────────────────────────────────────

    /**
     * Returns [ScrubbingModeParameters] that disable audio/metadata decoding and
     * boost codec operating rate for the fastest possible seek, or `null` when
     * performance mode is off.
     */
    fun buildScrubbingParams(): ScrubbingModeParameters? {
        if (!enabled) return null
        return ScrubbingModeParameters.Builder()
            .setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA))
            .setShouldIncreaseCodecOperatingRate(true)
            .setAllowSkippingMediaCodecFlush(true)
            .setShouldEnableDynamicScheduling(true)
            .build()
    }

    /**
     * Returns `true` when the seek target [positionMs] falls within the player's
     * already-buffered window (forward into [Player.getBufferedPosition] or
     * backward into the retained back-buffer).
     *
     * Only meaningful when performance mode is enabled; returns `false` otherwise.
     */
    fun isSeekInBuffer(player: ExoPlayer, positionMs: Long): Boolean {
        if (!enabled) return false
        val bufferedPos = player.bufferedPosition
        val currentPos = player.currentPosition
        val backBufferStart = (currentPos - SEEK_BACK_BUFFER_THRESHOLD_MS - SEEK_BACKWARD_TOLERANCE_MS)
            .coerceAtLeast(0L)
        return positionMs in backBufferStart..bufferedPos
    }

    // ─── Buffering UI ─────────────────────────────────────────────────────────

    /**
     * Determines whether transient buffering UI should be suppressed during a
     * seek operation. Returns `false` when performance mode is disabled so that
     * the stock buffering indicator always shows.
     *
     * @param suppressBufferingUiForSeek  Flag set when an in-buffer seek is active.
     * @param seekBufferingUiDeferred     Flag set during the 1 s grace window.
     * @param isBuffering                 Current [Player.STATE_BUFFERING] state.
     */
    fun shouldSuppressBufferingUi(
        suppressBufferingUiForSeek: Boolean,
        seekBufferingUiDeferred: Boolean,
        isBuffering: Boolean
    ): Boolean {
        if (!enabled) return false
        return (suppressBufferingUiForSeek && isBuffering) ||
            (seekBufferingUiDeferred && isBuffering)
    }

    // ─── Networking ───────────────────────────────────────────────────────────

    /**
     * Puts the given [builder] on the shared connection pool. Playback stays on HTTP/1.1: on
     * HTTP/2 the player made about as much Java garbage as it downloaded, for no speed gain.
     */
    fun applyNetworkOptimizations(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder =
        builder.connectionPool(sharedConnectionPool)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))

    // ─── Audio Renderer ───────────────────────────────────────────────────────

    /**
     * Returns `true` when the audio renderer should bypass the codec for a
     * non-PCM format that the sink supports directly. Only active when
     * performance mode is enabled.
     */
    fun shouldBypassForNonPcmFormat(): Boolean {
        return enabled
    }

    // ─── Memory Logging ───────────────────────────────────────────────────────

    /**
     * Returns `true` when allocator memory logging should be active.
     */
    fun shouldLogMemoryFootprint(): Boolean {
        return enabled
    }

    // ─── Track Rebuild Guard ──────────────────────────────────────────────────

    /**
     * Returns `true` when track selection rebuild should be skipped after seeks
     * (only allow on first ready). When disabled, always rebuilds (stock behaviour).
     */
    fun shouldGuardTrackRebuild(): Boolean {
        return enabled
    }
}
