package com.fetch.tv.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps screen edges on one token and key repeat on one modifier.
 *
 * - Screen edges use `FetchTheme.spacing.screen.horizontal` / `.vertical` (the 5% TV safe area),
 *   not a hard-coded dp and not a general-purpose step (`xxxl`, `huge`) standing in for it.
 *   Code written before the token still has some; `screen-padding-baseline.txt` records how many
 *   each file may keep. A file going over its count fails, and so does one going under, so the
 *   baseline can only shrink. Run this test and copy the counts it prints to update it.
 * - Lists throttle held keys with `dpadRepeatThrottle` (or `dpadVerticalFastScroll`, which uses
 *   it), so holding a direction behaves the same everywhere. A hand-rolled repeat gate fails.
 */
class ScreenPaddingGuardTest {

    private enum class Kind(val fix: String) {
        LITERAL("use FetchTheme.spacing.screen.horizontal / .vertical, or a FetchTheme.spacing step below 24 dp"),
        STAND_IN("use FetchTheme.spacing.screen.horizontal for a screen edge instead of xxxl / huge"),
    }

    private val sourceRoot = File("src/main/java")
    private val uiRoot = "com/fetch/tv/ui/"
    private val themeDir = "com/fetch/tv/ui/theme/"
    private val utilDir = "com/fetch/tv/ui/util/"

    /** An edge parameter set to a hard-coded dp of 24 or more: the size of a screen margin. */
    private val edgeLiteral = Regex("""\b(horizontal|vertical|start|end|top|bottom)\s*=\s*(\d+)\.dp\b""")

    /** A general step used where the screen token belongs. */
    private val edgeStandIn = Regex("""\b(horizontal|start|end)\s*=\s*FetchTheme\.spacing\.(xxxl|huge)\b""")

    /** A repeat gate written inline: `repeatCount > 0` followed shortly by a clock read. */
    private val handRolledRepeatGate =
        Regex("""repeatCount\s*>\s*0[\s\S]{0,300}?(uptimeMillis|currentTimeMillis)\(\)""")

    private fun uiFiles(): Sequence<Pair<String, String>> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(sourceRoot).invariantSeparatorsPath to it }
        .filter { (path, _) -> path.startsWith(uiRoot) && !path.startsWith(themeDir) }
        .map { (path, file) -> path to file.readText() }

    private fun counts(): Map<String, Int> = uiFiles()
        .flatMap { (path, text) ->
            listOfNotNull(
                edgeLiteral.findAll(text).count { it.groupValues[2].toInt() >= 24 }
                    .takeIf { it > 0 }?.let { "$path|${Kind.LITERAL.name}" to it },
                edgeStandIn.findAll(text).count()
                    .takeIf { it > 0 }?.let { "$path|${Kind.STAND_IN.name}" to it },
            )
        }
        .toMap()

    private fun baseline(): Map<String, Int> =
        javaClass.getResourceAsStream("/screen-padding-baseline.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (path, kind, count) = line.split('|')
                "$path|$kind" to count.toInt()
            }

    @Test
    fun `the screen token is the TV safe area`() {
        val screen = FetchSpacing.tokens.screen
        assertTrue("screen.horizontal must be at least 48 dp", screen.horizontal >= 48.dp)
        assertTrue("screen.vertical must be at least 27 dp", screen.vertical >= 27.dp)
    }

    @Test
    fun `screens add no new hard-coded edge padding`() {
        check(sourceRoot.isDirectory) { "Run from the app module; ${sourceRoot.absolutePath} is missing" }
        val actual = counts()
        val allowed = baseline()
        val problems = buildList {
            (actual.keys + allowed.keys).sorted().forEach { key ->
                val now = actual[key] ?: 0
                val max = allowed[key] ?: 0
                val (path, kind) = key.split('|')
                if (now > max) {
                    add("$path: ${now - max} new screen-edge padding value(s); ${Kind.valueOf(kind).fix}")
                } else if (now < max) {
                    add("$path: $kind is down to $now from $max; lower it in screen-padding-baseline.txt")
                }
            }
        }
        if (problems.isNotEmpty()) {
            val current = actual.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}|${it.value}" }
            fail(problems.joinToString("\n") + "\n\nCurrent counts:\n$current")
        }
    }

    @Test
    fun `lists use the shared key-repeat modifier`() {
        check(sourceRoot.isDirectory) { "Run from the app module; ${sourceRoot.absolutePath} is missing" }
        val offenders = uiFiles()
            .filter { (path, text) -> !path.startsWith(utilDir) && handRolledRepeatGate.containsMatchIn(text) }
            .map { it.first }
            .toList()
        if (offenders.isNotEmpty()) {
            fail(
                "Hand-rolled D-pad repeat gate; use Modifier.dpadRepeatThrottle() " +
                    "(moveFocusOnRepeat = false if the list handles Left/Right itself):\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}
