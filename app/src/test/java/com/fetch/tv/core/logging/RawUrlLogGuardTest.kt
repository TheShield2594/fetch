package com.fetch.tv.core.logging

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
        val offenders = mutableListOf<String>()
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            Regex("""\bLog\.(i|w|e|wtf)\(""").findAll(text).forEach { call ->
                val args = callArguments(text, call.range.last + 1)
                template.findAll(args).forEach { match ->
                    val expression = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    if (rawUrlExpression.matches(expression) && expression.trim() !in allowedNames) {
                        val line = text.substring(0, call.range.first).count { it == '\n' } + 1
                        offenders += "${file.path}:$line: ${match.value}"
                    }
                }
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Log.i/w/e calls print a raw URL; wrap it in hostOnlyUrlForLog():\n" +
                    offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `the scanner reads past parentheses inside strings and comments`() {
        val source = """Log.w(TAG, "Failed ) (" /* ) */ + "${'$'}{x.size})" + ${'$'}manifestUrl) // ) trailing"""
        val args = callArguments(source, source.indexOf('(') + 1)
        assertTrue(args, args.endsWith("${'$'}manifestUrl"))
        assertTrue(rawUrlExpression.matches("manifestUrl"))

        val raw = "Log.e(TAG, \"\"\"a ) \"b\" \$url\"\"\")"
        assertTrue(callArguments(raw, raw.indexOf('(') + 1).contains("${'$'}url"))
    }

    /**
     * Text from [start] up to the parenthesis that closes the call. Parentheses inside string and
     * char literals and comments are skipped; `${'$'}{…}` templates inside strings are read as code.
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
                text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
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

    private fun skipCharLiteral(text: String, from: Int): Int {
        var i = from + 1
        if (i < text.length && text[i] == '\\') i += 2 else i++
        // Unicode escapes ('\u0041') are longer; run to the closing quote.
        while (i < text.length && text[i] != '\'') i++
        return (i + 1).coerceAtMost(text.length)
    }
}
