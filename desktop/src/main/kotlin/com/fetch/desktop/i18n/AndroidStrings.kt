package com.fetch.desktop.i18n

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads an Android `res/values…/strings.xml`: each `<string name="…">` as Android would hand it
 * to the app, with its escapes undone (`\'`, `\"`, `\n`, `\t`, `\@`, `\?`, `\uXXXX`), unescaped
 * double quotes dropped and runs of whitespace outside them collapsed. XML entities (`&amp;`) are
 * the parser's job. Plurals and string arrays are skipped; `translatable="false"` strings are kept.
 */
object AndroidStrings {

    fun parse(input: InputStream): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val out = LinkedHashMap<String, String>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            if (element.tagName != "string") continue
            val name = element.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
            out[name] = unescape(element.textContent)
        }
        return out
    }

    /** Android's string-resource unescaping, applied to the text the XML parser returned. */
    fun unescape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var quoted = false
        var pendingSpace = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    if (pendingSpace) { sb.append(' '); pendingSpace = false }
                    val n = raw[i + 1]
                    i += 2
                    when (n) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = raw.substring(i, minOf(i + 4, raw.length))
                            val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                            if (code != null) { sb.append(code.toChar()); i += 4 } else sb.append('u')
                        }
                        else -> sb.append(n) // \' \" \@ \? \\ and anything else: the character itself
                    }
                    continue
                }
                c == '"' -> quoted = !quoted
                !quoted && (c == ' ' || c == '\n' || c == '\r' || c == '\t') -> pendingSpace = sb.isNotEmpty()
                else -> {
                    if (pendingSpace) { sb.append(' '); pendingSpace = false }
                    sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }
}
