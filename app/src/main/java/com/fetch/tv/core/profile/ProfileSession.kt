package com.fetch.tv.core.profile

import java.util.concurrent.ConcurrentHashMap

/**
 * One period of a profile being active: its id and the in-memory caches that belong to it.
 *
 * [ProfileManager.activeSession] hands out a new session whenever the active profile changes, so
 * a cache kept here can't outlive its profile or be read under another profile's id. Capture the
 * session (or [profileId]) once at the start of an operation and use it throughout, instead of
 * reading the active profile again part way through.
 */
class ProfileSession internal constructor(val profileId: Int) {

    /** Identifies one cache in a session. Compare by identity: declare each key once. */
    class Key<T : Any>(private val name: String) {
        override fun toString(): String = name
    }

    private val caches = ConcurrentHashMap<Key<*>, Any>()

    /** This session's value for [key], created by [create] on first use. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> cache(key: Key<T>, create: () -> T): T =
        caches.computeIfAbsent(key) { create() } as T

    override fun toString(): String = "ProfileSession(profileId=$profileId)"
}
