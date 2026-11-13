package com.fetch.tv.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Release builds strip only Log.d and Log.v (see proguard-rules.pro), so Log.i, Log.w and Log.e
 * reach logcat. Addon, stream, subtitle and poster URLs often embed a debrid or provider API key,
 * so those calls must not interpolate a URL as-is: pass it through [hostOnlyUrlForLog] (or
 * [urlForLog] / a host-only helper when the path is known to be safe).
 *
 * This scans each Log.i/w/e/wtf call (including calls split over several lines) for a string
 * template naming a `url`/`uri` variable that is not wrapped in a redaction helper.
 */
class RawUrlLogGuardTest {

    private val sourceRoot = File("src/main/java")

    // `$name` and `${expr}` string templates.
    private val template = Regex("""\$\{([^}]*)}|\$([A-Za-z_][A-Za-z0-9_]*)""")

    // A plain reference (`url`, `input.url`, `addon.manifestUrl`), optionally cut with `.take(n)`,
    // whose last segment names a URL. Anything passed through a helper (`.safeHost()`,
    // `.hostOnlyUrlForLog()`) or reduced to a property (`.host`) is not matched.
    private val rawUrlExpression =
        Regex("""\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_]*(?:url|Url|URL|uri|Uri)(?:\.take\(\d+\))?\s*""")

    // URLs that are public and carry no credential (YouTube trailer pages).
    private val allowedNames = setOf("youtubeUrl")

    @Test
    fun `release log calls do not print raw urls`() {
        assertTrue("run from the app module", sourceRoot.isDirectory)
        val offenders = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            rawUrlLogs(file.readText()).map { (line, template) -> "${file.path}:$line: $template" }
        }.toList()
        if (offenders.isNotEmpty()) {
            fail(
                "Log.i/w/e calls print a raw URL; wrap it in hostOnlyUrlForLog():\n" +
                    offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `the guard sees urls after parentheses in strings and nested comments`() {
        val d = "${'$'}"
        listOf(
            "Log.w(TAG, \"Failed ) $d" + "manifestUrl\")",
            "Log.w(TAG, /* outer /* inner */ ) */ \"Failed ) $d" + "manifestUrl\")",
            "Log.e(TAG, \"a ) \" + \"$d{list.size})\" + \" $d" + "{input.url}\") // ) trailing",
            "Log.i(TAG, \"\"\"raw ) \"quoted\" $d" + "streamUrl\"\"\")",
            "Log.w(TAG, \"char $d{')'} then $d" + "url\")",
        ).forEach { source ->
            assertEquals(source, 1, rawUrlLogs(source).size)
        }
        listOf(
            "Log.w(TAG, \"ok ) $d{url.hostOnlyUrlForLog()}\")",
            "Log.w(TAG, \"done\") // $d" + "url",
            "Log.w(TAG, \"done\") /* $d" + "url */",
        ).forEach { source ->
            assertEquals(source, 0, rawUrlLogs(source).size)
        }
    }

    /** (line, template) for each raw URL template inside a Log.i/w/e/wtf call in [text]. */
    private fun rawUrlLogs(text: String): List<Pair<Int, String>> =
        Regex("""\bLog\.(i|w|e|wtf)\(""").findAll(text).flatMap { call ->
            val line = text.substring(0, call.range.first).count { it == '\n' } + 1
            template.findAll(callArguments(text, call.range.last + 1)).mapNotNull { match ->
                val expression = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (rawUrlExpression.matches(expression) && expression.trim() !in allowedNames) line to match.value else null
            }
        }.toList()

    /**
     * Text from [start] up to the parenthesis that closes the call. Parentheses inside string and
     * char literals and comments are skipped; `${…}` templates inside strings are read as code.
     */
    private fun callArguments(text: String, start: Int): String {
        val end = skipCode(text, start)
        return text.substring(start, (end - 1).coerceIn(start, text.length))
    }

    /** Index just past the bracket that closes the code starting at [from]. */
    private fun skipCode(text: String, from: Int): Int {
        var i = from
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("/*", i) -> i = skipBlockComment(text, i)
                c == '"' -> i = skipString(text, i)
                c == '\'' -> i = skipCharLiteral(text, i)
                c == '(' || c == '{' || c == '[' -> { depth++; i++ }
                c == ')' || c == '}' || c == ']' -> {
                    if (depth == 0) return i + 1
                    depth--
                    i++
                }
                else -> i++
            }
        }
        return text.length
    }

    /** Index just past the string literal opening at [from] (plain or raw). */
    private fun skipString(text: String, from: Int): Int {
        val raw = text.startsWith("\"\"\"", from)
        var i = from + if (raw) 3 else 1
        while (i < text.length) {
            when {
                raw && text.startsWith("\"\"\"", i) -> {
                    // A raw string may end with extra quotes ("""a"""").
                    i += 3
                    while (i < text.length && text[i] == '"') i++
                    return i
                }
                !raw && text[i] == '"' -> return i + 1
                !raw && text[i] == '\\' -> i += 2
                text.startsWith("${'$'}{", i) -> i = skipCode(text, i + 2)
                else -> i++
            }
        }
        return text.length
    }

    /** Index just past the block comment opening at [from]; Kotlin block comments nest. */
    private fun skipBlockComment(text: String, from: Int): Int {
        var i = from + 2
        var depth = 1
        while (i < text.length && depth > 0) {
            when {
                text.startsWith("/*", i) -> { depth++; i += 2 }
                text.startsWith("*/", i) -> { depth--; i += 2 }
                else -> i++
            }
        }
        return i
    }

    private fun skipCharLiteral(text: String, from: Int): Int {
        var i = from + 1
        if (i < text.length && text[i] == '\\') i += 2 else i++
        // Unicode escapes ('\u0041') are longer; run to the closing quote.
        while (i < text.length && text[i] != '\'') i++
        return (i + 1).coerceAtMost(text.length)
    }
}
