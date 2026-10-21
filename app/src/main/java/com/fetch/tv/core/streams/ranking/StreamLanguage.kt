package com.fetch.tv.core.streams.ranking

/**
 * Audio languages a release names. Addons write them as full names ("Italian"), three-letter
 * tags ("ITA"), flags ("🇮🇹") or, in structured results, ISO codes ("it"). Two-letter codes are
 * only trusted from structured results: in free text they collide with ordinary words.
 */
enum class StreamLanguage(
    val code: String,
    val label: String,
    internal val names: List<String>,
    internal val flags: List<String>
) {
    ENGLISH("en", "English", listOf("english", "eng"), listOf("🇬🇧", "🇺🇸")),
    HINDI("hi", "Hindi", listOf("hindi", "hin"), listOf("🇮🇳")),
    ITALIAN("it", "Italian", listOf("italian", "ita"), listOf("🇮🇹")),
    SPANISH("es", "Spanish", listOf("spanish", "castellano", "spa", "esp"), listOf("🇪🇸")),
    LATINO("la", "Latino", listOf("latino", "lat"), listOf("🇲🇽")),
    FRENCH("fr", "French", listOf("french", "vff", "vf2", "truefrench", "fre", "fra"), listOf("🇫🇷")),
    GERMAN("de", "German", listOf("german", "ger", "deu"), listOf("🇩🇪")),
    PORTUGUESE("pt", "Portuguese", listOf("portuguese", "dublado", "por"), listOf("🇵🇹", "🇧🇷")),
    POLISH("pl", "Polish", listOf("polish", "pol", "lektor"), listOf("🇵🇱")),
    CZECH("cs", "Czech", listOf("czech", "cze", "ces"), listOf("🇨🇿")),
    RUSSIAN("ru", "Russian", listOf("russian", "rus"), listOf("🇷🇺")),
    JAPANESE("ja", "Japanese", listOf("japanese", "jpn"), listOf("🇯🇵")),
    KOREAN("ko", "Korean", listOf("korean", "kor"), listOf("🇰🇷")),
    CHINESE("zh", "Chinese", listOf("chinese", "mandarin", "cantonese", "chi", "zho"), listOf("🇨🇳", "🇹🇼", "🇭🇰")),
    HEBREW("he", "Hebrew", listOf("hebrew", "heb"), listOf("🇮🇱")),
    MULTI("multi", "Multi audio", listOf("multi", "multi audio", "dual audio"), emptyList());

    companion object {
        fun fromCode(code: String?): StreamLanguage? {
            val normalized = code?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.code == normalized || it.label.lowercase() == normalized || normalized in it.names }
        }
    }
}
