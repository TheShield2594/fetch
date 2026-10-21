package com.fetch.tv.screenshots

import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import com.fetch.tv.domain.model.Video
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.ui.screens.home.ContinueWatchingItem

/** Made-up titles for the README screenshots; artwork comes from [SampleArtwork]. */
internal object Samples {
    private data class Title(val name: String, val hue: Int, val type: ContentType, val year: String, val genres: List<String>)

    private val titles = listOf(
        Title("The Long Field", 24, ContentType.SERIES, "2024", listOf("Drama")),
        Title("Northbound", 160, ContentType.SERIES, "2023", listOf("Thriller")),
        Title("Hollow Creek", 210, ContentType.SERIES, "2022", listOf("Mystery")),
        Title("Kestrel", 285, ContentType.MOVIE, "2025", listOf("Action")),
        Title("Signal Lost", 195, ContentType.SERIES, "2021", listOf("Sci-Fi")),
        Title("Paper Moons", 40, ContentType.MOVIE, "2024", listOf("Comedy")),
        Title("Ash and Iron", 8, ContentType.MOVIE, "2023", listOf("Fantasy")),
        Title("Low Tide", 180, ContentType.MOVIE, "2022", listOf("Drama")),
        Title("The Quiet Orbit", 230, ContentType.MOVIE, "2025", listOf("Sci-Fi")),
        Title("Glasshouse", 120, ContentType.SERIES, "2024", listOf("Drama")),
        Title("Saltwater Kings", 200, ContentType.SERIES, "2020", listOf("Crime")),
        Title("Ember Season", 15, ContentType.MOVIE, "2021", listOf("Romance"))
    )

    private fun preview(t: Title) = MetaPreview(
        id = "sample-" + t.name.lowercase().replace(' ', '-'),
        type = t.type,
        name = t.name,
        poster = SampleArtwork.poster(t.name, t.hue),
        posterShape = PosterShape.POSTER,
        background = SampleArtwork.backdrop(t.name, t.hue),
        logo = SampleArtwork.logo(t.name),
        landscapePoster = SampleArtwork.thumb(t.name, t.hue),
        description = "A sample title used to show Fetch's layout.",
        releaseInfo = t.year,
        imdbRating = 7.4f + (t.hue % 15) / 10f,
        genres = t.genres
    )

    private fun row(id: String, name: String, picks: List<Int>, type: ContentType) = CatalogRow(
        addonId = "sample.addon",
        addonName = "Sample",
        addonBaseUrl = "https://example.invalid",
        catalogId = id,
        catalogName = name,
        type = type,
        items = picks.map { preview(titles[it]) }
    )

    val catalogRows = listOf(
        row("unwatched-scifi", "Unwatched sci-fi", listOf(8, 4, 1, 9, 6, 2), ContentType.MOVIE),
        row("trending", "Trending", listOf(3, 5, 10, 0, 11, 7), ContentType.MOVIE)
    )

    val continueWatching: List<ContinueWatchingItem> = listOf(0, 2, 3, 4, 1).mapIndexed { i, idx ->
        val t = titles[idx]
        val series = t.type == ContentType.SERIES
        ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "sample-" + t.name.lowercase().replace(' ', '-'),
                contentType = if (series) "series" else "movie",
                name = t.name,
                poster = SampleArtwork.poster(t.name, t.hue),
                backdrop = SampleArtwork.backdrop(t.name, t.hue),
                logo = SampleArtwork.logo(t.name),
                videoId = "sample-$idx",
                season = if (series) 2 else null,
                episode = if (series) 5 + i else null,
                episodeTitle = if (series) "Episode ${5 + i}" else null,
                position = (20 + i * 12) * 60_000L,
                duration = 58 * 60_000L,
                lastWatched = 1_760_000_000_000L - i * 3_600_000L
            ),
            episodeThumbnail = SampleArtwork.thumb(t.name, t.hue),
            genres = t.genres,
            releaseInfo = t.year
        )
    }

    val detailMeta: Meta = titles[0].let { t ->
        Meta(
            id = "sample-long-field",
            type = ContentType.SERIES,
            name = t.name,
            poster = SampleArtwork.poster(t.name, t.hue),
            posterShape = PosterShape.POSTER,
            background = SampleArtwork.backdrop(t.name, t.hue),
            logo = SampleArtwork.logo(t.name),
            description = "Two sisters inherit a failing farm on the edge of the plains, and a neighbour who wants it more than they do.",
            releaseInfo = "2024–",
            imdbRating = 8.1f,
            genres = listOf("Drama", "Western"),
            runtime = "52 min",
            director = emptyList(),
            cast = emptyList(),
            videos = (1..8).map { ep ->
                Video(
                    id = "sample-long-field:2:$ep",
                    title = "Episode $ep",
                    released = "2024-0${(ep % 9) + 1}-01",
                    thumbnail = SampleArtwork.thumb(t.name, t.hue + ep * 6),
                    season = 2,
                    episode = ep,
                    overview = null
                )
            },
            ageRating = "TV-14",
            country = null,
            awards = null,
            language = "en",
            links = emptyList()
        )
    }

    private fun stream(addon: String, name: String, title: String, sizeGb: Double) = Stream(
        name = name,
        title = title,
        description = null,
        url = "https://example.invalid/${addon.lowercase()}",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = false,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = (sizeGb * 1_073_741_824).toLong()
        ),
        addonName = addon,
        addonLogo = null
    )

    val streams = listOf(
        stream("Torrentio", "[RD+] Torrentio\n4k DV", "The.Long.Field.S02E06.2160p.WEB-DL.DV.HDR.DDP5.1.Atmos-GRP", 18.2),
        stream("Comet", "[RD+] Comet 4K", "The.Long.Field.S02E06.2160p.WEB.H265.HDR10.DDP5.1-GRP", 9.8),
        stream("Jellyfin", "Jellyfin", "The Long Field S02E06 1080p", 4.1),
        stream("MediaFusion", "MediaFusion 1080p", "The.Long.Field.S02E06.1080p.WEB.x265.DD5.1-GRP", 2.3)
    )
}
