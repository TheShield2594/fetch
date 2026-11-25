package com.fetch.tv.data.website

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Brings together two copies of a profile's collections that were both edited since they last
 * agreed: this device's, and the one another device saved to the website in the meantime.
 *
 * The website stores the collections as one document, so a push used to replace everything
 * another TV had added since this one last pulled. A push now names the version it was based
 * on, and when the website answers that the version has moved on, this is what decides the
 * list both sides end up with.
 */
object FetchWebsiteCollectionsMerge {

    /**
     * The merge of [local] and [remote], collection by collection, against [base]: the copy
     * this device and the website last agreed on.
     *
     * - A collection this device hasn't touched since [base] takes whatever the website holds
     *   for it now, including being gone.
     * - A collection added, changed or deleted here since [base] stays as it is here: a local
     *   edit wins over an edit made to the same collection elsewhere.
     * - A collection the website has that this device never saw is kept, after the local ones.
     *
     * With no [base] (a device that hasn't synced since versions existed) nothing can be
     * called a deletion, so the result is every collection either side has, local copies first.
     */
    fun merge(base: JsonArray?, local: JsonArray, remote: JsonArray): JsonArray {
        val baseById = base.orEmpty().byId()
        val remoteById = remote.byId()
        val localIds = HashSet<String>()
        val merged = ArrayList<JsonElement>(local.size + remote.size)

        for (collection in local) {
            val id = collection.collectionId()
            if (id == null) {
                merged += collection
                continue
            }
            localIds += id
            val changedHere = baseById[id] != collection
            if (changedHere) {
                merged += collection
            } else {
                // Untouched here, so the website's copy is the newer one; absent means deleted there.
                remoteById[id]?.let { merged += it }
            }
        }

        for (collection in remote) {
            val id = collection.collectionId() ?: continue
            if (id in localIds) continue
            // In the base but no longer here: deleted on this device, and that stands.
            if (id in baseById) continue
            merged += collection
        }
        return JsonArray(merged)
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private fun JsonArray.byId(): Map<String, JsonElement> {
        val byId = LinkedHashMap<String, JsonElement>(size)
        for (collection in this) collection.collectionId()?.let { byId[it] = collection }
        return byId
    }

    private fun JsonElement.collectionId(): String? =
        ((this as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
}
