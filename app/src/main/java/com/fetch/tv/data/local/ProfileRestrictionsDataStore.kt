package com.fetch.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.core.parental.ContentRestriction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileRestrictionsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_restrictions",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/** Parental settings for one profile. */
data class ProfileRestrictions(
    val isKidsProfile: Boolean = false,
    val maxLevel: CertificationLevel? = null,
    val allowUnrated: Boolean = true
) {
    val contentRestriction: ContentRestriction
        get() = ContentRestriction(maxLevel = maxLevel, allowUnrated = allowUnrated)

    /** A restricted profile can't manage addons and needs the parent PIN for settings. */
    val isRestricted: Boolean get() = isKidsProfile || maxLevel != null

    companion object {
        val None = ProfileRestrictions()

        /** Preset applied when a profile is marked as a kids profile. */
        val KidsPreset = ProfileRestrictions(
            isKidsProfile = true,
            maxLevel = CertificationLevel.PARENTAL_GUIDANCE,
            allowUnrated = false
        )
    }
}

/** A title on a profile's hidden or allowed list, with every id it's known by. */
@Serializable
data class ParentalTitle(
    val ids: List<String>,
    val type: String,
    val name: String
) {
    /** True for [contentId] or [imdbId] naming this title, or one of its episodes ("tt123:1:2"). */
    fun matches(contentId: String, imdbId: String?): Boolean = ids.any { id ->
        id == contentId || id == imdbId || contentId.startsWith("$id:")
    }
}

/**
 * Titles a parent hid from a profile, and titles allowed on it whatever their rating. A title
 * is on one list at most.
 */
@Serializable
data class ProfileTitleLists(
    val hidden: List<ParentalTitle> = emptyList(),
    val allowed: List<ParentalTitle> = emptyList()
) {
    val isEmpty: Boolean get() = hidden.isEmpty() && allowed.isEmpty()

    fun isHidden(contentId: String, imdbId: String?): Boolean = hidden.any { it.matches(contentId, imdbId) }

    fun isAllowed(contentId: String, imdbId: String?): Boolean = allowed.any { it.matches(contentId, imdbId) }

    fun withHidden(title: ParentalTitle): ProfileTitleLists = without(title).let { it.copy(hidden = it.hidden + title) }

    fun withAllowed(title: ParentalTitle): ProfileTitleLists = without(title).let { it.copy(allowed = it.allowed + title) }

    /** Takes the title off both lists. */
    fun without(title: ParentalTitle): ProfileTitleLists {
        fun List<ParentalTitle>.drop() = filterNot { listed -> listed.ids.any { it in title.ids } }
        return ProfileTitleLists(hidden = hidden.drop(), allowed = allowed.drop())
    }

    companion object {
        val Empty = ProfileTitleLists()
    }
}

/**
 * Per-profile parental restrictions. Kept on the device and outside the synced profile
 * list, so a sync pull can never loosen them.
 */
@Singleton
class ProfileRestrictionsDataStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.profileRestrictionsDataStore)

    private val restrictionsKey = stringPreferencesKey("restrictions_map")
    // Kept apart from the limits, which a website pull replaces; the lists are only set on this TV.
    private val titleListsKey = stringPreferencesKey("title_lists")
    private val json = Json { ignoreUnknownKeys = true }

    val restrictions: Flow<Map<Int, ProfileRestrictions>> = dataStore.data.map { prefs ->
        decode(prefs[restrictionsKey])
    }

    suspend fun setRestrictions(profileId: Int, restrictions: ProfileRestrictions) {
        dataStore.edit { prefs ->
            val current = decode(prefs[restrictionsKey]).toMutableMap()
            if (restrictions == ProfileRestrictions.None) current.remove(profileId) else current[profileId] = restrictions
            prefs[restrictionsKey] = encode(current)
        }
    }

    val titleLists: Flow<Map<Int, ProfileTitleLists>> = dataStore.data.map { prefs ->
        decodeTitleLists(prefs[titleListsKey])
    }

    suspend fun updateTitleLists(profileId: Int, update: (ProfileTitleLists) -> ProfileTitleLists) {
        dataStore.edit { prefs ->
            val current = decodeTitleLists(prefs[titleListsKey]).toMutableMap()
            val updated = update(current[profileId] ?: ProfileTitleLists.Empty)
            if (updated.isEmpty) current.remove(profileId) else current[profileId] = updated
            prefs[titleListsKey] = json.encodeToString(current.mapKeys { it.key.toString() })
        }
    }

    /** Drops the profile's limits. Its title lists stay: they're kept on this TV whatever the website says. */
    suspend fun removeProfile(profileId: Int) = setRestrictions(profileId, ProfileRestrictions.None)

    /** For a deleted profile: its limits and its title lists. */
    suspend fun deleteProfile(profileId: Int) {
        removeProfile(profileId)
        updateTitleLists(profileId) { ProfileTitleLists.Empty }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    // "<id>:<kids>:<level or ->:<allowUnrated>" entries separated by ','.
    private fun encode(map: Map<Int, ProfileRestrictions>): String =
        map.entries.joinToString(separator = ",") { (id, value) ->
            "$id:${value.isKidsProfile}:${value.maxLevel?.name ?: "-"}:${value.allowUnrated}"
        }

    private fun decodeTitleLists(raw: String?): Map<Int, ProfileTitleLists> {
        if (raw.isNullOrBlank()) return emptyMap()
        val stored = runCatching { json.decodeFromString<Map<String, ProfileTitleLists>>(raw) }.getOrNull()
            ?: return emptyMap()
        return stored.mapNotNull { (id, lists) -> id.toIntOrNull()?.let { it to lists } }.toMap()
    }

    private fun decode(raw: String?): Map<Int, ProfileRestrictions> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 4) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val kids = parts[1].toBooleanStrictOrNull() ?: return@mapNotNull null
            val level = if (parts[2] == "-") null else CertificationLevel.fromName(parts[2]) ?: return@mapNotNull null
            val allowUnrated = parts[3].toBooleanStrictOrNull() ?: return@mapNotNull null
            id to ProfileRestrictions(kids, level, allowUnrated)
        }.toMap()
    }
}
