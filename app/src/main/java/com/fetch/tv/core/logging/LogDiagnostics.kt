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
