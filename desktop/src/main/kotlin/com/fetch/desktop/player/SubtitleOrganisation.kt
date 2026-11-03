package com.fetch.desktop.player

import com.fetch.desktop.AddonSubtitle
import com.fetch.desktop.data.SubtitleOrganization
import com.fetch.desktop.player.embedded.MpvTrack
import java.util.Locale

/** One choice in the player's subtitle list: a track of the file (or one already added), or an addon's file. */
sealed interface SubtitleChoice {
    val languageKey: String

    data class Track(val track: MpvTrack, override val languageKey: String) : SubtitleChoice
    data class Addon(val subtitle: AddonSubtitle, override val languageKey: String, val added: MpvTrack? = null) : SubtitleChoice
}

/** A heading in the subtitle list and what's under it. */
data class SubtitleGroup(val key: String, val label: String, val choices: List<SubtitleChoice>)

/**
 * The TV's subtitle list organisation (SubtitleSelectionOverlay): built-in tracks and addon
 * subtitles together, grouped by language with your preferred languages first, the file's own
 * tracks before the addons' (in your addon order), and with "only preferred languages" the other
 * languages hidden (the playing one always stays).
 */
object SubtitleOrganisation {
    const val UNKNOWN = "und"

    private val bibliographic = mapOf(
        "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka",
        "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy", "esp" to "es"
    )
    private val iso3: Map<String, String> by lazy {
        Locale.getISOLanguages().mapNotNull { two -> runCatching { Locale(two).isO3Language }.getOrNull()?.takeIf { it.isNotBlank() }?.let { it to two } }.toMap()
    }

    /** "en" for "en", "eng", "en-US" or "English"; "pt-br" and "es-419" stay apart, as on TV. */
    fun languageKey(code: String?, title: String? = null): String {
        val raw = code?.trim()?.lowercase()?.replace('_', '-')
        val hint = listOfNotNull(raw, title?.lowercase()).joinToString(" ")
        if (raw.isNullOrBlank() || raw == "und" || raw == "unknown") {
            return title?.let { t -> com.fetch.tv.core.streams.ranking.StreamLanguage.fromCode(t.trim())?.code } ?: UNKNOWN
        }
        val base = raw.substringBefore('-')
        val two = when {
            base.length == 2 -> base
            base.length == 3 -> bibliographic[base] ?: iso3[base] ?: base
            else -> com.fetch.tv.core.streams.ranking.StreamLanguage.fromCode(base)?.code ?: base
        }
        return when {
            two == "pt" && (raw == "pt-br" || "brazil" in hint || "pt-br" in hint) -> "pt-br"
            two == "es" && (raw == "es-419" || "latin" in hint || "es-419" in hint) -> "es-419"
            else -> two
        }
    }

    /** "English", "Portuguese (Brazil)", "Unknown". */
    fun languageLabel(key: String): String = when (key) {
        UNKNOWN -> "Unknown"
        "pt-br" -> "Portuguese (Brazil)"
        "es-419" -> "Spanish (Latin America)"
        else -> Locale(key).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it.lowercase() != key }
            ?.replaceFirstChar { it.uppercase() } ?: key.uppercase()
    }

    /** Your languages as keys, first then second, without duplicates. */
    fun preferredKeys(primary: String?, secondary: String?): List<String> =
        listOfNotNull(primary, secondary).filter { it.isNotBlank() }.map { languageKey(it) }.filter { it != UNKNOWN }.distinct()

    /**
     * The list for [tracks] (mpv's subtitle tracks) and [addonSubtitles]. An addon file already
     * added shows once, as the addon's, with its track. [addonOrder] is your addons by name.
     */
    fun organise(
        tracks: List<MpvTrack>,
        addonSubtitles: List<AddonSubtitle>,
        mode: SubtitleOrganization,
        preferred: List<String>,
        onlyPreferred: Boolean,
        addonOrder: List<String> = emptyList()
    ): List<SubtitleGroup> {
        val subs = tracks.filter { it.type == "sub" }
        val addedByUrl = subs.filter { it.external && it.externalFilename != null }.associateBy { it.externalFilename!! }
        val addonUrls = addonSubtitles.map { it.url }.toSet()
        val own = subs.filter { !(it.external && it.externalFilename in addonUrls) }
            .map { SubtitleChoice.Track(it, languageKey(it.lang, it.title)) }
        val order = addonOrder.withIndex().associate { (i, name) -> name to i }
        val addons = addonSubtitles.distinctBy { it.url }.withIndex()
            .sortedWith(compareBy({ order[it.value.addon] ?: Int.MAX_VALUE }, { it.index }))
            .map { (_, s) -> SubtitleChoice.Addon(s, languageKey(s.language), addedByUrl[s.url]) }
        val playingKey = (own.firstOrNull { it.track.selected }?.languageKey) ?: addons.firstOrNull { it.added?.selected == true }?.languageKey
        val keep: (SubtitleChoice) -> Boolean = { c -> !onlyPreferred || preferred.isEmpty() || c.languageKey in preferred || c.languageKey == playingKey }
        val all: List<SubtitleChoice> = (own + addons).filter(keep)
        return when (mode) {
            SubtitleOrganization.NONE -> listOf(SubtitleGroup("all", "Subtitles", all)).filter { it.choices.isNotEmpty() }
            SubtitleOrganization.BY_ADDON -> {
                val builtIn = all.filterIsInstance<SubtitleChoice.Track>()
                val byAddon = all.filterIsInstance<SubtitleChoice.Addon>().groupBy { it.subtitle.addon.ifBlank { "Addon" } }
                listOfNotNull(SubtitleGroup("builtin", "In the file", builtIn).takeIf { builtIn.isNotEmpty() }) +
                    byAddon.map { (addon, choices) -> SubtitleGroup("addon:$addon", addon, choices) }
            }
            SubtitleOrganization.BY_LANGUAGE -> all.groupBy { it.languageKey }.entries
                .sortedWith(compareBy({ preferred.indexOf(it.key).let { i -> if (i >= 0) i else Int.MAX_VALUE } }, { if (it.key == UNKNOWN) "￿" else languageLabel(it.key) }))
                .map { (key, choices) ->
                    // The file's own tracks first, then the addons' (already in addon order).
                    SubtitleGroup(key, languageLabel(key), choices.filterIsInstance<SubtitleChoice.Track>() + choices.filterIsInstance<SubtitleChoice.Addon>())
                }
        }
    }

    /** How many choices "only preferred languages" hid. */
    fun hiddenCount(tracks: List<MpvTrack>, addonSubtitles: List<AddonSubtitle>, preferred: List<String>, mode: SubtitleOrganization): Int {
        val all = organise(tracks, addonSubtitles, mode, preferred, onlyPreferred = false).sumOf { it.choices.size }
        val shown = organise(tracks, addonSubtitles, mode, preferred, onlyPreferred = true).sumOf { it.choices.size }
        return all - shown
    }
}
