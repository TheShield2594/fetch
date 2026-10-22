package com.fetch.tv.ui.theme

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps hard-coded corner radii, animation durations and colours out of screens. Each belongs in
 * `ui/theme` (`FetchRadii`, `FetchMotion`, `FetchTheme.colors`) so the look changes in one place.
 *
 * Code written before the tokens still has some; `theme-literals-baseline.txt` records how many
 * each file may keep. A file going over its count fails, and so does one going under, so the
 * baseline is lowered as literals are removed and can only shrink. To update it after removing
 * literals, run this test and copy the counts it prints.
 */
class ThemeLiteralGuardTest {

    private enum class Kind(val pattern: Regex, val fix: String) {
        RADIUS(
            Regex("""RoundedCornerShape\([^)]*?\b\d+(\.\d+)?\.dp"""),
            "use FetchRadii.tokens or FetchTheme.radii"
        ),
        DURATION(
            Regex("""\btween(<[^>()]+>)?\(\s*(durationMillis\s*=\s*)?\d+"""),
            "use FetchMotion.tokens.durations"
        ),
        COLOR(
            Regex("""\bColor\(0x[0-9A-Fa-f]+\)"""),
            "use FetchTheme.colors"
        )
    }

    private val sourceRoot = File("src/main/java")
    private val themeDir = File(sourceRoot, "com/fetch/tv/ui/theme").path

    private fun counts(): Map<String, Int> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.startsWith(themeDir) }
        .flatMap { file ->
            val text = file.readText()
            val path = file.relativeTo(sourceRoot).invariantSeparatorsPath
            Kind.entries.mapNotNull { kind ->
                kind.pattern.findAll(text).count().takeIf { it > 0 }?.let { "$path|${kind.name}" to it }
            }
        }
        .toMap()

    private fun baseline(): Map<String, Int> =
        javaClass.getResourceAsStream("/theme-literals-baseline.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (path, kind, count) = line.split('|')
                "$path|$kind" to count.toInt()
            }

    @Test
    fun `screens add no new hard-coded radii, durations or colours`() {
        check(sourceRoot.isDirectory) { "Run from the app module; ${sourceRoot.absolutePath} is missing" }
        val actual = counts()
        val allowed = baseline()
        val problems = buildList {
            (actual.keys + allowed.keys).sorted().forEach { key ->
                val now = actual[key] ?: 0
                val max = allowed[key] ?: 0
                val (path, kind) = key.split('|')
                if (now > max) {
                    add("$path: ${now - max} new hard-coded ${kind.lowercase()} value(s); ${Kind.valueOf(kind).fix}")
                } else if (now < max) {
                    add("$path: $kind is down to $now from $max; lower it in theme-literals-baseline.txt")
                }
            }
        }
        if (problems.isNotEmpty()) {
            val current = actual.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}|${it.value}" }
            fail(problems.joinToString("\n") + "\n\nCurrent counts:\n$current")
        }
    }
}
