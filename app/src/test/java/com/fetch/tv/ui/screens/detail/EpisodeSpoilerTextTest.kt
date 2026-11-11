package com.fetch.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeSpoilerTextTest {
    @Test
    fun `option on hides the title and overview of an unwatched episode`() {
        val text = episodeSpoilerText(
            title = "The Twist",
            overview = "Everyone dies.",
            hideSpoilers = true,
            isWatched = false,
            hiddenTitle = neutralEpisodeTitle("Episode", 4)
        )
        assertTrue(text.hidden)
        assertEquals("Episode 4", text.title)
        assertEquals("", text.overview)
    }

    @Test
    fun `option on keeps the text of a watched episode`() {
        val text = episodeSpoilerText(
            title = "The Twist",
            overview = "  Everyone dies.  ",
            hideSpoilers = true,
            isWatched = true,
            hiddenTitle = "Episode 4"
        )
        assertFalse(text.hidden)
        assertEquals("The Twist", text.title)
        assertEquals("Everyone dies.", text.overview)
    }

    @Test
    fun `option off shows the text of an unwatched episode`() {
        val text = episodeSpoilerText(
            title = "The Twist",
            overview = null,
            hideSpoilers = false,
            isWatched = false,
            hiddenTitle = "Episode 4"
        )
        assertFalse(text.hidden)
        assertEquals("The Twist", text.title)
        assertEquals("", text.overview)
    }

    @Test
    fun `neutral title without a number is just the word`() {
        assertEquals("Episode", neutralEpisodeTitle("Episode", null))
    }
}
