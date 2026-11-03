package com.fetch.desktop.i18n

import java.util.Locale

/**
 * The languages the TV app ships (its `res/values-…` folders) and how Android's resource
 * qualifiers map to BCP-47 tags, and a requested locale to the closest one Fetch has.
 */
object Languages {

    /** The default `values` folder. */
    const val ENGLISH = "en"

    /**
     * The TV's language picker (ThemeSettingsScreen), in its tags: what `locale_tag` holds there.
     * Indonesian is `in` and Hebrew `he`, as on TV.
     */
    val pickerTags: List<String> = listOf(
        "en", "ru", "ar", "bg", "bs", "da", "de", "el", "es", "es-419", "hu", "fr", "in", "it",
        "no", "pl", "pt-PT", "pt-BR", "tr", "uk", "cs", "sk", "sl", "sq", "sr-Latn", "sv", "ta", "ro", "ja",
        "nl", "vi", "hi", "lt", "he", "zh-CN", "zh-TW", "bn", "hr"
    )

    /** Every translated `values-…` folder the build copies from the TV app (see build.gradle.kts). */
    val qualifiers: List<String> = listOf(
        "values-ar", "values-b+es+419", "values-b+sr+Latn", "values-bg", "values-bn", "values-bs",
        "values-cs", "values-da", "values-de", "values-el", "values-es", "values-fr", "values-hi",
        "values-hr", "values-hu", "values-in", "values-it", "values-iw", "values-ja", "values-lt",
        "values-nl", "values-no", "values-pl", "values-pt-rBR", "values-pt-rPT", "values-ro",
        "values-ru", "values-sk", "values-sl", "values-sq", "values-sv", "values-ta", "values-tr",
        "values-uk", "values-vi", "values-zh-rCN", "values-zh-rTW"
    )

    /** Old ISO 639 codes Android (and older JDKs) still use, to the current ones. */
    private val legacyLanguages = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /**
     * A resource folder name to its BCP-47 tag: `values` → `en`, `values-de` → `de`,
     * `values-pt-rBR` → `pt-BR`, `values-b+es+419` → `es-419`, `values-b+sr+Latn` → `sr-Latn`,
     * `values-iw` → `he`, `values-in` → `id`. Null for a folder that isn't a language
     * (`values-v31`, `values-night`).
     */
    fun qualifierToTag(folder: String): String? {
        if (folder == "values") return ENGLISH
        if (!folder.startsWith("values-")) return null
        val q = folder.removePrefix("values-")
        val parts: List<String> = if (q.startsWith("b+")) {
            q.removePrefix("b+").split('+')
        } else {
            val bits = q.split('-')
            val lang = bits.first()
            if (lang.length !in 2..3 || !lang.all { it.isLetter() }) return null
            listOf(lang) + bits.drop(1).filter { it.length == 3 && it[0] == 'r' }.map { it.substring(1) }
        }
        val lang = parts.firstOrNull()?.lowercase()?.takeIf { it.length in 2..3 && it.all(Char::isLetter) } ?: return null
        val rest = parts.drop(1).map { p ->
            when {
                p.length == 4 && p.all(Char::isLetter) -> p.lowercase().replaceFirstChar { it.uppercase() }
                else -> p.uppercase()
            }
        }
        return (listOf(legacyLanguages[lang] ?: lang) + rest).joinToString("-")
    }

    /** A tag to the folder holding its strings (the reverse of [qualifierToTag]); null for English. */
    fun tagToQualifier(tag: String): String? = byTag[normalize(tag)]

    /** Each translated language's tag (normalized) to its folder. */
    private val byTag: Map<String, String> by lazy {
        qualifiers.mapNotNull { q -> qualifierToTag(q)?.let { it to q } }.toMap()
    }

    /** Every language there are strings for, English first. */
    val availableTags: List<String> get() = listOf(ENGLISH) + byTag.keys

    /** `pt_BR`, `in`, `iw-IL` and so on in canonical form: `pt-BR`, `id`, `he-IL`. */
    fun normalize(tag: String): String {
        val locale = Locale.forLanguageTag(tag.trim().replace('_', '-'))
        val lang = locale.language.lowercase().let { legacyLanguages[it] ?: it }
        if (lang.isEmpty()) return ENGLISH
        return listOf(lang, locale.script, locale.country).filter { it.isNotEmpty() }.joinToString("-")
    }

    /**
     * The language Fetch shows for [requested] (a stored `locale_tag` or the system locale): the
     * exact language, else the closest variant (Latin American Spanish for es-MX, Traditional
     * Chinese for zh-Hant and zh-HK, Brazilian Portuguese for pt), else English.
     */
    fun resolve(requested: String?): String {
        if (requested.isNullOrBlank()) return ENGLISH
        val tag = normalize(requested)
        val locale = Locale.forLanguageTag(tag)
        val lang = tag.substringBefore('-')
        if (lang == ENGLISH) return ENGLISH
        if (tag in byTag) return tag
        val region = locale.country
        val script = locale.script
        when (lang) {
            "es" -> return if (region.isEmpty() || region == "ES") "es" else "es-419"
            "pt" -> return if (region == "PT" || region == "AO" || region == "MZ") "pt-PT" else "pt-BR"
            "zh" -> return if (script == "Hant" || region in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            "sr" -> return "sr-Latn"
            "nb", "nn" -> return "no"
        }
        val withScript = if (script.isNotEmpty()) "$lang-$script" else null
        if (withScript != null && withScript in byTag) return withScript
        if (lang in byTag) return lang
        return byTag.keys.firstOrNull { it.substringBefore('-') == lang } ?: ENGLISH
    }

    /** A language in its own words, as the TV's picker shows it: "Deutsch", "Português (Brasil)". */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(normalize(tag))
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** The TV's picker: each language in its own name, sorted as the TV sorts them. */
    fun pickerOptions(): List<Pair<String, String>> = pickerTags.map { it to displayName(it) }.sortedBy { it.second }

    /** Arabic and Hebrew read right to left. */
    fun isRtl(tag: String): Boolean = normalize(tag).substringBefore('-') in setOf("ar", "he", "fa", "ur", "yi")
}
