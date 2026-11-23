package com.fetch.tv.core.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local changes a sync domain has not managed to send yet, per profile, kept on disk (#616).
 *
 * Collections, addons and Home rows are pushed a moment after each edit, and every sync pass
 * pulls the account's copy over the TV's. Without this, a push that failed (no network, the
 * website down) or never ran (the app killed within the debounce, a profile switch in between)
 * was simply forgotten, and the next pull put the older copy back over the edit.
 *
 * An edit [markDirty]s its domain; a pull of a dirty domain pushes first and keeps the local copy
 * if that fails; a successful push clears the mark unless another edit came in meanwhile.
 */
@Singleton
class PendingPushTracker internal constructor(private val prefsProvider: () -> SharedPreferences) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this({ context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) })

    private val prefs by lazy(prefsProvider)
    private val lock = Any()

    /** Records a local change to [domain] for [profileId] that the account doesn't have yet. */
    fun markDirty(domain: Domain, profileId: Int) {
        synchronized(lock) {
            val key = key(domain, profileId)
            // commit, not apply: the edit must still be known as unsent if the app dies right after.
            prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1L).commit()
        }
    }

    /** 0 when [domain] has nothing unsent for [profileId]; otherwise a counter that grows with each change. */
    fun revision(domain: Domain, profileId: Int): Long = synchronized(lock) { prefs.getLong(key(domain, profileId), 0L) }

    fun isDirty(domain: Domain, profileId: Int): Boolean = revision(domain, profileId) != 0L

    /**
     * Clears the mark after a push that read the local copy at [revision]. A change made while the
     * push was on its way has a newer revision and stays marked, so it goes up next time.
     */
    fun markSent(domain: Domain, profileId: Int, revision: Long) {
        if (revision == 0L) return
        synchronized(lock) {
            val key = key(domain, profileId)
            if (prefs.getLong(key, 0L) == revision) prefs.edit().remove(key).commit()
        }
    }

    /** Drops every mark of [profileId], when the profile is deleted. */
    fun clearProfile(profileId: Int) {
        synchronized(lock) {
            val editor = prefs.edit()
            Domain.entries.forEach { editor.remove(key(it, profileId)) }
            editor.commit()
        }
    }

    /** Drops every mark, when this TV signs out of its account or switches sync backend. */
    fun clearAll() {
        synchronized(lock) { prefs.edit().clear().commit() }
    }

    private fun key(domain: Domain, profileId: Int) = "${domain.storageKey}:$profileId"

    enum class Domain(val storageKey: String) {
        COLLECTIONS("collections"),
        ADDONS("addons"),
        HOME_LAYOUT("home_layout")
    }

    private companion object {
        const val PREFS_NAME = "sync_pending_pushes"
    }
}
