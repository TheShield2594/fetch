package com.fetch.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How "Not interested" titles (#650) are stored: one key per title, so sync merges them one by one. */
class HiddenTitlesStoreLogicTest {

    private fun title(vararg ids: String, type: String = "movie", name: String = "Title", at: Long = 1L) =
        HiddenTitle(ids = ids.toList(), type = type, name = name, poster = "https://img/p.jpg", hiddenAtMs = at)

    @Test
    fun `a hidden title round-trips through its stored value`() {
        val original = title("tt1", "tmdb:1", type = "series", name = "Show", at = 42L)

        assertEquals(original, decodeHiddenTitle(encodeHiddenTitle(original)))
    }

    @Test
    fun `damaged values are skipped rather than breaking the list`() {
        assertNull(decodeHiddenTitle(null))
        assertNull(decodeHiddenTitle("not json"))
        assertNull(decodeHiddenTitle("""{"ids":[],"type":"movie","name":"x"}"""))
        assertNull(decodeHiddenTitle("""{"type":"movie","name":"x"}"""))
        // A blank name falls back to the id, and blank or repeated ids go.
        val decoded = decodeHiddenTitle("""{"ids":["tt9"," ","tt9"],"type":"movie","name":""}""")!!
        assertEquals(listOf("tt9"), decoded.ids)
        assertEquals("tt9", decoded.name)
    }

    @Test
    fun `each title gets its own key, by kind, so a movie and a show sharing an id stay apart`() {
        val prefs = mutablePreferencesOf()
        prefs.withHiddenTitle(title("tmdb:5", type = "movie"))
        prefs.withHiddenTitle(title("tmdb:5", type = "tv"))

        assertEquals(
            setOf("title:movie:tmdb:5", "title:series:tmdb:5"),
            prefs.asMap().keys.map { it.name }.toSet()
        )
        assertEquals(2, hiddenTitlesStateOf(prefs).titles.size)
    }

    @Test
    fun `hiding a title again under another id merges into one entry`() {
        val prefs = mutablePreferencesOf()
        prefs.withHiddenTitle(title("tmdb:7", at = 1L))
        prefs.withHiddenTitle(title("tt7", "tmdb:7", at = 2L))

        val titles = hiddenTitlesStateOf(prefs).titles
        assertEquals(1, titles.size)
        assertEquals(listOf("tt7", "tmdb:7"), titles.single().ids)
        assertEquals(setOf("title:movie:tt7"), prefs.asMap().keys.map { it.name }.toSet())
    }

    @Test
    fun `restoring removes every entry naming the title and leaves the others`() {
        val prefs = mutablePreferencesOf()
        prefs.withHiddenTitle(title("tt1"))
        prefs.withHiddenTitle(title("tt2"))

        prefs.withoutHiddenTitle(title("tt1", "tmdb:1"))

        assertEquals(listOf(listOf("tt2")), hiddenTitlesStateOf(prefs).titles.map { it.ids })
    }

    @Test
    fun `the oldest titles go past the cap`() {
        val prefs = mutablePreferencesOf()
        (1..HiddenTitlesStore.MAX_TITLES + 2).forEach { n -> prefs.withHiddenTitle(title("tt$n", at = n.toLong())) }

        val titles = hiddenTitlesStateOf(prefs).titles
        assertEquals(HiddenTitlesStore.MAX_TITLES, titles.size)
        assertFalse(titles.any { it.ids.single() == "tt1" || it.ids.single() == "tt2" })
        // Newest first.
        assertEquals("tt${HiddenTitlesStore.MAX_TITLES + 2}", titles.first().ids.single())
    }

    @Test
    fun `trimming after a sync keeps the newest titles up to the cap`() {
        val prefs = mutablePreferencesOf()
        // As a synced import writes them: every title either side had, past the cap.
        (1..HiddenTitlesStore.MAX_TITLES + 3).forEach { n ->
            val synced = title("tt$n", at = n.toLong())
            prefs[stringPreferencesKey(synced.storageKey)] = encodeHiddenTitle(synced)
        }
        prefs[booleanPreferencesKey(HiddenTitlesStore.HIDE_WATCHED_KEY_NAME)] = true

        prefs.trimHiddenTitles()

        val state = hiddenTitlesStateOf(prefs)
        assertEquals(HiddenTitlesStore.MAX_TITLES, state.titles.size)
        assertFalse(state.titles.any { it.ids.single() in setOf("tt1", "tt2", "tt3") })
        assertTrue(state.hideWatched)
    }

    @Test
    fun `the state reads hide watched and ignores keys it doesn't know`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("hide_watched") to true,
            stringPreferencesKey("from_a_newer_version") to "x",
            stringPreferencesKey("title:movie:broken") to "{"
        )
        val state = hiddenTitlesStateOf(prefs)
        assertTrue(state.hideWatched)
        assertTrue(state.titles.isEmpty())
        assertFalse(hiddenTitlesStateOf(mutablePreferencesOf()).hideWatched)
    }

    @Test
    fun `matching needs the same kind but takes either id`() {
        val hidden = title("tt3", "tmdb:3", type = "series")
        assertTrue(hidden.matches("tmdb:3", null, "tv"))
        assertTrue(hidden.matches("other", "tt3", "anime"))
        assertTrue(hidden.matches("tt3", null, null))
        assertFalse(hidden.matches("tmdb:3", null, "movie"))
        assertFalse(hidden.matches("tt4", null, "series"))
    }
}
