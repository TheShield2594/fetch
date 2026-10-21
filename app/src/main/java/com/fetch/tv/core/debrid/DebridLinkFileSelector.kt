package com.fetch.tv.core.debrid

import com.fetch.tv.data.remote.dto.DebridLinkTorrentFileDto
import com.fetch.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridLinkFileSelector @Inject constructor() {
    fun selectFile(
        files: List<DebridLinkTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): DebridLinkTorrentFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.name.orEmpty() },
        isPlayable = { it.isReady && it.displayName().hasDebridVideoExtension() },
        size = { it.size ?: 0L },
    )
}
