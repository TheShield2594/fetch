package com.fetch.tv.ui.components

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Ratchets screens onto the component layer (roadmap F2). Every raw tv-material `Button(` or
 * `Card(` call in a screen is a hand-styled control that drifts from the others; the layer's
 * [FetchButton], [FetchIconButton], [FetchChip] and [FetchMediaCard] keep them identical.
 *
 * `component-layer-baseline.txt` records how many raw calls each `ui/screens/<dir>` may keep. A
 * directory going over fails, and so does one going under, so the baseline is lowered as screens
 * migrate and can only shrink. After migrating, run this test and copy the counts it prints.
 */
class ComponentLayerGuardTest {

    private enum class Kind(val pattern: Regex, val fix: String) {
        BUTTON(Regex("""(?<![A-Za-z0-9_])Button\("""), "use FetchButton or FetchIconButton"),
        CARD(Regex("""(?<![A-Za-z0-9_])Card\("""), "use FetchMediaCard")
    }

    private val screensRoot = File("src/main/java/com/fetch/tv/ui/screens")
    private val declaration = Regex("""fun\s+$""")

    private fun counts(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        screensRoot.listFiles { file -> file.isDirectory }.orEmpty().forEach { dir ->
            val texts = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
            Kind.entries.forEach { kind ->
                val count = texts.sumOf { text ->
                    kind.pattern.findAll(text).count { match ->
                        // `fun Button(` declares a wrapper; only calls count.
                        !declaration.containsMatchIn(text.substring(maxOf(0, match.range.first - 10), match.range.first))
                    }
                }
                if (count > 0) result["${dir.name}|${kind.name}"] = count
            }
        }
        return result
    }

    private fun baseline(): Map<String, Int> =
        javaClass.getResourceAsStream("/component-layer-baseline.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (dir, kind, count) = line.split('|')
                "$dir|$kind" to count.toInt()
            }

    @Test
    fun `screens add no raw Button or Card calls`() {
        check(screensRoot.isDirectory) { "Run from the app module; ${screensRoot.absolutePath} is missing" }
        val actual = counts()
        val allowed = baseline()
        val problems = buildList {
            (actual.keys + allowed.keys).sorted().forEach { key ->
                val now = actual[key] ?: 0
                val max = allowed[key] ?: 0
                val (dir, kind) = key.split('|')
                if (now > max) {
                    add("screens/$dir: ${now - max} new raw ${kind.lowercase()} call(s); ${Kind.valueOf(kind).fix}")
                } else if (now < max) {
                    add("screens/$dir: $kind is down to $now from $max; lower it in component-layer-baseline.txt")
                }
            }
        }
        if (problems.isNotEmpty()) {
            val current = actual.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}|${it.value}" }
            fail(problems.joinToString("\n") + "\n\nCurrent counts:\n$current")
        }
    }

    @Test
    fun `the first migrated screens use no raw buttons`() {
        val actual = counts()
        val leftovers = MIGRATED_BUTTON_FREE.filter { (actual["$it|BUTTON"] ?: 0) > 0 }
        if (leftovers.isNotEmpty()) fail("Raw Button( calls are back in: ${leftovers.joinToString()}")
    }

    private companion object {
        /** Screens whose buttons are all on the component layer (the F2 acceptance list). */
        val MIGRATED_BUTTON_FREE = listOf("home", "library", "search")
    }
}
