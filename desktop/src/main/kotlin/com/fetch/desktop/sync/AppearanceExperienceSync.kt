package com.fetch.desktop.sync

import com.fetch.desktop.data.AppearanceSettings
import com.fetch.desktop.data.ExperienceSettings
import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.CustomThemeColors
import com.fetch.tv.domain.model.ExperienceMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The TV's `theme_settings` keys beyond `selected_theme`. Null fields are "not in the blob" when
 * read and "leave as is" when pushed.
 */
data class RemoteAppearance(
    /** `custom_theme_colors`: `#RRGGBB,#RRGGBB,#RRGGBB`. */
    val customThemeColors: String? = null,
    /** `selected_font`: an [AppFont] name. */
    val font: String? = null,
    val amoledMode: Boolean? = null,
    val amoledSurfaces: Boolean? = null,
    val highContrast: Boolean? = null
) {
    val isEmpty: Boolean get() = this == RemoteAppearance()
}

/** The TV's `experience_settings`: `mode` (an [ExperienceMode] name) and `addon_setup_skipped`. */
data class RemoteExperience(
    val mode: String? = null,
    val addonSetupSkipped: Boolean? = null
) {
    val isEmpty: Boolean get() = this == RemoteExperience()
}

/** Reads and writes [RemoteAppearance] and [RemoteExperience] in the TV's settings blob. */
object AppearanceExperienceSync {
    const val EXPERIENCE = "experience_settings"

    private fun entry(blob: JsonObject?, feature: String, key: String, type: String): JsonElement? {
        val features = blob?.get(SyncSettingsBlob.FEATURES) as? JsonObject ?: return null
        val e = (features[feature] as? JsonObject)?.get(key) as? JsonObject ?: return null
        if ((e["type"] as? JsonPrimitive)?.contentOrNull != type) return null
        return e["value"]?.takeUnless { it is JsonNull }
    }

    private fun string(blob: JsonObject?, feature: String, key: String) =
        (entry(blob, feature, key, "string") as? JsonPrimitive)?.contentOrNull

    private fun boolean(blob: JsonObject?, feature: String, key: String) =
        (entry(blob, feature, key, "boolean") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    fun appearance(blob: JsonObject?): RemoteAppearance? = RemoteAppearance(
        customThemeColors = string(blob, SyncSettingsBlob.THEME, "custom_theme_colors"),
        font = string(blob, SyncSettingsBlob.THEME, "selected_font"),
        amoledMode = boolean(blob, SyncSettingsBlob.THEME, "amoled_mode"),
        amoledSurfaces = boolean(blob, SyncSettingsBlob.THEME, "amoled_surfaces_mode"),
        highContrast = boolean(blob, SyncSettingsBlob.THEME, "high_contrast")
    ).takeUnless { it.isEmpty }

    fun experience(blob: JsonObject?): RemoteExperience? = RemoteExperience(
        mode = string(blob, EXPERIENCE, "mode"),
        addonSetupSkipped = boolean(blob, EXPERIENCE, "addon_setup_skipped")
    ).takeUnless { it.isEmpty }

    /** The (feature, key, typed value) entries to write for [settings]' non-null fields. */
    fun edits(settings: RemoteSharedSettings): List<Triple<String, String, JsonObject>> = buildList {
        settings.appearance?.let { a ->
            a.customThemeColors?.let { add(Triple(SyncSettingsBlob.THEME, "custom_theme_colors", stringValue(it))) }
            a.font?.let { add(Triple(SyncSettingsBlob.THEME, "selected_font", stringValue(it))) }
            a.amoledMode?.let { add(Triple(SyncSettingsBlob.THEME, "amoled_mode", booleanValue(it))) }
            a.amoledSurfaces?.let { add(Triple(SyncSettingsBlob.THEME, "amoled_surfaces_mode", booleanValue(it))) }
            a.highContrast?.let { add(Triple(SyncSettingsBlob.THEME, "high_contrast", booleanValue(it))) }
        }
        settings.experience?.let { e ->
            e.mode?.let { add(Triple(EXPERIENCE, "mode", stringValue(it))) }
            e.addonSetupSkipped?.let { add(Triple(EXPERIENCE, "addon_setup_skipped", booleanValue(it))) }
        }
    }

    private fun stringValue(value: String) = buildJsonObject { put("type", "string"); put("value", value) }

    private fun booleanValue(value: Boolean) = buildJsonObject { put("type", "boolean"); put("value", value) }
}

/** What changed since [before], field by field; null when nothing did. */
fun RemoteAppearance?.changedFrom(before: RemoteAppearance?): RemoteAppearance? {
    val now = this ?: return null
    fun <T> pick(a: T?, b: T?): T? = a.takeIf { it != b }
    return RemoteAppearance(
        customThemeColors = pick(now.customThemeColors, before?.customThemeColors),
        font = pick(now.font, before?.font),
        amoledMode = pick(now.amoledMode, before?.amoledMode),
        amoledSurfaces = pick(now.amoledSurfaces, before?.amoledSurfaces),
        highContrast = pick(now.highContrast, before?.highContrast)
    ).takeUnless { it.isEmpty }
}

fun RemoteExperience?.changedFrom(before: RemoteExperience?): RemoteExperience? {
    val now = this ?: return null
    fun <T> pick(a: T?, b: T?): T? = a.takeIf { it != b }
    return RemoteExperience(
        mode = pick(now.mode, before?.mode),
        addonSetupSkipped = pick(now.addonSetupSkipped, before?.addonSetupSkipped)
    ).takeUnless { it.isEmpty }
}

/** [a] with the account's values; unreadable ones (an unknown font, bad colours) are left alone. */
fun RemoteAppearance.applyTo(a: AppearanceSettings): AppearanceSettings {
    var out = a
    customThemeColors?.takeIf { raw -> raw.split(",").size == 3 && raw.split(",").all { com.fetch.tv.domain.model.parseHexColor(it) != null } }
        ?.let { out = out.copy(customThemeColors = CustomThemeColors.decode(it).encode()) }
    font?.takeIf { name -> AppFont.entries.any { it.name == name } }?.let { out = out.copy(font = it) }
    amoledMode?.let { out = out.copy(amoledMode = it) }
    amoledSurfaces?.let { out = out.copy(amoledSurfaces = it) }
    highContrast?.let { out = out.copy(highContrast = it) }
    return out
}

fun RemoteExperience.applyTo(e: ExperienceSettings): ExperienceSettings {
    var out = e
    mode?.let { name -> ExperienceMode.entries.firstOrNull { it.name == name } }?.let { out = out.copy(mode = it) }
    addonSetupSkipped?.let { out = out.copy(addonSetupSkipped = it) }
    return out
}

fun AppearanceSettings.toRemote() = RemoteAppearance(
    customThemeColors = customThemeColors,
    font = font,
    amoledMode = amoledMode,
    amoledSurfaces = amoledSurfaces,
    highContrast = highContrast
)

/** A mode not chosen yet (the first-run wizard is open) isn't sent, so it can't clear the TV's. */
fun ExperienceSettings.toRemote() = RemoteExperience(mode = mode?.name, addonSetupSkipped = addonSetupSkipped)
