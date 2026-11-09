package com.fetch.tv.domain.model

import com.fetch.tv.core.network.NetworkResult

/** How one source (an addon or a media server) finished its part of a stream search. */
enum class StreamSourceOutcome {
    /** Answered with streams; they arrive as that source's group in the results. */
    STREAMS,

    /** Answered "nothing for this title". Done, not a failure. */
    EMPTY,

    /** The request failed (unreachable, an error status, a server error). */
    FAILED,

    /** No answer within the source's time budget. */
    TIMED_OUT
}

/**
 * One step of a stream search: the streams found so far, and how each source that has finished
 * did, keyed by the source's display name (the name its chip shows). A search replayed from the
 * session cache carries the outcomes it recorded the first time.
 */
data class StreamSearchUpdate(
    val result: NetworkResult<List<AddonStreams>>,
    val sourceOutcomes: Map<String, StreamSourceOutcome> = emptyMap()
)
