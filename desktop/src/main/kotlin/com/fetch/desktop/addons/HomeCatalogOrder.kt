package com.fetch.desktop.addons

import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.home.HomeLayout
import com.fetch.tv.domain.model.catalogRowLegacyKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A Home catalog row as the order needs it: its desktop key and the TV's (`addonId_type_catalogId`). */
data class OrderedCatalog(val key: String, val tvKey: String)

fun tvCatalogKey(addonId: String, type: String, catalogId: String): String = catalogRowLegacyKey(addonId, type, catalogId)

/**
 * The order of Home's catalog rows, as the TV's Catalog order screen keeps it
 * ([DesktopSettings.catalogOrder], in the TV's keys so it syncs). Pinned rows still lead, in pin
 * order; then the saved order; then rows it doesn't know yet, in addon order.
 */
object HomeCatalogOrder {
    fun <T> ordered(rows: List<T>, settings: DesktopSettings, key: (T) -> String, tvKey: (T) -> String?): List<T> {
        val pinned = settings.pinnedCatalogs
        val index = settings.catalogOrder.withIndex().associate { it.value to it.index }
        val (first, rest) = rows.partition { key(it) in pinned }
        return first.sortedBy { pinned.indexOf(key(it)) } +
            rest.withIndex().sortedWith(compareBy({ tvKey(it.value)?.let(index::get) ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    /** Whether [row] is hidden from Home: by its desktop key, or by the TV's (from a sync). */
    fun isDisabled(settings: DesktopSettings, row: OrderedCatalog): Boolean =
        row.key in settings.disabledCatalogs || row.tvKey in settings.disabledCatalogs

    /**
     * [settings] after moving [row] by [delta] places among [rows] (Home's whole list, hidden ones
     * included). The order then holds every row, and pins become plain positions.
     */
    fun move(settings: DesktopSettings, rows: List<OrderedCatalog>, row: OrderedCatalog, delta: Int): DesktopSettings {
        val current = ordered(rows, settings, { it.key }, { it.tvKey }).map { it.tvKey }.distinct().toMutableList()
        val from = current.indexOf(row.tvKey)
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (from < 0 || from == to) return settings
        current.add(to, current.removeAt(from))
        // Keys of rows not loaded right now keep their place after the known ones.
        val unknown = settings.catalogOrder.filter { it !in current }
        return settings.copy(catalogOrder = current + unknown, pinnedCatalogs = emptyList())
    }

    fun setEnabled(settings: DesktopSettings, row: OrderedCatalog, enabled: Boolean): DesktopSettings {
        val without = settings.disabledCatalogs - row.key - row.tvKey
        return settings.copy(disabledCatalogs = if (enabled) without else without + row.key)
    }

    fun reset(settings: DesktopSettings): DesktopSettings = settings.copy(catalogOrder = emptyList(), pinnedCatalogs = emptyList())
}

// --- Sync, as the TV's HomeCatalogSettingsSyncService ---

@Serializable
data class SyncCatalogItem(
    @SerialName("addon_id") val addonId: String,
    val type: String,
    @SerialName("catalog_id") val catalogId: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    @SerialName("custom_title") val customTitle: String = "",
    @SerialName("is_collection") val isCollection: Boolean = false,
    @SerialName("collection_id") val collectionId: String = ""
)

@Serializable
data class SyncHomeCatalogPayload(
    @SerialName("hide_unreleased_content") val hideUnreleasedContent: Boolean = false,
    val items: List<SyncCatalogItem> = emptyList()
)

/** A catalog row as the payload names it. */
data class SyncableCatalog(val key: String, val addonId: String, val type: String, val catalogId: String) {
    val tvKey: String get() = tvCatalogKey(addonId, type, catalogId)
    val ordered: OrderedCatalog get() = OrderedCatalog(key, tvKey)
}

object HomeCatalogSync {
    /** The settings row the TV keeps Home's catalogs in, shared by its platforms. */
    const val PLATFORM = "home_catalog_shared"
    private const val HIDE_UNRELEASED = "hide_unreleased_content"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Home's catalogs and collections in their order with whether each shows, as the TV pushes
     * them. [collectionIds] are this profile's collections; each goes where Home shows it (after
     * the catalog it follows in the Home catalogs order, `layout.homeOrder`), as on TV.
     * Collections only the account knows keep their place from [remote], and custom titles the
     * TV set are kept too.
     */
    fun payload(
        catalogs: List<SyncableCatalog>,
        settings: DesktopSettings,
        remote: SyncHomeCatalogPayload?,
        collectionIds: List<String> = emptyList()
    ): SyncHomeCatalogPayload {
        val distinct = catalogs.distinctBy { it.tvKey }
        val ordered = HomeCatalogOrder.ordered(distinct, settings, { it.key }, { it.tvKey })
        val byTvKey = ordered.associateBy { it.tvKey }
        val remoteItems = remote?.items.orEmpty().sortedBy { it.order }
        val titles = remoteItems.associate { itemKey(it) to it.customTitle }
        val remoteCollections = remoteItems.filter { it.isCollection && it.collectionId.isNotBlank() }.associateBy { it.collectionId }
        // Where collections go: the local Home order (desktop or TV keys), else the account's.
        val toTvKey = distinct.associate { it.key to it.tvKey }
        val placement = settings.layout.homeOrder.map { toTvKey[it] ?: it }.ifEmpty { remoteItems.map(::itemKey) }
        val keys = HomeLayout.middle(
            catalogs = ordered.map { it.tvKey },
            collections = (collectionIds + remoteCollections.keys).distinct(),
            order = placement,
            followAddonsOrder = true
        )
        val hidden = settings.disabledCatalogs.toSet()
        val items = keys.mapIndexedNotNull { index, key ->
            val catalog = byTvKey[key]
            if (catalog != null) {
                SyncCatalogItem(
                    addonId = catalog.addonId, type = catalog.type, catalogId = catalog.catalogId,
                    enabled = !HomeCatalogOrder.isDisabled(settings, catalog.ordered),
                    order = index,
                    customTitle = titles[key].orEmpty()
                )
            } else {
                val id = key.removePrefix(COLLECTION_PREFIX)
                val local = id in collectionIds
                SyncCatalogItem(
                    addonId = "", type = "", catalogId = "",
                    enabled = if (local) key !in hidden else remoteCollections[id]?.enabled ?: true,
                    order = index,
                    customTitle = titles[key].orEmpty(),
                    isCollection = true,
                    collectionId = id
                )
            }
        }
        return SyncHomeCatalogPayload(settings.hideUnreleased, items)
    }

    private const val COLLECTION_PREFIX = "collection_"

    /** The TV's order key for [item]: `addonId_type_catalogId`, or `collection_<id>`. */
    private fun itemKey(item: SyncCatalogItem): String =
        if (item.isCollection) HomeLayout.collectionKey(item.collectionId) else tvCatalogKey(item.addonId, item.type, item.catalogId)

    /**
     * [settings] with the account's order, hidden catalogs and collections and "Hide unreleased"
     * (when the payload has it). The payload's collection places become Home's order
     * (`layout.homeOrder`, in the TV's keys). Hidden filter rows, which only this app has, stay
     * hidden, as do hidden collections the payload doesn't name.
     */
    fun apply(settings: DesktopSettings, remote: SyncHomeCatalogPayload, hasHideUnreleased: Boolean): DesktopSettings {
        val sorted = remote.items.sortedBy { it.order }
        val catalogs = sorted.filter { !it.isCollection }
        if (catalogs.isEmpty()) return settings
        val collections = sorted.filter { it.isCollection && it.collectionId.isNotBlank() }
        val order = catalogs.map(::itemKey)
        val disabled = sorted.filter { !it.enabled && (!it.isCollection || it.collectionId.isNotBlank()) }.map(::itemKey)
        val named = collections.map(::itemKey).toSet()
        val localOnly = settings.disabledCatalogs.filter {
            it.startsWith("filter|") || (it.startsWith(COLLECTION_PREFIX) && it !in named)
        }
        return settings.copy(
            catalogOrder = order,
            pinnedCatalogs = emptyList(),
            disabledCatalogs = localOnly + disabled,
            hideUnreleased = if (hasHideUnreleased) remote.hideUnreleasedContent else settings.hideUnreleased,
            layout = if (collections.isEmpty()) settings.layout else settings.layout.copy(homeOrder = sorted.map(::itemKey).distinct())
        )
    }

    fun decode(settingsJson: JsonObject): Pair<SyncHomeCatalogPayload, Boolean>? = runCatching {
        json.decodeFromJsonElement(SyncHomeCatalogPayload.serializer(), settingsJson) to settingsJson.containsKey(HIDE_UNRELEASED)
    }.getOrNull()

    /** The row `sync_pull_home_catalog_settings` returns, as its `settings_json`. */
    fun settingsJsonOf(body: String): JsonObject? = runCatching {
        val root = json.parseToJsonElement(body)
        val row = (root as? JsonArray)?.firstOrNull() ?: root
        (row as? JsonObject)?.get("settings_json")?.jsonObject
    }.getOrNull()

    /** The remote JSON's other keys, with this payload's over them (the TV's `mergeHomeCatalogSettingsJson`). */
    fun merged(remote: JsonObject?, payload: SyncHomeCatalogPayload): JsonObject {
        val local = json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), payload).jsonObject
        return buildJsonObject {
            remote?.forEach { (k, v) -> put(k, v) }
            local.forEach { (k, v) -> put(k, v) }
        }
    }

    fun pullParams(profileId: Int) = buildJsonObject {
        put("p_profile_id", profileId)
        put("p_platform", PLATFORM)
    }

    fun pushParams(profileId: Int, settingsJson: JsonObject, originClientId: String?) = buildJsonObject {
        put("p_profile_id", profileId)
        put("p_settings_json", settingsJson)
        put("p_platform", PLATFORM)
        originClientId?.let { put("p_origin_client_id", it) }
    }
}
