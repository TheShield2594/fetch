package com.fetch.tv.core.memory

import android.content.ComponentCallbacks2
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Releases memory the app can rebuild when the system asks for it (`onTrimMemory`).
 *
 * Holders of large rebuildable state register here when they are created, so a trim never
 * builds a component just to empty it. [FetchApplication] forwards every trim level.
 */
object MemoryTrim {
    private const val TAG = "MemoryTrim"

    /** How much to give back. Levels map from [ComponentCallbacks2] constants by [stageFor]. */
    enum class Stage {
        /** The UI is no longer visible: drop what only the visible UI needs (the trailer player). */
        UI_HIDDEN,
        /** The process is in the background LRU list: drop in-memory caches too. */
        BACKGROUND,
    }

    fun interface Trimmable {
        fun trim(stage: Stage)
    }

    private val trimmables = CopyOnWriteArrayList<Trimmable>()

    fun register(trimmable: Trimmable) {
        trimmables.addIfAbsent(trimmable)
    }

    fun unregister(trimmable: Trimmable) {
        trimmables.remove(trimmable)
    }

    /**
     * Maps a trim level to a [Stage], or null for levels that need nothing from us.
     *
     * RUNNING_* levels arrive while the app is in the foreground (possibly mid playback), so
     * nothing visible is released for them; Coil trims its own image memory cache for every level.
     */
    @Suppress("DEPRECATION")
    fun stageFor(level: Int): Stage? = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> Stage.BACKGROUND
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Stage.UI_HIDDEN
        else -> null
    }

    fun onTrimMemory(level: Int) {
        val stage = stageFor(level) ?: return
        Log.i(TAG, "onTrimMemory level=$level stage=$stage holders=${trimmables.size}")
        trimmables.forEach { trimmable ->
            runCatching { trimmable.trim(stage) }
                .onFailure { Log.w(TAG, "trim failed for $trimmable", it) }
        }
    }
}
