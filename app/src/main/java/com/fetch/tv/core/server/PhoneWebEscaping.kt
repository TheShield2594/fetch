package com.fetch.tv.core.server

import kotlinx.serialization.json.JsonPrimitive

/**
 * Escaping for the phone pages served from the TV ([PhoneHubWebPage], [AddonWebPage],
 * [StreamBadgeWebPage], [CustomPosterWebPage]). Every translated string put into a page goes
 * through one of these, so a translation containing a quote, a newline, `\r` or `</script>`
 * can't break the page.
 */
internal object PhoneWebEscaping {

    /** Text for HTML content or a quoted attribute value. */
    fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** A complete, double-quoted JS string literal that is also safe inside a `<script>` element. */
    fun jsString(value: String): String = JsonPrimitive(value).toString().replace("</", "<\\/")

    /**
     * The inside of a JS string literal, for text spliced into a literal the page already opens
     * and closes. Safe in single- or double-quoted literals and inside `<script>`.
     */
    fun jsStringContent(value: String): String =
        jsString(value).let { it.substring(1, it.length - 1) }.replace("'", "\\'")
}
