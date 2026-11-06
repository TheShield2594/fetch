package com.fetch.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubtitleFileCachePrioritizeTest {

    private fun sub(lang: String, n: Int) = SubtitleInput("https://s/$lang/$n.srt", "$lang $n", lang)

    @Test
    fun `preferred language comes first and others are dropped`() {
        val input = listOf(sub("fr", 1), sub("en", 1), sub("de", 1), sub("en", 2), sub("fr", 2))

        val result = SubtitleFileCache.prioritize(input, listOf("en", "fr"))

        assertEquals(listOf(sub("en", 1), sub("en", 2), sub("fr", 1), sub("fr", 2)), result)
    }

    @Test
    fun `keeps all subtitles when none match the preferred languages`() {
        val input = listOf(sub("de", 1), sub("it", 1))

        assertEquals(input, SubtitleFileCache.prioritize(input, listOf("en")))
    }

    @Test
    fun `none and blank preferences are ignored`() {
        val input = listOf(sub("de", 1), sub("it", 1))

        assertEquals(input, SubtitleFileCache.prioritize(input, listOf("none", " ")))
    }

    @Test
    fun `list is capped`() {
        val input = (1..60).map { sub("en", it) }

        val result = SubtitleFileCache.prioritize(input, listOf("en"), max = 20)

        assertEquals(input.take(20), result)
    }

    @Test
    fun `cache filenames are unique for same language and name`() {
        val a = SubtitleFileCache.cacheFilename(0, "en", "https://s/a.srt", "srt")
        val b = SubtitleFileCache.cacheFilename(1, "en", "https://s/b.srt", "srt")

        assertNotEquals(a, b)
        assertEquals(true, a.matches(Regex("[A-Za-z0-9._-]+")))
    }

    @Test
    fun `non latin language codes still produce distinct names`() {
        val a = SubtitleFileCache.cacheFilename(0, "日本語", "https://s/a.srt", "srt")
        val b = SubtitleFileCache.cacheFilename(1, "日本語", "https://s/a.srt", "srt")

        assertNotEquals(a, b)
    }
}
