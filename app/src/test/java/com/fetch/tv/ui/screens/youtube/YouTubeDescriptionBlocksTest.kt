package com.fetch.tv.ui.screens.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeDescriptionBlocksTest {

    @Test
    fun `each line is its own block, blank lines dropped`() {
        assertEquals(listOf("One", "Two", "Three"), descriptionBlocks("One\nTwo\n\n\n  Three  \n"))
    }

    @Test
    fun `a long line with no breaks is cut after a sentence, so every piece fits the panel`() {
        val sentence = "This sentence is about forty characters. "
        val line = sentence.repeat(40).trim()
        val blocks = descriptionBlocks(line, maxChars = 100)
        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.length <= 100 })
        assertTrue(blocks.dropLast(1).all { it.endsWith(".") })
        assertEquals(line.replace(" ", ""), blocks.joinToString("").replace(" ", ""))
    }

    @Test
    fun `without sentences it cuts at a space, and without spaces at the limit`() {
        val words = List(60) { "word" }.joinToString(" ")
        assertTrue(descriptionBlocks(words, maxChars = 50).all { it.length <= 50 && !it.startsWith(" ") })
        val link = "x".repeat(130)
        assertEquals(listOf("x".repeat(50), "x".repeat(50), "x".repeat(30)), descriptionBlocks(link, maxChars = 50))
    }

    @Test
    fun `wide characters count double, so a CJK line is cut shorter`() {
        val line = "字".repeat(300)
        val blocks = descriptionBlocks(line, maxChars = 100)
        assertTrue(blocks.all { it.length <= 50 })
        assertEquals(line, blocks.joinToString(""))
    }
}
