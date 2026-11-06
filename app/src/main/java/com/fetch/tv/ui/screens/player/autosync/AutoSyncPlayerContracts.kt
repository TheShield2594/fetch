package com.fetch.tv.ui.screens.player.autosync

internal data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
)
