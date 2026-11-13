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

    /** Text from [start] up to the parenthesis that closes the call, ignoring nesting. */
    private fun callArguments(text: String, start: Int): String {
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start))
    }
}
