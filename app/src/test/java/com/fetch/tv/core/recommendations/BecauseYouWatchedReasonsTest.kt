package com.fetch.tv.core.recommendations

import com.fetch.tv.data.remote.api.TmdbCastMember
import com.fetch.tv.data.remote.api.TmdbCreatedBy
import com.fetch.tv.data.remote.api.TmdbCreditsResponse
import com.fetch.tv.data.remote.api.TmdbCrewMember
import com.fetch.tv.data.remote.api.TmdbDetailsResponse
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BecauseYouWatchedReasonsTest {

    private val nolan = CreditPerson(1, "Christopher Nolan")
    private val jonah = CreditPerson(2, "Jonathan Nolan")
    private val bale = CreditPerson(3, "Christian Bale")
    private val caine = CreditPerson(4, "Michael Caine")
    private val ledger = CreditPerson(5, "Heath Ledger")
    private val extra = CreditPerson(6, "Extra")

    private val seed = TitleCredits(
        directors = listOf(nolan),
        writers = listOf(jonah),
        cast = listOf(bale, ledger, caine, extra)
    )

    @Test
    fun `shared collection wins over shared people`() {
        val reason = becauseYouWatchedReason(seed, seed, isSeries = false, sharedCollectionName = "The Dark Knight Collection")
        assertEquals(BecauseYouWatchedReason.SameCollection("The Dark Knight Collection"), reason)
    }

    @Test
    fun `shared director is named`() {
        val candidate = TitleCredits(directors = listOf(nolan), cast = listOf(CreditPerson(99, "Other")))
        assertEquals(BecauseYouWatchedReason.SameDirector("Christopher Nolan"), becauseYouWatchedReason(seed, candidate, isSeries = false))
    }

    @Test
    fun `shared show creator reads as creator`() {
        val candidate = TitleCredits(directors = listOf(nolan))
        assertEquals(BecauseYouWatchedReason.SameCreator("Christopher Nolan"), becauseYouWatchedReason(seed, candidate, isSeries = true))
    }

    @Test
    fun `shared writer is used when no director is shared`() {
        val candidate = TitleCredits(writers = listOf(jonah))
        assertEquals(BecauseYouWatchedReason.SameWriter("Jonathan Nolan"), becauseYouWatchedReason(seed, candidate, isSeries = false))
    }

    @Test
    fun `seed lead billed on the candidate counts as shared cast`() {
        val candidate = TitleCredits(cast = listOf(CreditPerson(10, "A"), CreditPerson(11, "B"), caine))
        assertEquals(BecauseYouWatchedReason.SameLeadCast("Michael Caine"), becauseYouWatchedReason(seed, candidate, isSeries = false))
    }

    @Test
    fun `actor below the seed's leads is not a reason`() {
        val candidate = TitleCredits(cast = listOf(extra))
        assertNull(becauseYouWatchedReason(seed, candidate, isSeries = false))
    }

    @Test
    fun `seed lead billed low on the candidate is not a reason`() {
        val candidate = TitleCredits(
            cast = (10..14).map { CreditPerson(it, "Actor $it") } + bale
        )
        assertNull(becauseYouWatchedReason(seed, candidate, isSeries = false))
    }

    @Test
    fun `candidate sharing nothing has no reason`() {
        val candidate = TitleCredits(
            directors = listOf(CreditPerson(20, "Someone")),
            cast = listOf(CreditPerson(21, "Else"))
        )
        assertNull(becauseYouWatchedReason(seed, candidate, isSeries = false))
        assertNull(becauseYouWatchedReason(seed, candidate, isSeries = false, sharedCollectionName = " "))
    }

    @Test
    fun `movie credits keep directors, screenplay writers and billing order`() {
        val credits = TmdbCreditsResponse(
            cast = listOf(
                TmdbCastMember(id = 3, name = "Christian Bale"),
                TmdbCastMember(id = 5, name = "Heath Ledger")
            ),
            crew = listOf(
                TmdbCrewMember(id = 1, name = "Christopher Nolan", job = "Director"),
                TmdbCrewMember(id = 2, name = "Jonathan Nolan", job = "Screenplay"),
                TmdbCrewMember(id = 7, name = "Hans Zimmer", job = "Original Music Composer"),
                TmdbCrewMember(id = null, name = "No id", job = "Director")
            )
        ).toTitleCredits(isSeries = false, tvDetails = null)

        assertEquals(listOf(nolan), credits.directors)
        assertEquals(listOf(jonah), credits.writers)
        assertEquals(listOf(bale, ledger), credits.cast)
    }

    @Test
    fun `show credits take creators from details`() {
        val credits = TmdbCreditsResponse(
            crew = listOf(TmdbCrewMember(id = 9, name = "Episode Director", job = "Director"))
        ).toTitleCredits(
            isSeries = true,
            tvDetails = TmdbDetailsResponse(id = 1, createdBy = listOf(TmdbCreatedBy(id = 2, name = "Jonathan Nolan")))
        )

        assertEquals(listOf(jonah), credits.directors)
        assertEquals(emptyList<CreditPerson>(), credits.writers)
    }

    @Test
    fun `seeds are the latest completed distinct titles`() {
        val progress = listOf(
            progress("tt1", "Finished movie", lastWatched = 100, position = 95),
            progress("tt2", "Half watched", lastWatched = 300, position = 40),
            progress("tt3", "Series", lastWatched = 200, position = 100, type = "series")
        )
        val watched = listOf(
            WatchedItem(contentId = "tt4", contentType = "movie", title = "Marked watched", watchedAt = 250),
            WatchedItem(contentId = "TT1", contentType = "movie", title = "Finished movie", watchedAt = 50)
        )

        val seeds = latestCompletedSeeds(progress, watched, limit = 3)

        assertEquals(listOf("tt4", "tt3", "tt1"), seeds.map { it.contentId })
        assertEquals(100L, seeds.last().completedAt)
    }

    @Test
    fun `no completed titles means no seeds`() {
        val progress = listOf(progress("tt2", "Half watched", lastWatched = 300, position = 40))
        assertEquals(emptyList<BecauseYouWatchedSeed>(), latestCompletedSeeds(progress, emptyList(), limit = 2))
    }

    private fun progress(
        id: String,
        name: String,
        lastWatched: Long,
        position: Long,
        type: String = "movie"
    ) = WatchProgress(
        contentId = id,
        contentType = type,
        name = name,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = id,
        season = null,
        episode = null,
        episodeTitle = null,
        position = position,
        duration = 100,
        lastWatched = lastWatched
    )
}
