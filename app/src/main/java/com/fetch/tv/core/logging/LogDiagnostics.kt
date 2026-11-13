package com.fetch.tv.core.logging

import java.security.MessageDigest

/**
 * Redacts a secret-ish value (login codes, nonces, device codes, user ids) for logcat.
 *
 * Release builds still emit Log.w/e/i, and logcat is readable by anything with adb access, so the
 * raw value never leaves this function. What is logged is the length plus a short SHA-256 prefix:
 * enough to correlate the same value across log lines without being able to replay it.
 */
fun String?.rawForLog(): String {
    val value = this ?: return "(null)"
    if (value.isEmpty()) return "(empty)"
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val prefix = digest.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "len=${value.length}#$prefix"
}

/**
 * Drops the query string and fragment from a URL before logging it. TV-login and device-login
 * URLs carry the login code / nonce as query parameters, so only scheme, host and path are kept.
 */
fun String?.urlForLog(): String {
    val value = this ?: return "(null)"
    val cut = value.indexOfFirst { it == '?' || it == '#' }
    return if (cut < 0) value else "${value.substring(0, cut)}?…"
}

/**
 * Reduces a URL that may carry a credential anywhere (addon, stream, subtitle and poster URLs often
 * embed a debrid or provider API key in the path or the query) to its scheme and host plus a
 * [rawForLog] fingerprint of the whole URL, e.g. `https://addon.example/…[len=87#1a2b3c4d]`. The
 * fingerprint still lets the same URL be matched across log lines.
 */
fun String?.hostOnlyUrlForLog(): String {
    val value = this ?: return "(null)"
    if (value.isEmpty()) return "(empty)"
    // Only a scheme at the very start counts: indexOf("://") alone would find a URL nested in the
    // query of a scheme-less value and keep everything before it, path secrets included.
    val scheme = LEADING_SCHEME.find(value)
    val origin = if (scheme != null) {
        val authorityStart = scheme.range.last + 1
        // OkHttp also ends the authority at a backslash.
        val authorityEnd = value.indexOfAny(AUTHORITY_END, authorityStart).let { if (it < 0) value.length else it }
        // Drop any user-info (user:password@) from the authority.
        val host = value.substring(authorityStart, authorityEnd).substringAfterLast('@')
        "${scheme.groupValues[1]}://$host"
    } else {
        "(no host)"
    }
    return "$origin/…[${value.rawForLog()}]"
}

/**
 * Makes free text such as an exception message safe to log: each of [urls] that appears in it
 * (even without a scheme, as Retrofit's "Malformed URL" message shows it) and any other
 * `scheme://…` token is replaced by its [hostOnlyUrlForLog] form.
 */
fun String?.redactUrlsForLog(vararg urls: String?): String? {
    val text = this ?: return null
    // Known URLs are swapped for NUL-delimited placeholders first, the remaining scheme://
    // tokens are redacted, then the placeholders are filled in. Nothing in the input is trusted
    // as "already redacted", so text that merely looks like hostOnlyUrlForLog output is reduced
    // like any other URL.
    // Each known URL takes the rest of its token with it, so a suffix such as "?token=…" after
    // it is redacted too.
    val tokens = mutableListOf<String>()
    var masked = text.replace(PLACEHOLDER_CHAR.toString(), "")
    urls.filterNotNull().filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }.forEach { url ->
        masked = Regex(Regex.escape(url) + TOKEN_TAIL).replace(masked) { match ->
            tokens += match.value
            "$PLACEHOLDER_CHAR${tokens.lastIndex}$PLACEHOLDER_CHAR"
        }
    }
    masked = URL_IN_TEXT.replace(masked) { it.value.hostOnlyUrlForLog() }
    return PLACEHOLDER.replace(masked) { tokens[it.groupValues[1].toInt()].hostOnlyUrlForLog() }
}

private const val PLACEHOLDER_CHAR = '\u0000'
private val PLACEHOLDER = Regex("\u0000(\\d+)\u0000")
private val LEADING_SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://""")
private val AUTHORITY_END = charArrayOf('/', '\\', '?', '#')
private const val TOKEN_TAIL = """[^\s"'<>\u0000]*"""
private val URL_IN_TEXT = Regex("""[A-Za-z][A-Za-z0-9+.-]*://""" + TOKEN_TAIL.dropLast(1) + "+")

fun String?.bodySnippetForLog(maxLength: Int = Int.MAX_VALUE): String {
    val value = this ?: return "(null)"
    if (value.isBlank()) return value
    return if (value.length <= maxLength) value else "${value.take(maxLength)}..."
}

fun Throwable.diagnosticSummary(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && parts.size < 6) {
        val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
        val message = current.message.bodySnippetForLog()
        parts.add("$name: $message")
        current = current.cause
    }
    return parts.joinToString(" <- ")
}
