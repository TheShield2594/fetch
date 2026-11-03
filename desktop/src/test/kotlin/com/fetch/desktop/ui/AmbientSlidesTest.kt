package com.fetch.desktop.ui

import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientSlidesTest {
    private fun p(id: String, backdrop: String? = "https://img/$id.jpg", released: String? = null) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = id, poster = null, posterShape = PosterShape.POSTER, background = backdrop,
        logo = null, description = null, releaseInfo = null, imdbRating = null, genres = listOf("Drama", "Crime", "Thriller", "Extra"), released = released
    )

    private val rows = listOf(
        "a" to (1..40).map { p("a$it") },
        "b" to listOf(p("b1"), p("b2", backdrop = null), p("a1")),
        "c" to listOf(p("c1")),
        "d" to listOf(p("d1"))
    )

    @Test
    fun `the first row when none are chosen, at most 30 from it`() {
        val slides = ambientSlides(rows, emptyList(), hideUnreleased = false)
        assertEquals(30, slides.size)
        assertTrue(slides.all { it.id.startsWith("movie:a") })
        assertEquals(3, slides.first().genres.size)
    }

    @Test
    fun `chosen rows, up to three, only with a backdrop, no repeats`() {
        val slides = ambientSlides(rows, listOf("b", "c", "d", "a"), hideUnreleased = false)
        assertEquals(setOf("movie:b1", "movie:a1", "movie:c1", "movie:d1"), slides.map { it.id }.toSet())
    }

    @Test
    fun `unreleased titles are left out when Home hides them`() {
        val slides = ambientSlides(listOf("x" to listOf(p("x1", released = "2999-01-01"), p("x2"))), emptyList(), hideUnreleased = true)
        assertEquals(listOf("movie:x2"), slides.map { it.id })
    }
}
