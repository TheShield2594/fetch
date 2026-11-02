package com.fetch.tv.core.sync

import com.fetch.tv.core.parental.ScreenTimeDay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A kids profile's screen time as carried in its synced settings blob, next to "parental": one
 * record per TV, keyed by the TV's sync client id, so each TV only ever writes its own and the
 * day's total is the sum (see [com.fetch.tv.core.parental.mergeScreenTimeDays]).
 *
 * The blob is replaced whole on each push, so a TV sends the other TVs' records as it last read
 * them alongside its own. Two TVs pushing at the same moment can drop each other's record until
 * that TV's next push, a minute or two later.
 */
internal object ScreenTimeBlob {
    const val KEY = "screenTime"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(records: Map<String, ScreenTimeDay>): JsonObject =
        JsonObject(records.mapValues { (_, day) -> json.encodeToJsonElement(day) })

    /** Every TV's record in [blob]; records that can't be read are left out. */
    fun decode(blob: JsonObject?): Map<String, ScreenTimeDay> {
        val records = blob?.get(KEY) as? JsonObject ?: return emptyMap()
        return records.mapNotNull { (clientId, element) ->
            runCatching { json.decodeFromJsonElement<ScreenTimeDay>(element) }.getOrNull()?.let { clientId to it }
        }.toMap()
    }

    /**
     * The records to send: [others] as last read plus this TV's [own] under [ownClientId],
     * keeping only days from [oldestDate] on (ISO dates sort as text), so finished days drop off.
     */
    fun recordsForPush(
        others: Map<String, ScreenTimeDay>,
        ownClientId: String,
        own: ScreenTimeDay?,
        oldestDate: String
    ): Map<String, ScreenTimeDay> {
        val merged = others - ownClientId + listOfNotNull(own?.let { ownClientId to it })
        return merged.filterValues { it.date >= oldestDate }
    }
}
