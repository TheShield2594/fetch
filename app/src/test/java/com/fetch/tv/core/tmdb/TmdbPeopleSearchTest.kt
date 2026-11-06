package com.fetch.tv.core.tmdb

import com.fetch.tv.core.tmdb.TmdbPeopleSearch.Companion.toPeople
import com.fetch.tv.data.remote.api.TmdbPersonKnownFor
import com.fetch.tv.data.remote.api.TmdbPersonSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbPeopleSearchTest {

    @Test
    fun `maps photo and known-for titles`() {
        val people = listOf(
            TmdbPersonSearchResult(
                id = 6384,
                name = "Keanu Reeves",
                profilePath = "/abc.jpg",
                knownForDepartment = "Acting",
                knownFor = listOf(
                    TmdbPersonKnownFor(title = "The Matrix"),
                    TmdbPersonKnownFor(name = "Some Show"),
                    TmdbPersonKnownFor(title = "John Wick")
                )
            )
        ).toPeople()

        val result = people.single()
        assertEquals("Keanu Reeves", result.person.name)
        assertEquals(6384, result.person.tmdbId)
        assertEquals("https://image.tmdb.org/t/p/w185/abc.jpg", result.person.photo)
        assertEquals("The Matrix, Some Show", result.person.character)
        assertFalse(result.preferCrew)
    }

    @Test
    fun `directors open on crew credits`() {
        val result = listOf(
            TmdbPersonSearchResult(id = 1, name = "A Director", profilePath = "/d.jpg", knownForDepartment = "Directing")
        ).toPeople().single()
        assertTrue(result.preferCrew)
        assertNull(result.person.character)
    }

    @Test
    fun `drops adult, nameless and empty stub results, and duplicates`() {
        val people = listOf(
            TmdbPersonSearchResult(id = 1, name = "Adult", profilePath = "/a.jpg", adult = true),
            TmdbPersonSearchResult(id = 2, name = " ", profilePath = "/b.jpg"),
            TmdbPersonSearchResult(id = 3, name = "Stub"),
            TmdbPersonSearchResult(id = 4, name = "Kept", profilePath = "/k.jpg"),
            TmdbPersonSearchResult(id = 4, name = "Kept", profilePath = "/k.jpg")
        ).toPeople()

        assertEquals(listOf(4), people.map { it.person.tmdbId })
    }
}
