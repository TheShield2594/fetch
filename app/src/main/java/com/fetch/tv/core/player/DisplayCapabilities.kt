package com.fetch.tv.core.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Heuristic detection of whether the current display supports refresh-rate
 * and resolution switching via Display.getSupportedModes().
 *
 * EDID-based detection is best-effort: some boxes/TVs misreport modes in
 * either direction. The result is intended to inform the UI (so the user
 * can disable a setting that appears to do nothing on their hardware), not
 * to gate execution — FrameRateUtils already silently no-ops when no
 * matching mode is available.
 */
object DisplayCapabilities {

    private const val TAG = "DisplayCapabilities"

    data class Snapshot(
        val supportsFrameRateSwitching: Boolean,
        val supportsResolutionSwitching: Boolean,
        val supportedModes: List<Display.Mode>,
        val currentModeId: Int,
        val apiSupported: Boolean,
    ) {
        companion object {
            val Unknown = Snapshot(
                supportsFrameRateSwitching = false,
                supportsResolutionSwitching = false,
                supportedModes = emptyList(),
                currentModeId = -1,
                apiSupported = false,
            )
        }
    }

    fun detect(activity: Activity): Snapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Snapshot.Unknown
        }
        val display = activity.window?.decorView?.display ?: return Snapshot.Unknown
        val modes = display.supportedModes.toList()
        val current = display.mode
        val (afr, res) = deriveSupport(modes, current.modeId)
        return Snapshot(
            supportsFrameRateSwitching = afr,
            supportsResolutionSwitching = res,
            supportedModes = modes,
            currentModeId = current.modeId,
            apiSupported = true,
        )
    }

    fun logSummary(snapshot: Snapshot) {
        if (!snapshot.apiSupported) {
            Log.i(TAG, "api=${Build.VERSION.SDK_INT} apiSupported=false (no display introspection)")
            return
        }
        val current = snapshot.supportedModes.firstOrNull { it.modeId == snapshot.currentModeId }
        val rates = snapshot.supportedModes
            .map { roundedMilliHz(it.refreshRate) / 1000f }
            .distinct()
            .sorted()
            .joinToString(",")
        val resolutions = snapshot.supportedModes
            .map { "${it.physicalWidth}x${it.physicalHeight}" }
            .distinct()
            .joinToString(",")
        val currentDesc = current?.let {
            "${it.physicalWidth}x${it.physicalHeight}@${"%.3f".format(it.refreshRate)}Hz"
        } ?: "unknown"
        Log.i(
            TAG,
            "api=${Build.VERSION.SDK_INT} current=$currentDesc modeCount=${snapshot.supportedModes.size} " +
                "rates=[$rates] resolutions=[$resolutions] " +
                "afrSupported=${snapshot.supportsFrameRateSwitching} " +
                "resSupported=${snapshot.supportsResolutionSwitching}"
        )
    }

    /**
     * Pure predicate, extracted for unit testing.
     *
     * Returns Pair(supportsFrameRateSwitching, supportsResolutionSwitching).
     * Refresh rates are deduped at millihertz precision to avoid float-noise
     * false positives like 59.94f != 59.940002f.
     */
    internal fun deriveSupport(modes: List<Display.Mode>, currentModeId: Int): Pair<Boolean, Boolean> {
        if (modes.isEmpty()) return false to false
        val current = modes.firstOrNull { it.modeId == currentModeId } ?: modes.first()
        val sameResModes = modes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val distinctRates = sameResModes.map { roundedMilliHz(it.refreshRate) }.toSet()
        val distinctResolutions = modes.map { it.physicalWidth to it.physicalHeight }.toSet()
        return (distinctRates.size >= 2) to (distinctResolutions.size >= 2)
    }

    private fun roundedMilliHz(refreshRate: Float): Int = (refreshRate * 1000f).roundToInt()

    /**
     * Whether refresh-rate matching should default to "start" on this device (#204): the
     * built-in display can switch modes and has one that shows film without judder, and the
     * device isn't a known-bad mode switcher. Reads the default display through the
     * application context, so no Activity is needed. Any failure counts as not capable.
     */
    fun defaultsToFrameRateMatching(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (isKnownBadModeSwitcher(Build.MANUFACTURER, Build.MODEL, Build.DEVICE)) return false
        return try {
            val displayManager = context.getSystemService(DisplayManager::class.java) ?: return false
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
            val modes = display.supportedModes.map {
                FrameRateUtils.DisplayModeSpec(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
            }
            supportsFilmRateMatching(modes, display.mode.modeId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read display modes for the frame-rate matching default", e)
            false
        }
    }

    /**
     * Pure predicate, extracted for unit testing. True when the display offers at least two
     * refresh rates at its current resolution and one of them is 23.976/24 Hz or a whole
     * multiple of it (47.952/48, 119.88/120, ...), so 24p content can be shown without
     * 3:2 pulldown judder.
     */
    internal fun supportsFilmRateMatching(
        modes: List<FrameRateUtils.DisplayModeSpec>,
        currentModeId: Int,
    ): Boolean {
        if (modes.isEmpty()) return false
        val current = modes.firstOrNull { it.modeId == currentModeId } ?: modes.first()
        val sameRes = modes.filter { it.width == current.width && it.height == current.height }
        val distinctRates = sameRes.map { roundedMilliHz(it.refreshRate) }.toSet()
        if (distinctRates.size < 2) return false
        return sameRes.any { isFilmRateMultiple(it.refreshRate) }
    }

    private val FILM_RATES = floatArrayOf(24000f / 1001f, 24f)

    private fun isFilmRateMultiple(refreshRate: Float): Boolean {
        if (refreshRate <= 0f) return false
        return FILM_RATES.any { base ->
            val multiple = (refreshRate / base).roundToInt()
            multiple >= 1 && abs(refreshRate - multiple * base) <= FILM_RATE_TOLERANCE_HZ * multiple
        }
    }

    private const val FILM_RATE_TOLERANCE_HZ = 0.01f

    /**
     * Devices that report switchable modes but where switching misbehaves (long blackouts,
     * HDMI handshake loops, or a mode that never takes), matched on lower-cased
     * [Build.MANUFACTURER] and [Build.MODEL] or [Build.DEVICE]. These keep "off" as the
     * default; anyone can still turn matching on in Settings. Add a device here once a
     * report confirms it, with the report in the comment.
     */
    internal val knownBadModeSwitchers: List<Pair<String, String>> = emptyList()

    internal fun isKnownBadModeSwitcher(manufacturer: String?, model: String?, device: String?): Boolean {
        val m = manufacturer.orEmpty().trim().lowercase()
        val mo = model.orEmpty().trim().lowercase()
        val d = device.orEmpty().trim().lowercase()
        return knownBadModeSwitchers.any { (badManufacturer, badModel) ->
            m == badManufacturer && (mo == badModel || d == badModel)
        }
    }
}
