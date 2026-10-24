package com.fetch.tv.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long a stream, catalog or meta request may take before an addon counts as timed out. */
@Singleton
class AddonTimeoutPreferences @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _timeoutSeconds = MutableStateFlow(storedTimeoutSeconds())
    val timeoutSeconds: StateFlow<Int> = _timeoutSeconds.asStateFlow()

    private val _skipSlowAddons = MutableStateFlow(preferences.getBoolean(KEY_SKIP_SLOW, false))
    /** "Don't wait for slow addons": use [FAST_TIMEOUT_SECONDS] whatever [timeoutSeconds] says. */
    val skipSlowAddons: StateFlow<Boolean> = _skipSlowAddons.asStateFlow()

    /** The budget each addon request gets right now. */
    val effectiveTimeoutMs: Long
        get() = (if (_skipSlowAddons.value) FAST_TIMEOUT_SECONDS else _timeoutSeconds.value) * 1_000L

    fun setTimeoutSeconds(seconds: Int) {
        val value = seconds.takeIf { it in TIMEOUT_OPTIONS_SECONDS } ?: DEFAULT_TIMEOUT_SECONDS
        preferences.edit().putInt(KEY_TIMEOUT_SECONDS, value).apply()
        _timeoutSeconds.value = value
    }

    fun setSkipSlowAddons(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SKIP_SLOW, enabled).apply()
        _skipSlowAddons.value = enabled
    }

    private fun storedTimeoutSeconds(): Int =
        preferences.getInt(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)
            .takeIf { it in TIMEOUT_OPTIONS_SECONDS } ?: DEFAULT_TIMEOUT_SECONDS

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 25
        const val FAST_TIMEOUT_SECONDS = 8
        val TIMEOUT_OPTIONS_SECONDS = listOf(10, 15, 25, 40, 60)

        private const val PREFERENCES_NAME = "addon_requests"
        private const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        private const val KEY_SKIP_SLOW = "skip_slow_addons"
    }
}
