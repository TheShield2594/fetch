package com.fetch.tv.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchGridKeyboardTest {
    @Test
    fun `keys type, space, delete and clear`() {
        var query = ""
        "dune".forEach { query = applySearchKey(query, SearchKey.Character(it)) }
        assertEquals("dune", query)
        query = applySearchKey(query, SearchKey.Space)
        query = applySearchKey(query, SearchKey.Character('2'))
        assertEquals("dune 2", query)
        assertEquals("dune ", applySearchKey(query, SearchKey.Delete))
        assertEquals("", applySearchKey(query, SearchKey.Clear))
    }

    @Test
    fun `space is never first or doubled`() {
        assertEquals("", applySearchKey("", SearchKey.Space))
        assertEquals("a ", applySearchKey("a ", SearchKey.Space))
    }

    @Test
    fun `delete on an empty query stays empty`() {
        assertEquals("", applySearchKey("", SearchKey.Delete))
    }

    @Test
    fun `grid holds every letter and digit once`() {
        val keys = SEARCH_KEYBOARD_ROWS.joinToString("")
        assertEquals(36, keys.length)
        assertEquals(keys.length, keys.toSet().size)
        assertTrue(('a'..'z').all { it in keys } && ('0'..'9').all { it in keys })
        assertTrue(SEARCH_KEYBOARD_ROWS.all { it.length == 6 })
    }
}
