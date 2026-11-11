package com.fetch.tv.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps hard-coded corner radii, animation durations, colours, focus scales, alphas, dp values and
 * non-TV Material components out of screens. Each belongs in `ui/theme` (`FetchRadii`,
 * `FetchMotion`, `FetchTheme.colors`, `FetchTheme.focus`, `FetchTheme.sizes`) or the component
 * layer, so the look changes in one place.
 *
 * Code written before the tokens still has some; `theme-literals-baseline.txt` records how many
 * each file may keep. A file going over its count fails, and so does one going under, so the
 * baseline is lowered as literals are removed and can only shrink. To update it after removing
 * literals, run this test and copy the counts it prints.
 */
class ThemeLiteralGuardTest {

    private enum class Kind(val pattern: Regex, val fix: String) {
        RADIUS(
            Regex("""RoundedCornerShape\([^)]*\)"""),
            "use FetchRadii.tokens or FetchTheme.radii"
        ),
        DURATION(
            Regex("""\btween(<[^>()]+>)?\(\s*(durationMillis\s*=\s*)?\d+"""),
            "use FetchMotion.tokens.durations"
        ),
        COLOR(
            Regex("""\bColor\(\s*0x[0-9A-Fa-f]+\s*\)"""),
            "use FetchTheme.colors"
        ),
        // Color.White / Color.Black skip the theme just as a hex literal does: they ignore
        // "Increase contrast" and custom themes. Existing uses are baselined and can only shrink.
        WHITE_BLACK(
            Regex("""\bColor\.(White|Black)\b"""),
            "use FetchTheme.colors (TextPrimary, Highlight, OnHighlight, Scrim) instead of Color.White/Black"
        ),
        // RoundedCornerShape(50) is a percent radius: a pill written a different way each time.
        PERCENT_RADIUS(
            Regex("""RoundedCornerShape\(\s*(percent\s*=\s*)?\d+\s*\)"""),
            "use FetchTheme.shapes or FetchTheme.radii.full"
        ),
        // Twelve different focus scales grew this way; components read FetchFocusSpec instead.
        FOCUS_SCALE(
            Regex("""\bfocusedScale\s*=\s*\d+(\.\d+)?f\b|targetValue\s*=\s*if\s*\(\s*\w*[Ff]ocused\w*\s*\)\s*\d+(\.\d+)?f"""),
            "use FetchFocusSpec.controlScale / cardScale or FetchTheme.focus"
        ),
        // androidx.compose.material and material3 are touch components: they ignore the TV theme
        // (the in-player panels rendered the platform font this way) and their focus model.
        NON_TV_MATERIAL(
            Regex("""(?m)^import androidx\.compose\.material3?\.(?!icons\.)"""),
            "use androidx.tv.material3 or the Fetch component layer"
        ),
        ALPHA(
            Regex("""\.copy\(\s*alpha\s*=\s*\d*\.?\d+f?\s*\)"""),
            "use a FetchTheme.colors role (Scrim, Overlay, Border) rather than an inline alpha"
        ),
        // Spacing is for gaps. A ring or a corner drawn with a spacing token changes when the
        // spacing scale does.
        SPACING_AS_STROKE(
            Regex("""(BorderStroke\(|\.border\()\s*(width\s*=\s*)?FetchTheme\.spacing\.|RoundedCornerShape\(\s*FetchTheme\.spacing\."""),
            "use FetchTheme.strokes for widths and FetchTheme.radii for corners"
        ),
        DP(
            Regex("""(?<![\w.])\d+(\.\d+)?\.dp\b"""),
            "use FetchTheme.spacing for gaps and FetchTheme.sizes for sizes"
        )
    }

    private val sourceRoot = File("src/main/java")
    private val DP_LITERAL = Regex("""\b\d+(\.\d+)?\.dp""")
    private val themeDir = File(sourceRoot, "com/fetch/tv/ui/theme").path

    private fun counts(): Map<String, Int> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.startsWith(themeDir) }
        .flatMap { file ->
            val text = file.readText()
            val path = file.relativeTo(sourceRoot).invariantSeparatorsPath
            Kind.entries.mapNotNull { kind ->
                val matches = kind.pattern.findAll(text)
                // A shape can set each corner separately, so every hard-coded corner counts.
                val count = if (kind == Kind.RADIUS) matches.sumOf { DP_LITERAL.findAll(it.value).count() } else matches.count()
                count.takeIf { it > 0 }?.let { "$path|${kind.name}" to it }
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
    fun `screens add no new hard-coded radii, durations, colours, scales, alphas, dp or Material imports`() {
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

    @Test
    fun `a raw focus scale or a non-TV Material import is caught`() {
        // The roadmap F4 acceptance: a pull request adding either fails the ratchet above.
        val caught = mapOf(
            Kind.FOCUS_SCALE to listOf(
                "scale = ButtonDefaults.scale(focusedScale = 1.1f)",
                "val s by animateFloatAsState(targetValue = if (isFocused) 1.05f else 1f)"
            ),
            Kind.NON_TV_MATERIAL to listOf(
                "import androidx.compose.material3.Text",
                "import androidx.compose.material.Button"
            ),
            Kind.PERCENT_RADIUS to listOf("RoundedCornerShape(50)"),
            Kind.ALPHA to listOf("Color.Black.copy(alpha = 0.6f)"),
            Kind.SPACING_AS_STROKE to listOf(
                "BorderStroke(FetchTheme.spacing.xxs, color)",
                "RoundedCornerShape(FetchTheme.spacing.sm)"
            ),
            Kind.DP to listOf("Modifier.padding(18.dp)")
        )
        caught.forEach { (kind, samples) ->
            samples.forEach { sample ->
                assertTrue("$kind should catch: $sample", kind.pattern.containsMatchIn(sample))
            }
        }
        val allowed = mapOf(
            Kind.FOCUS_SCALE to listOf("focusedScale = FetchFocusSpec.controlScale"),
            Kind.NON_TV_MATERIAL to listOf(
                "import androidx.compose.material.icons.Icons",
                "import androidx.tv.material3.Text"
            ),
            Kind.ALPHA to listOf("color.copy(alpha = contentAlpha)"),
            Kind.DP to listOf("FetchTheme.spacing.md", "size.dp")
        )
        allowed.forEach { (kind, samples) ->
            samples.forEach { sample ->
                assertTrue("$kind should allow: $sample", !kind.pattern.containsMatchIn(sample))
            }
        }
    }
}
