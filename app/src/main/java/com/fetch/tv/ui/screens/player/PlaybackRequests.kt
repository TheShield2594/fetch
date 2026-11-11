package com.fetch.tv.ui.screens.player

import java.util.UUID

/**
 * What the player needs to open a stream that must not travel in a navigation route: the URL,
 * its request headers and the torrent sources. Stream URLs often carry access tokens, and a
 * route string is kept in saved state, logs and crash reports.
 */
data class PlaybackRequest(
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val sources: List<String>? = null,
)

/**
 * In-memory holder for [PlaybackRequest]s, keyed by the request id the player route carries.
 *
 * It lives only as long as the process: after the system kills the app, the restored player
 * finds no request for its id and has to resolve the stream again (see [isAvailable]) rather
 * than replaying a saved, possibly expired, tokenised URL.
 */
object PlaybackRequests {
    // A player screen holds one request; a few spare cover screens still on the back stack.
    private const val MAX_REQUESTS = 8

    private val requests = object : LinkedHashMap<String, PlaybackRequest>(MAX_REQUESTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlaybackRequest>?): Boolean =
            size > MAX_REQUESTS
    }

    /** Stores [request] and returns the id to put in the player route. */
    fun put(request: PlaybackRequest): String {
        val id = UUID.randomUUID().toString()
        synchronized(requests) { requests[id] = request }
        return id
    }

    fun get(id: String?): PlaybackRequest? {
        if (id.isNullOrEmpty()) return null
        return synchronized(requests) { requests[id] }
    }

    /** Whether [id] still names a request in this process (false after a process restore). */
    fun isAvailable(id: String?): Boolean = get(id) != null

    /** Points [id] at the stream now playing, e.g. after an in-place episode or source switch. */
    fun update(id: String?, request: PlaybackRequest) {
        if (id.isNullOrEmpty()) return
        synchronized(requests) { requests[id] = request }
    }

    fun remove(id: String?) {
        if (id.isNullOrEmpty()) return
        synchronized(requests) { requests.remove(id) }
    }
}
