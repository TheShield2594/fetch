package com.fetch.tv.core.profile

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile slots whose data a delete hasn't finished removing, kept on disk.
 *
 * A deleted profile's slot is the next one a new profile gets. If part of the cleanup failed (a
 * file that wouldn't delete, a database error, the app killed part way), whatever survived would
 * show up in that new profile. A slot is marked here before its cleanup starts and unmarked only
 * once every step has succeeded; [ProfileManager] retries marked slots and won't hand one out.
 */
@Singleton
class PendingProfileCleanup internal constructor(private val prefsProvider: () -> SharedPreferences) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this({ context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) })

    private val prefs by lazy(prefsProvider)
    private val lock = Any()

    val slots: Set<Int>
        get() = synchronized(lock) {
            prefs.getStringSet(KEY_SLOTS, emptySet()).orEmpty().mapNotNullTo(HashSet()) { it.toIntOrNull() }
        }

    fun mark(slot: Int) = update { it + slot }

    fun clear(slot: Int) = update { it - slot }

    private fun update(change: (Set<Int>) -> Set<Int>) {
        synchronized(lock) {
            val next = change(slots).mapTo(HashSet()) { it.toString() }
            // commit, not apply: the mark must still be there if the app dies right after.
            prefs.edit().putStringSet(KEY_SLOTS, next).commit()
        }
    }

    private companion object {
        const val PREFS_NAME = "profile_cleanup_pending"
        const val KEY_SLOTS = "slots"
    }
}
