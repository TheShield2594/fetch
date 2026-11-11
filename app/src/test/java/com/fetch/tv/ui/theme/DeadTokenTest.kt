package com.fetch.tv.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every public token has a use outside `ui/theme` (roadmap F3). A token nobody reads is a promise
 * the design system doesn't keep: the next person changes it, nothing on screen moves, and the
 * real value lives as a literal somewhere else.
 *
 * The test reads each token holder (`data class Fetch…` in the scale and component files below)
 * and looks for `.fieldName` in a source file outside the theme package. Matching by field name is
 * deliberately simple, so a generic name such as `md` can be satisfied by an unrelated `.md`; it
 * still catches the tokens that matter, the specific ones that quietly stop being used.
 *
 * When this fails, delete the token, or use it where a literal currently stands in for it.
 */
class DeadTokenTest {

    private val sourceRoot = File("src/main/java")
    private val themeDir = File(sourceRoot, "com/fetch/tv/ui/theme")

    /** The files that define scales and component tokens. Colours are covered by the contrast tests. */
    private val tokenFiles = listOf(
        "SizeTokens.kt",
        "SpacingTokens.kt",
        "ShapeTokens.kt",
        "StrokeElevationEffectTokens.kt",
        "MotionFocusTokens.kt",
        "ComponentTokens.kt",
        "Type.kt"
    )

    private val tokenHolder = Regex("""data class (Fetch\w+)\((.*?)\n\)""", RegexOption.DOT_MATCHES_ALL)
    private val field = Regex("""val (\w+):""")

    private fun outsideTheme(): String = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.startsWith(themeDir.path) }
        .joinToString("\n") { it.readText() }

    @Test
    fun `every public token is used outside the theme`() {
        check(themeDir.isDirectory) { "Run from the app module; ${themeDir.absolutePath} is missing" }
        val uses = outsideTheme()
        val dead = tokenFiles.flatMap { name ->
            val text = File(themeDir, name).readText()
            tokenHolder.findAll(text).flatMap { holder ->
                field.findAll(holder.groupValues[2]).map { it.groupValues[1] }
                    .filterNot { Regex("""\.$it\b""").containsMatchIn(uses) }
                    .map { "${holder.groupValues[1]}.$it ($name)" }
            }.toList()
        }
        if (dead.isNotEmpty()) {
            fail("Tokens with no use outside ui/theme; delete them or use them:\n" + dead.joinToString("\n"))
        }
    }

    @Test
    fun `retired token groups stay deleted`() {
        // These duplicated other tokens or were never read; screens wrote literals instead.
        val retired = listOf("FetchLayout", "FetchMedia", "FetchEffects", "FetchColors")
        val theme = themeDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        retired.forEach { name ->
            assertTrue("$name is back in ui/theme", !Regex("""\bobject $name\b""").containsMatchIn(theme))
        }
    }

    @Test
    fun `each layout value is defined once`() {
        // The safe area, the poster size and the sidebar width each used to live in two or more
        // token files with different numbers. One definition each now.
        val theme = tokenFiles.associateWith { File(themeDir, it).readText() }
        fun definitions(pattern: String) = theme.values.sumOf { Regex(pattern).findAll(it).count() }
        assertTrue("safe area defined more than once", definitions("""(?m)^\s+vertical = 27\.dp""") == 1)
        assertTrue("poster size defined more than once", definitions("""(?m)^\s+width = 126\.dp""") == 1)
        assertTrue("sidebar width defined more than once", definitions("""(?m)^\s+expandedWidth = \d+\.dp""") == 1)
    }
}
