package com.fetch.tv.core.parental

import com.fetch.tv.core.tmdb.TmdbEntityBrowseData
import com.fetch.tv.core.tmdb.TmdbEntityHeader
import com.fetch.tv.core.tmdb.TmdbEntityKind
import com.fetch.tv.core.tmdb.TmdbEntityMediaType
import com.fetch.tv.core.tmdb.TmdbEntityRail
import com.fetch.tv.core.tmdb.TmdbEntityRailType
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.LibraryEntry
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PersonDetail
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.ui.screens.cast.CastDetailUiState
import com.fetch.tv.ui.screens.cast.filteredForProfile
import com.fetch.tv.ui.screens.detail.MetaDetailsUiState
import com.fetch.tv.ui.screens.detail.filteredForProfile
import com.fetch.tv.ui.screens.library.LibraryUiState
import com.fetch.tv.ui.screens.library.filteredForProfile
import com.fetch.tv.ui.screens.tmdb.TmdbEntityBrowseUiState
import com.fetch.tv.ui.screens.tmdb.filteredForProfile
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Browse screens leave out what the active profile may not see (#169). */
class ParentalBrowseFiltersTest {

    private val blocked = setOf("tt-r-rated", "tt-hidden")

    private val restricted = mockk<ParentalControls> {
        every { filtersContent() } returns true
        every { filterPreviews(any()) } answers { firstArg<List<MetaPreview>>().filter { it.id !in blocked } }
        every { filterAllowed(any<List<Any>>(), any()) } answers {
            val query = secondArg<(Any) -> CertificationQuery>()
            firstArg<List<Any>>().filter { query(it).contentId !in blocked }
        }
    }

    private val unrestricted = mockk<ParentalControls> {
        every { filtersContent() } returns false
    }

    private fun preview(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private val mixed = listOf(preview("tt-kids"), preview("tt-r-rated"), preview("tt-hidden"))

    @Test
    fun `detail page drops More like this and collection titles`() {
        val state = MetaDetailsUiState(moreLikeThis = mixed, collection = mixed)

        val filtered = state.filteredForProfile(restricted)

        assertEquals(listOf("tt-kids"), filtered.moreLikeThis.map { it.id })
        assertEquals(listOf("tt-kids"), filtered.collection.map { it.id })
    }

    @Test
    fun `an unrestricted profile sees everything unchanged`() {
        val state = MetaDetailsUiState(moreLikeThis = mixed)

        assertSame(state, state.filteredForProfile(unrestricted))
    }

    @Test
    fun `cast pages drop movie and TV credits`() {
        val person = PersonDetail(
            tmdbId = 1,
            name = "Voice actor",
            biography = null,
            birthday = null,
            deathday = null,
            placeOfBirth = null,
            profilePhoto = null,
            knownFor = null,
            movieCredits = mixed,
            tvCredits = mixed
        )

        val filtered = CastDetailUiState.Success(person).filteredForProfile(restricted) as CastDetailUiState.Success

        assertEquals(listOf("tt-kids"), filtered.personDetail.movieCredits.map { it.id })
        assertEquals(listOf("tt-kids"), filtered.personDetail.tvCredits.map { it.id })
    }

    @Test
    fun `studio and network rails drop titles, and rails left empty`() {
        val data = TmdbEntityBrowseData(
            header = TmdbEntityHeader(1, TmdbEntityKind.COMPANY, "Studio", null, null, null, null),
            rails = listOf(
                TmdbEntityRail(TmdbEntityMediaType.MOVIE, TmdbEntityRailType.POPULAR, mixed),
                TmdbEntityRail(TmdbEntityMediaType.MOVIE, TmdbEntityRailType.TOP_RATED, listOf(preview("tt-r-rated")))
            )
        )

        val filtered = TmdbEntityBrowseUiState.Success(data).filteredForProfile(restricted) as TmdbEntityBrowseUiState.Success

        assertEquals(listOf(TmdbEntityRailType.POPULAR), filtered.data.rails.map { it.railType })
        assertEquals(listOf("tt-kids"), filtered.data.rails.single().items.map { it.id })
    }

    @Test
    fun `library drops entries from every list`() {
        fun entry(id: String) = LibraryEntry(
            id = id,
            type = "movie",
            name = id,
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null
        )
        val entries = listOf(entry("tt-kids"), entry("tt-r-rated"))

        val filtered = LibraryUiState(allItems = entries, visibleItems = entries).filteredForProfile(restricted)

        assertEquals(listOf("tt-kids"), filtered.allItems.map { it.id })
        assertEquals(listOf("tt-kids"), filtered.visibleItems.map { it.id })
    }
}
