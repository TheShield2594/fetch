package com.fetch.tv.data.website

import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.data.local.BundledAvatars
import com.fetch.tv.data.local.KidsRules
import com.fetch.tv.data.local.ParentalTitle
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.domain.model.PROFILE_AVATAR_COLORS
import com.fetch.tv.domain.model.UserProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/** Translation between the website's profiles and the TV's, kept pure so it can be tested. */
object FetchWebsiteMappings {
    /** The TV profile a website profile becomes, in the slot the website assigned this TV. */
    fun toUserProfile(remote: WebsiteSlottedProfile, local: UserProfile?): UserProfile = UserProfile(
        id = remote.slot,
        name = remote.name,
        avatarColorHex = remote.avatarColorHex
            ?: local?.avatarColorHex
            ?: PROFILE_AVATAR_COLORS[(remote.slot - 1).mod(PROFILE_AVATAR_COLORS.size)],
        usesPrimaryAddons = remote.usesPrimaryAddons,
        avatarId = remote.avatarId,
        // The website only knows avatar ids, and it decides the picture while this TV is
        // signed in to it.
        avatarUrl = null,
        profileBackgroundId = remote.profileBackgroundId,
        profileBackgroundUrl = local?.profileBackgroundUrl?.takeIf { remote.profileBackgroundId == local.profileBackgroundId }
    )

    /** The website's parental settings as the TV enforces them. */
    fun toRestrictions(parental: WebsiteParental): ProfileRestrictions = ProfileRestrictions(
        isKidsProfile = parental.isKidsProfile,
        maxLevel = CertificationLevel.fromName(parental.maxRating),
        allowUnrated = parental.allowUnrated
    )

    /**
     * What the TV sends for a profile edited here. Never a PIN: those are checked by the website.
     * [kidsRules] goes along when this TV has a copy of its own; the website keeps whichever copy
     * changed last. Never for the primary profile, which is the parent's.
     */
    fun toWebsiteBody(
        profile: UserProfile,
        restrictions: ProfileRestrictions,
        kidsRules: LocalKidsRules? = null
    ): JsonObject = buildJsonObject {
        put("name", profile.name)
        put("avatarColorHex", profile.avatarColorHex.takeIf { COLOR.matches(it) })
        // Only ids the website can draw; a server-catalog avatar has no meaning there.
        put("avatarId", profile.avatarId?.takeIf { it.startsWith(BundledAvatars.ID_PREFIX) })
        put("profileBackgroundId", profile.profileBackgroundId?.takeIf { ASSET_ID.matches(it) })
        if (!profile.isPrimary) put("usesPrimaryAddons", profile.usesPrimaryAddons)
        put(
            "parental",
            buildJsonObject {
                put("isKidsProfile", restrictions.isKidsProfile)
                put("maxRating", restrictions.maxLevel?.name)
                put("allowUnrated", restrictions.allowUnrated)
                if (kidsRules != null && !profile.isPrimary) putKidsRules(kidsRules)
            }
        )
    }

    /** A profile's kids rules and always-allowed titles on this TV, and when they last changed. */
    data class LocalKidsRules(
        val rules: KidsRules,
        val allowed: List<ParentalTitle>,
        val changedAtMs: Long
    )

    /** What to do with the website's copy of a profile's kids rules against this TV's. */
    sealed interface KidsRulesSync {
        /** Take the website's: it changed later than this TV's, or this TV has none of its own. */
        data class TakeWebsite(val rules: KidsRules, val allowed: List<ParentalTitle>, val changedAtMs: Long) : KidsRulesSync

        /** Send this TV's: it changed later, or the website has none yet. */
        data object SendLocal : KidsRulesSync

        /** Both sides agree, or neither has any. */
        data object Keep : KidsRulesSync
    }

    /**
     * The newer of the website's kids rules ([remote]) and this TV's wins, by when each last
     * changed. [localChangedAtMs] is null when this TV has never had a copy of its own: then the
     * website's is taken, or, when the website has none either, this TV's are sent if a parent set
     * any here ([localHasRules]) before the TV was paired or before this sync existed.
     */
    fun decideKidsRules(remote: WebsiteKidsRules?, localChangedAtMs: Long?, localHasRules: Boolean): KidsRulesSync {
        val remoteChangedAtMs = remote?.updatedAt?.let(::parseInstantMs)
        if (remote == null || remoteChangedAtMs == null) {
            return if (localChangedAtMs != null || localHasRules) KidsRulesSync.SendLocal else KidsRulesSync.Keep
        }
        return when {
            localChangedAtMs == null || remoteChangedAtMs > localChangedAtMs ->
                KidsRulesSync.TakeWebsite(fromWebsiteKidsRules(remote), fromWebsiteApprovedTitles(remote), remoteChangedAtMs)
            localChangedAtMs > remoteChangedAtMs -> KidsRulesSync.SendLocal
            else -> KidsRulesSync.Keep
        }
    }

    /** The website's rules as the TV keeps them; [KidsRules.approvedListIds] are the TV's own and stay. */
    fun fromWebsiteKidsRules(remote: WebsiteKidsRules): KidsRules {
        val start = remote.bedtimeStartMinute?.takeIf { it in 0 until MINUTES_PER_DAY }
        val end = remote.bedtimeEndMinute?.takeIf { it in 0 until MINUTES_PER_DAY }
        val hasBedtime = start != null && end != null
        return KidsRules(
            autoplayEpisodeCap = remote.autoplayEpisodeCap?.takeIf { it > 0 },
            approvedTitlesOnly = remote.approvedTitlesOnly,
            dailyMinutes = remote.dailyMinutes?.takeIf { it > 0 },
            bedtimeStartMinute = start.takeIf { hasBedtime },
            bedtimeEndMinute = end.takeIf { hasBedtime },
            hardStop = remote.hardStop
        )
    }

    fun fromWebsiteApprovedTitles(remote: WebsiteKidsRules): List<ParentalTitle> =
        remote.approvedTitles.mapNotNull { title ->
            val ids = title.ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (ids.isEmpty() || title.type.isBlank()) return@mapNotNull null
            ParentalTitle(ids = ids, type = title.type, name = title.name, poster = title.poster, background = title.background)
        }

    /** Epoch ms of an ISO 8601 instant, or null when it can't be read. */
    fun parseInstantMs(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    /**
     * Writes [local] the way the website validates it, leaving out what it would refuse (an id
     * too long, artwork that isn't a web address), so one odd title can't fail the whole profile.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putKidsRules(local: LocalKidsRules) {
        val rules = local.rules
        val hasBedtime = rules.bedtimeStartMinute?.let { it in 0 until MINUTES_PER_DAY } == true &&
            rules.bedtimeEndMinute?.let { it in 0 until MINUTES_PER_DAY } == true
        putJsonObject("kidsRules") {
            put("autoplayEpisodeCap", rules.autoplayEpisodeCap?.takeIf { it in 1..MAX_AUTOPLAY_CAP })
            put("approvedTitlesOnly", rules.approvedTitlesOnly)
            put("dailyMinutes", rules.dailyMinutes?.takeIf { it in 1..MINUTES_PER_DAY })
            put("bedtimeStartMinute", rules.bedtimeStartMinute.takeIf { hasBedtime })
            put("bedtimeEndMinute", rules.bedtimeEndMinute.takeIf { hasBedtime })
            put("hardStop", rules.hardStop)
            putJsonArray("approvedTitles") {
                local.allowed.asSequence()
                    .mapNotNull { title ->
                        val ids = title.ids.map { it.trim() }
                            .filter { it.isNotEmpty() && it.length <= MAX_ID_LENGTH && it.none { char -> char.code < 0x20 || char.code == 0x7f } }
                            .distinct()
                            .take(MAX_IDS_PER_TITLE)
                        val type = title.type.trim()
                        if (ids.isEmpty() || type.isEmpty() || type.length > MAX_TYPE_LENGTH) null else Triple(title, ids, type)
                    }
                    .take(MAX_APPROVED_TITLES)
                    .forEach { (title, ids, type) ->
                        addJsonObject {
                            putJsonArray("ids") { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                            put("type", type)
                            put("name", title.name.take(MAX_NAME_LENGTH))
                            put("poster", title.poster?.takeIf(::isWebUrl))
                            put("background", title.background?.takeIf(::isWebUrl))
                        }
                    }
            }
            put("updatedAt", Instant.ofEpochMilli(local.changedAtMs).toString())
        }
    }

    private fun isWebUrl(value: String): Boolean =
        value.length <= MAX_URL_LENGTH && (value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true))

    private const val MINUTES_PER_DAY = 24 * 60
    // The website's limits (web/apps/api/src/lib/kids-rules.ts).
    private const val MAX_AUTOPLAY_CAP = 50
    private const val MAX_APPROVED_TITLES = 1000
    private const val MAX_IDS_PER_TITLE = 20
    private const val MAX_ID_LENGTH = 200
    private const val MAX_TYPE_LENGTH = 32
    private const val MAX_NAME_LENGTH = 500
    private const val MAX_URL_LENGTH = 2048

    private val COLOR = Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")
    private val ASSET_ID = Regex("^[A-Za-z0-9:_.-]{1,100}$")
}
