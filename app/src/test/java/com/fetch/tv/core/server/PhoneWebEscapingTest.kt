package com.fetch.tv.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The escaping the phone pages rely on to keep TV-side text and phone input inert. */
class PhoneWebEscapingTest {

    @Test
    fun `escapeHtml escapes every markup character`() {
        listOf(
            "" to "",
            "plain text" to "plain text",
            "<script>alert(1)</script>" to "&lt;script&gt;alert(1)&lt;/script&gt;",
            "a & b" to "a &amp; b",
            // Already-escaped text is escaped again, never passed through as markup.
            "&amp;" to "&amp;amp;",
            "\"quoted\"" to "&quot;quoted&quot;",
            "it's" to "it&#39;s",
            "\" onmouseover=\"x" to "&quot; onmouseover=&quot;x",
            "' onfocus='x" to "&#39; onfocus=&#39;x",
            "café ✓ 日本" to "café ✓ 日本",
        ).forEach { (input, expected) ->
            assertEquals(input, expected, PhoneWebEscaping.escapeHtml(input))
        }
    }

    @Test
    fun `jsString makes a quoted literal that cannot leave the string or the script`() {
        listOf(
            "" to "\"\"",
            "plain" to "\"plain\"",
            "say \"hi\"" to "\"say \\\"hi\\\"\"",
            "back\\slash" to "\"back\\\\slash\"",
            "line\nbreak" to "\"line\\nbreak\"",
            "tab\there" to "\"tab\\there\"",
            "carriage\rreturn" to "\"carriage\\rreturn\"",
            "</script><script>alert(1)</script>" to "\"\\u003c/script>\\u003cscript>alert(1)\\u003c/script>\"",
            "</SCRIPT>" to "\"\\u003c/SCRIPT>\"",
            "<!--<script>" to "\"\\u003c!--\\u003cscript>\"",
            "it's" to "\"it's\"",
            "café ✓" to "\"café ✓\"",
        ).forEach { (input, expected) ->
            assertEquals(input, expected, PhoneWebEscaping.jsString(input))
        }
    }

    @Test
    fun `jsString never emits a closing tag sequence or a raw line break`() {
        listOf("</", "</script>", "a</b", "x\n</script>\ny", "<!--<script>", "\u0000\u001f").forEach { input ->
            val literal = PhoneWebEscaping.jsString(input)
            assertFalse(input, literal.contains('<'))
            assertFalse(input, literal.contains('\n'))
            assertFalse(input, literal.contains('\r'))
            assertTrue(input, literal.startsWith("\"") && literal.endsWith("\""))
        }
    }

    @Test
    fun `jsStringContent is safe spliced into a single- or double-quoted literal`() {
        listOf(
            "plain" to "plain",
            "it's" to "it\\'s",
            "say \"hi\"" to "say \\\"hi\\\"",
            "line\nbreak\rreturn" to "line\\nbreak\\rreturn",
            "back\\slash" to "back\\\\slash",
            "</script>" to "\\u003c/script>",
        ).forEach { (input, expected) ->
            assertEquals(input, expected, PhoneWebEscaping.jsStringContent(input))
        }
    }

    @Test
    fun `custom poster page puts its strings into script as escaped literals`() {
        val page = CustomPosterWebPage.html(null)
        val script = page.substringAfter("<script>").substringBefore("</script>")
        assertTrue(script.contains("msg(\"Saved!\",true)"))
        assertTrue(script.contains("textContent=\"No pattern set\""))
        // The placeholder attribute escapes its ampersands.
        assertTrue(page.contains("placeholder=\"https://example.com/poster?id={id}&amp;id_type={id_type}&amp;type={type}\""))
    }

    @Test
    fun `back link escapes its label`() {
        val link = PhoneHubWebPage.backLink("<img src=x onerror=alert(1)> & \"back\"")
        assertTrue(link.contains("&lt;img src=x onerror=alert(1)&gt; &amp; &quot;back&quot;"))
        assertFalse(link.contains("<img"))
        assertTrue(link.startsWith("<a href=\"/\""))
    }

    @Test
    fun `section pages get their api and logo links under the prefix`() {
        val page = """
            <html><body class="x" data-a="1"><img src="/logo.png">
            <script>fetch('/api/pattern');fetch("/api/clear");</script></body></html>
        """.trimIndent()

        val rewritten = PhoneHubServer.rewriteSectionPage(page, "posters", "All settings")

        assertTrue(rewritten.contains("fetch('/posters/api/pattern')"))
        assertTrue(rewritten.contains("fetch(\"/posters/api/clear\")"))
        assertTrue(rewritten.contains("src=\"/posters/logo.png\""))
        assertFalse(rewritten.contains("'/api/"))
        assertFalse(rewritten.contains("\"/api/"))
        // The back link goes straight after the body tag, which keeps its attributes.
        assertTrue(rewritten.contains("<body class=\"x\" data-a=\"1\"><a href=\"/\""))
        assertEquals(1, Regex("&larr;").findAll(rewritten).count())
    }

    @Test
    fun `the back link is added once, to the first body tag only`() {
        val rewritten = PhoneHubServer.rewriteSectionPage("<body><p>a</p><body>", "badges", "Back")
        assertEquals(1, Regex("&larr; Back").findAll(rewritten).count())
        assertTrue(rewritten.startsWith("<body><a href=\"/\""))
    }

    @Test
    fun `a translated back label is inserted literally, whatever it contains`() {
        // `$` and `\` are special in a regex replacement; a label must never be read as one.
        listOf(
            "Back \$1" to "Back \$1",
            "Zurück \\ Start" to "Zurück \\ Start",
            "\$0 \\\$ \\1" to "\$0 \\\$ \\1",
            "<b>Back</b>" to "&lt;b&gt;Back&lt;/b&gt;",
        ).forEach { (label, shown) ->
            val rewritten = PhoneHubServer.rewriteSectionPage("<body class=\"c\"><main></main></body>", "posters", label)
            assertTrue(label, rewritten.contains("&larr; $shown</a><main>"))
            assertTrue(label, rewritten.startsWith("<body class=\"c\"><a href=\"/\""))
        }
    }

    @Test
    fun `custom poster pattern json round trips any character`() {
        listOf(
            "https://img.example/{imdb_id}.jpg",
            "https://img.example/poster?id={id}&type={type}",
            "C:\\posters\\{id}.jpg",
            "ends with a backslash \\",
            "has \"quotes\" and \\\"escaped quotes\\\"",
            "line\nbreak and tab\t",
            "</script>",
            "",
        ).forEach { pattern ->
            val json = CustomPosterConfigServer.patternJson(pattern)
            assertEquals(pattern, pattern, CustomPosterConfigServer.extractJsonString(json, "pattern"))
        }
    }

    @Test
    fun `custom poster pattern reads the posted field and nothing else`() {
        listOf(
            """{"pattern":"https://a/{id}.jpg"}""" to "https://a/{id}.jpg",
            """{ "pattern" : "x\"y" }""" to "x\"y",
            """{"pattern":"a\\b"}""" to "a\\b",
            """{"pattern":"a\u0026b"}""" to "a&b",
            """{"pattern":"https:\/\/a\/b"}""" to "https://a/b",
            // A key whose value merely mentions "pattern" is not the field.
            """{"note":"\"pattern\": \"evil\"","pattern":"good"}""" to "good",
            """{"pattern":42}""" to "42",
        ).forEach { (body, expected) ->
            assertEquals(body, expected, CustomPosterConfigServer.extractJsonString(body, "pattern"))
        }
        listOf(
            "",
            "not json",
            "[\"pattern\"]",
            """{"other":"x"}""",
            """{"pattern":null}""",
            """{"pattern":{"nested":"x"}}""",
            """{"pattern":"unterminated}""",
        ).forEach { body ->
            assertNull(body, CustomPosterConfigServer.extractJsonString(body, "pattern"))
        }
    }
}
