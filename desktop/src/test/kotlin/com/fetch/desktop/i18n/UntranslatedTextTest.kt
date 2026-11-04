package com.fetch.desktop.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every English line the desktop shows goes through [tr], so it follows App Language (#270). This
 * catches a `Text("…")` or `mpv.showText("…")` written with a bare literal, interpolated or not;
 * text with a value in it is written as `tr("Delete %1$s?", name)` instead.
 */
class UntranslatedTextTest {

    /** Text that is the same in every language. */
    private val allowed = setOf(
        "YouTube",
        "https://example.com/manifest.json",
        "%+d dB"
    )

    private val bareLiteral = Regex("""\b(?:Text|showText)\("((?:[^"\\]|\\.)*)"""")

    /** `${…}`, `$name` and format specs (`%+d`, `%.1f`), which aren't words to translate. */
    private val notWords = Regex("""\$\{[^}]*\}|\$\w+|%[-+ #0,(]*\d*(?:\.\d+)?[a-zA-Z]""")

    @Test
    fun `screen text goes through tr`() {
        val root = File("src/main/kotlin")
        assertTrue("run from the desktop module", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().flatMap { (i, line) ->
                    bareLiteral.findAll(line)
                        .map { it.groupValues[1] }
                        .filter { text -> notWords.replace(text, "").any(Char::isLetter) && text !in allowed }
                        .map { text -> "${file.relativeTo(root)}:${i + 1}: \"$text\"" }
                        .toList()
                }
            }
            .toList()
        assertTrue("Wrap these in tr():\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
