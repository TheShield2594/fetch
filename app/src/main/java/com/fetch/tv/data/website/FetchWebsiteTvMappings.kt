package com.fetch.tv.data.website

import com.fetch.tv.core.sync.SyncCatalogItem
import com.fetch.tv.core.sync.SyncHomeCatalogPayload
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Translations between what the TV stores and what the Fetch website's addon, Home layout and
 * settings resources hold. Pure, so the rules that decide what survives a sync are testable on
 * the JVM.
 */
internal object FetchWebsiteTvMappings {

    private const val MANIFEST_SUFFIX = "/manifest.json"

    // ─── Addons ───

    /**
     * The website wants the manifest address; the TV keeps the addon's base URL. The query, which
     * some addons use for their settings, stays after the manifest path.
     */
    fun toManifestUrl(tvUrl: String): String {
        val trimmed = tvUrl.trim()
        val queryStart = trimmed.indexOf('?')
        val path = (if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed).trimEnd('/')
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val withManifest = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) path else path + MANIFEST_SUFFIX
        return withManifest + query
    }

    /** The base URL the TV stores for a manifest address from the website. */
    fun toTvAddonUrl(manifestUrl: String): String {
        val trimmed = manifestUrl.trim()
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val base = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) path.dropLast(MANIFEST_SUFFIX.length) else path
        return base.trimEnd('/') + query
    }

    // ─── Home layout ───

    private const val ADDON_PREFIX = "addon:"
    private const val COLLECTION_PREFIX = "collection:"

    /** `encodeURIComponent`, so a key built here is the same string the website builds. */
    fun encodeUriComponent(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) {
                out.append(ch)
            } else {
                out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
            }
        }
        return out.toString()
    }

    fun rowKey(item: SyncCatalogItem): String = if (item.isCollection) {
        COLLECTION_PREFIX + encodeUriComponent(item.collectionId)
    } else {
        ADDON_PREFIX + listOf(item.addonId, item.type, item.catalogId).joinToString(":") { encodeUriComponent(it) }
    }

    /** Rows the TV has its own entry for. Everything else on the website is the website's to place. */
    private fun isTvRow(key: String): Boolean = key.startsWith(ADDON_PREFIX) || key.startsWith(COLLECTION_PREFIX)

    /**
     * The layout to send: the TV's rows in the TV's order, with the rows only the website knows
     * (its lists, Continue Watching) kept where they were, so a push from the TV doesn't drop
     * what was placed on the website.
     *
     * The TV's payload only carries catalogs of enabled addons whose catalogs have loaded, so an
     * addon row it leaves out is kept too while that addon is still in [installedAddonIds]:
     * turning an addon off must not throw away where its rows sat or whether they were hidden.
     */
    fun toWebsiteRows(
        payload: SyncHomeCatalogPayload,
        remote: List<WebsiteHomeRow>,
        installedAddonIds: Set<String>
    ): List<WebsiteHomeRow> {
        val tvRows = payload.items.sortedBy { it.order }.map { item ->
            WebsiteHomeRow(
                key = rowKey(item),
                hidden = !item.enabled,
                title = item.customTitle.takeIf { it.isNotBlank() }
            )
        }
        val sentKeys = tvRows.mapTo(HashSet()) { it.key }
        val result = tvRows.toMutableList()
        remote.forEachIndexed { index, row ->
            val keep = !isTvRow(row.key) ||
                (row.key !in sentKeys && addonIdOf(row.key)?.let { it in installedAddonIds } == true)
            if (keep) result.add(index.coerceAtMost(result.size), row.copy(source = null))
        }
        return result
    }

    /** The addon id an `addon:` row key names, decoded; null for any other row. */
    private fun addonIdOf(key: String): String? {
        if (!key.startsWith(ADDON_PREFIX)) return null
        val parts = key.removePrefix(ADDON_PREFIX).split(":")
        return if (parts.size == 3) decode(parts[0]) else null
    }

    /**
     * The website's layout as the TV's payload. Only addon catalogs and collections become items;
     * a renamed row keeps the name it was given on this TV, which the website doesn't edit.
     */
    fun fromWebsiteRows(rows: List<WebsiteHomeRow>, local: SyncHomeCatalogPayload): SyncHomeCatalogPayload {
        val localByKey = local.items.associateBy { rowKey(it) }
        val items = rows.filter { isTvRow(it.key) }.mapNotNull { row -> itemFor(row, localByKey[row.key]) }
            .mapIndexed { index, item -> item.copy(order = index) }
        return local.copy(items = items)
    }

    private fun itemFor(row: WebsiteHomeRow, local: SyncCatalogItem?): SyncCatalogItem? {
        val enabled = !row.hidden
        if (local != null) return local.copy(enabled = enabled)
        if (row.key.startsWith(COLLECTION_PREFIX)) {
            val id = decode(row.key.removePrefix(COLLECTION_PREFIX)) ?: return null
            return SyncCatalogItem(addonId = "", type = "", catalogId = "", enabled = enabled, isCollection = true, collectionId = id)
        }
        val parts = row.key.removePrefix(ADDON_PREFIX).split(":")
        if (parts.size != 3) return null
        val (addonId, type, catalogId) = parts.map { decode(it) ?: return null }
        return SyncCatalogItem(addonId = addonId, type = type, catalogId = catalogId, enabled = enabled)
    }

    private fun decode(part: String): String? = runCatching {
        java.net.URLDecoder.decode(part.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    // ─── Settings ───

    private const val FEATURES = "features"

    /**
     * A three-way merge of the TV's settings blob (`{ version, features: { <feature>: { <key>:
     * { type, value } } } }`), key by key:
     *
     * - a key this TV changed since the copy both sides last agreed on ([base]) takes the TV's
     *   value, a removal included;
     * - every other key takes the website's, so an edit made there is kept;
     * - a feature this TV doesn't sync at all (absent from [local]) is the website's untouched,
     *   as is anything outside `features` — that is what lets a newer TV's settings survive an
     *   older one.
     *
     * With no [base] (the first sync after pairing) every key the TV has wins.
     *
     * [isNotSynced] names the keys this TV leaves out of its blob on purpose (device-only and
     * credential settings): their absence from [local] says nothing, so the website's value
     * stands rather than reading as a removal.
     */
    fun mergeSettings(
        base: JsonObject?,
        local: JsonObject,
        remote: JsonObject,
        isNotSynced: (feature: String, key: String) -> Boolean = { _, _ -> false }
    ): JsonObject {
        val baseFeatures = base?.get(FEATURES) as? JsonObject
        val localFeatures = local[FEATURES] as? JsonObject ?: JsonObject(emptyMap())
        val remoteFeatures = remote[FEATURES] as? JsonObject ?: JsonObject(emptyMap())

        val mergedFeatures = buildJsonObject {
            (remoteFeatures.keys + localFeatures.keys).distinct().forEach { feature ->
                val localFeature = localFeatures[feature] as? JsonObject
                val remoteFeature = remoteFeatures[feature] as? JsonObject ?: JsonObject(emptyMap())
                if (localFeature == null) {
                    put(feature, remoteFeature)
                    return@forEach
                }
                val baseFeature = baseFeatures?.get(feature) as? JsonObject
                put(
                    feature,
                    mergeFeature(baseFeature, localFeature, remoteFeature, hasBase = base != null) { key -> isNotSynced(feature, key) }
                )
            }
        }

        return buildJsonObject {
            remote.forEach { (key, value) -> if (key != FEATURES) put(key, value) }
            local.forEach { (key, value) -> if (key != FEATURES && key !in remote) put(key, value) }
            put(FEATURES, mergedFeatures)
        }
    }

    private fun mergeFeature(
        base: JsonObject?,
        local: JsonObject,
        remote: JsonObject,
        hasBase: Boolean,
        isNotSynced: (String) -> Boolean
    ): JsonObject =
        buildJsonObject {
            (remote.keys + local.keys + base?.keys.orEmpty()).distinct().forEach { key ->
                val localValue: JsonElement? = local[key]
                val chosen = if (isNotSynced(key)) {
                    remote[key]
                } else if (!hasBase) {
                    localValue ?: remote[key]
                } else if (localValue != base?.get(key)) {
                    localValue
                } else {
                    remote[key]
                }
                if (chosen != null) put(key, chosen)
            }
        }
}
