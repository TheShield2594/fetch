package com.fetch.tv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps text at or above 12sp, which is 24px on a 1080p TV (density 2): the usual minimum for
 * body text and labels read from across a room.
 */
class TypeScaleFloorTest {

    private val minSp = 12f

    @Test
    fun `every type style is at least 12sp`() {
        val typography = buildFetchTypography(FontFamily.Default)
        val styles: Map<String, TextStyle> = mapOf(
            "displayLarge" to typography.displayLarge,
            "displayMedium" to typography.displayMedium,
            "displaySmall" to typography.displaySmall,
            "headlineLarge" to typography.headlineLarge,
            "headlineMedium" to typography.headlineMedium,
            "headlineSmall" to typography.headlineSmall,
            "titleLarge" to typography.titleLarge,
            "titleMedium" to typography.titleMedium,
            "titleSmall" to typography.titleSmall,
            "bodyLarge" to typography.bodyLarge,
            "bodyMedium" to typography.bodyMedium,
            "bodySmall" to typography.bodySmall,
            "labelLarge" to typography.labelLarge,
            "labelMedium" to typography.labelMedium,
            "labelSmall" to typography.labelSmall
        ) + buildFetchTextStyles(typography).let { tokens ->
            mapOf(
                "metadata" to tokens.metadata,
                "spec" to tokens.spec,
                "badge" to tokens.badge
            )
        }
        val small = styles.filterValues { it.fontSize.value < minSp }
        assertTrue("Below ${minSp}sp: ${small.keys}", small.isEmpty())
    }

    @Test
    fun `styles that carry reading text are at least 14sp`() {
        // 12sp is fine for a badge glanced at; metadata, spec lines and secondary sentences are
        // read from the couch, so they get 28px at 1080p.
        val readingMinSp = 14f
        val typography = buildFetchTypography(FontFamily.Default)
        val tokens = buildFetchTextStyles(typography)
        val reading: Map<String, TextStyle> = mapOf(
            "bodySmall" to typography.bodySmall,
            "bodyMedium" to typography.bodyMedium,
            "labelMedium" to typography.labelMedium,
            "labelLarge" to typography.labelLarge,
            "metadata" to tokens.metadata,
            "spec" to tokens.spec
        )
        val small = reading.filterValues { it.fontSize.value < readingMinSp }
        assertTrue("Reading text below ${readingMinSp}sp: ${small.keys}", small.isEmpty())
    }

    @Test
    fun `screens set no font size below 12sp`() {
        val sourceRoot = File("src/main/java")
        check(sourceRoot.isDirectory) { "Run from the app module; ${sourceRoot.absolutePath} is missing" }
        val literal = Regex("""\b(?:fontSize|minFontSize|textSize)\s*(?::\s*TextUnit\s*)?=\s*(\d+(?:\.\d+)?)[fF]?\.sp""")
        val problems = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().flatMapIndexed { index, line ->
                    literal.findAll(line)
                        .map { it.groupValues[1].toFloat() }
                        .filter { it < minSp }
                        .map { "${file.relativeTo(sourceRoot).invariantSeparatorsPath}:${index + 1}: ${it}sp" }
                        .toList()
                }
            }
            .toList()
        if (problems.isNotEmpty()) fail("Text below ${minSp}sp:\n" + problems.joinToString("\n"))
    }
}
