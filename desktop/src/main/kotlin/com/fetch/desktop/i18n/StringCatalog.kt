package com.fetch.desktop.i18n

import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates the desktop's English UI text with the TV app's string resources. The desktop has no
 * keys of its own: an English string is looked up among the TV's English values (`values/`), and
 * the first key with that value that the language translates gives the text. A string the TV
 * doesn't have, or hasn't translated, stays English.
 *
 * Matching is exact first, then loose (ignoring case, curly apostrophes, `...` against `…` and a
 * closing full stop or colon), since the desktop often says "Increase contrast" where the TV says
 * "Increase Contrast".
 *
 * [open] returns a folder's `strings.xml` (`"values"`, `"values-de"`, …) or null. Each source is
 * read once, when first needed; several sources (the TV's, then the desktop's own additions) merge
 * with later ones winning.
 */
class StringCatalog(private val sources: List<(folder: String) -> InputStream?>) {

    constructor(open: (folder: String) -> InputStream?) : this(listOf(open))

    private val folders = ConcurrentHashMap<String, Map<String, String>>()
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    private fun folder(name: String): Map<String, String> = folders.getOrPut(name) {
        val merged = LinkedHashMap<String, String>()
        for (open in sources) {
            runCatching { open(name)?.use { merged.putAll(AndroidStrings.parse(it)) } }
        }
        merged
    }

    private val english: Map<String, String> get() = folder("values")

    /** English value → its keys, in file order. */
    private val exactIndex: Map<String, List<String>> by lazy {
        english.entries.groupBy({ it.value }, { it.key })
    }

    /** Loose form ([loose]) of each English value → its keys. */
    private val looseIndex: Map<String, List<String>> by lazy {
        english.entries.groupBy({ loose(it.value) }, { it.key })
    }

    /** The TV keys whose English is [text], exact matches first. */
    fun keysFor(text: String): List<String> {
        val exact = exactIndex[text].orEmpty()
        val near = looseIndex[loose(text)].orEmpty()
        return if (near.isEmpty()) exact else (exact + near).distinct()
    }

    /** The English value for a TV key, or null. */
    fun english(key: String): String? = english[key]

    /** [text] in [tag] (a tag from [Languages.resolve]), or [text] itself. */
    fun translate(text: String, tag: String): String {
        if (text.isEmpty() || tag == Languages.ENGLISH) return text
        val perLanguage = cache.getOrPut(tag) { ConcurrentHashMap() }
        perLanguage[text]?.let { return it }
        val folder = Languages.tagToQualifier(tag)?.let(::folder)
        // A key left in English in this language gives way to one that's really translated.
        val result = folder?.let { values ->
            val found = keysFor(text).mapNotNull { key -> values[key]?.let { key to it } }
            (found.firstOrNull { (key, value) -> value != english[key] } ?: found.firstOrNull())?.second
        }?.let { translated ->
            // "UP NEXT" stays capitals in every language, whatever case the TV's string has.
            if (text.any(Char::isLetter) && text == text.uppercase() && text != text.lowercase()) {
                translated.uppercase(Locale.forLanguageTag(tag))
            } else translated
        } ?: text
        perLanguage[text] = result
        return result
    }

    /**
     * [template] (Android style: `%1$s`, `%s`, `%d`) in [tag], formatted with [args]. A
     * translation the arguments don't fit falls back to the English template.
     */
    fun format(template: String, tag: String, vararg args: Any?): String {
        val translated = translate(template, tag)
        val locale = Locale.forLanguageTag(tag)
        return runCatching { String.format(locale, anyType(translated), *args) }
            .recoverCatching { String.format(Locale.ROOT, anyType(template), *args) }
            .getOrDefault(template)
    }

    /** Reads [tag]'s strings now, so the first screen doesn't wait for them. */
    fun preload(tag: String) {
        exactIndex.size
        looseIndex.size
        Languages.tagToQualifier(tag)?.let(::folder)
    }

    companion object {
        private val placeholder = Regex("(?<!%)%(?:(\\d+)\\$)?[sd]")

        /**
         * `%d` as `%s`, so a desktop template written with `%1$s` takes the TV's `%1$d` and the
         * other way round: an integer prints the same either way.
         */
        internal fun anyType(template: String): String =
            placeholder.replace(template) { m ->
                val index = m.groupValues[1]
                if (index.isEmpty()) "%s" else "%$index\$s"
            }

        fun loose(text: String): String {
            var n = 0
            var s = text.trim()
                .replace("...", "…")
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace(Regex("\\s+"), " ")
                .lowercase(Locale.ROOT)
            // Placeholders by position: %s, %1$s and %1$d read the same.
            s = placeholder.replace(s) { m ->
                n++
                "%" + m.groupValues[1].ifEmpty { n.toString() } + "\$s"
            }
            while (s.endsWith('.') || s.endsWith(':')) s = s.dropLast(1)
            return s.trimEnd()
        }

        /** The build's copies of the TV strings (`i18n/tv/…`) and the desktop's own (`i18n/desktop/…`). */
        fun fromClasspath(loader: ClassLoader = StringCatalog::class.java.classLoader): StringCatalog =
            StringCatalog(
                listOf(
                    { folder -> loader.getResourceAsStream("i18n/tv/$folder/strings.xml") },
                    { folder -> loader.getResourceAsStream("i18n/desktop/$folder/strings.xml") }
                )
            )
    }
}
