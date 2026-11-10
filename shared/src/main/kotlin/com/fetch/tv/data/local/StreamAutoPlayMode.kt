package com.fetch.tv.data.local

/** How Play picks a source; the TV stores it by name, the desktop maps its own setting to it. */
enum class StreamAutoPlayMode {
    MANUAL, FIRST_STREAM, REGEX_MATCH,
    /** Parse every result, rank it against the ranking rules and the device, play the winner. */
    BEST_MATCH
}
