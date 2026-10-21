package com.fetch.tv.core.debrid

import com.fetch.tv.data.remote.dto.AllDebridMagnetFile
import com.fetch.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AllDebridFileSelector @Inject constructor() {
    fun selectFile(
        files: List<AllDebridMagnetFile>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): AllDebridMagnetFile? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.path },
        isPlayable = { it.displayName().hasDebridVideoExtension() },
        size = { it.size ?: 0L },
    )
}
