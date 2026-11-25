package com.fetch.tv.ui.components

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Holding OK or pressing Menu opens a card's options, but TalkBack only reaches them through the
 * accessibility long click. A card that answers "double-tap and hold" with `onLongClick { false }`
 * shuts TalkBack users out of library, watched, lists and the Continue Watching and episode menus
 * (#647). The checks read the source because the app module has no Compose UI tests.
 */
class CardLongPressAccessibilityGuardTest {

    private val uiRoot = File("src/main/java/com/fetch/tv/ui")

    private fun source(path: String): String = File(uiRoot, path).readText()

    @Test
    fun `no card refuses the accessibility long click`() {
        check(uiRoot.isDirectory) { "Run from the app module; ${uiRoot.absolutePath} is missing" }
        val offenders = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""onLongClick\s*\{\s*false\s*}""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(uiRoot).path }
            .toList()
        if (offenders.isNotEmpty()) {
            fail("onLongClick { false } hides the options menu from TalkBack in: $offenders")
        }
    }

    @Test
    fun `LightweightCard exposes its long press to accessibility`() {
        val text = source("components/LightweightCard.kt")
        assertTrue(text.contains("onLongPress: (() -> Unit)? = null"))
        assertTrue(
            "Both semantics branches wire the long click",
            Regex("""onLongClick\(label = longClickLabel\)""").findAll(text).count() == 2
        )
    }

    @Test
    fun `cards with an options menu pass it to the card`() {
        listOf(
            "screens/home/ModernHomeRows.kt",
            "components/ContinueWatchingSection.kt",
            "screens/detail/EpisodesSection.kt"
        ).forEach { path ->
            val text = source(path)
            val call = text.indexOf("LightweightCard(")
            check(call >= 0) { "$path no longer calls LightweightCard" }
            val head = text.substring(call, minOf(text.length, call + 600))
            assertTrue("$path doesn't pass onLongPress to LightweightCard", head.contains("onLongPress = onLongPress"))
        }
        listOf("components/ContentCard.kt", "components/GridContentCard.kt").forEach { path ->
            assertTrue(
                "$path doesn't expose onLongPress as the accessibility long click",
                source(path).contains("onLongClick(label = optionsLabel)")
            )
        }
    }
}
