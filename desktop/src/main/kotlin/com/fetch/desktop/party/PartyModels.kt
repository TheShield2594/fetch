package com.fetch.desktop.party

import com.fetch.tv.domain.model.ProxyHeaders
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBadge
import com.fetch.tv.domain.model.StreamBehaviorHints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The watch party's wire models, the same as the TV app's `core/party/PartyModels.kt`, so a
// desktop and a TV can share one party.

@Serializable
data class PartySource(
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("file_idx") val fileIdx: Int? = null,
    val filename: String? = null,
    @SerialName("video_size") val videoSize: Long? = null,
    @SerialName("binge_group") val bingeGroup: String? = null,
    @SerialName("addon_id") val addonId: String? = null,
    val name: String? = null,
) {
    /** The TV's PartySourceMatch: the same torrent (and file), else the same file name. */
    fun matches(stream: Stream): Boolean {
        val hash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val streamHash = stream.getEffectiveInfoHash()?.lowercase()
        if (hash != null && streamHash != null) {
            val index = fileIdx
            val streamIndex = stream.getEffectiveFileIdx()
            return hash == streamHash && (index == null || streamIndex == null || index == streamIndex)
        }
        val file = filename?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val streamFile = (stream.behaviorHints?.filename ?: stream.clientResolve?.filename)?.trim()
        return !streamFile.isNullOrEmpty() && file.equals(streamFile, ignoreCase = true)
    }
}

@Serializable
data class PartyMedia(
    val type: String,
    @SerialName("meta_id") val metaId: String,
    @SerialName("meta_type") val metaType: String,
    @SerialName("video_id") val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String = "",
    @SerialName("episode_title") val episodeTitle: String? = null,
    val description: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val source: PartySource? = null,
) {
    val key: String
        get() = partyMediaKey(metaType, metaId, season, episode)

    /** "S1 E2 · Title", or null for a movie. */
    val episodeLabel: String?
        get() {
            val s = season ?: return null
            val e = episode ?: return null
            val label = "S$s E$e"
            return episodeTitle?.takeIf { it.isNotBlank() }?.let { "$label · $it" } ?: label
        }

    /** "Show · S1 E2", as the TV's `displayTitle`. */
    val displayTitle: String
        get() {
            val s = season ?: return title
            val e = episode ?: return title
            val label = "S$s E$e"
            return if (title.isBlank()) label else "$title · $label"
        }
}

@Serializable
data class PartyStream(
    @SerialName("media_key") val mediaKey: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    @SerialName("response_headers") val responseHeaders: Map<String, String> = emptyMap(),
    val name: String? = null,
    val description: String? = null,
    @SerialName("addon_name") val addonName: String = "",
    @SerialName("addon_id") val addonId: String = "",
    @SerialName("addon_logo") val addonLogo: String? = null,
    val filename: String? = null,
    @SerialName("video_size") val videoSize: Long? = null,
    @SerialName("binge_group") val bingeGroup: String? = null,
    @SerialName("stream_type") val streamType: String? = null,
    val badges: List<StreamBadge> = emptyList(),
) {
    fun toStream(): Stream = Stream(
        name = name,
        title = null,
        description = description,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = ProxyHeaders(request = headers, response = responseHeaders),
            videoSize = videoSize,
            filename = filename,
        ),
        addonName = addonName,
        addonLogo = addonLogo,
        badges = badges,
    )
}

/** Only links friends can open: http(s) and not this computer. */
fun isShareableStreamUrl(url: String): Boolean = SHAREABLE_URL.containsMatchIn(url)

private val SHAREABLE_URL = Regex("^https?://(?!localhost|127\\.|0\\.0\\.0\\.0|\\[::1])[^\\s/]+", RegexOption.IGNORE_CASE)

fun partyMediaKey(metaType: String, metaId: String, season: Int?, episode: Int?): String =
    listOf(metaType.lowercase(), metaId, season?.toString().orEmpty(), episode?.toString().orEmpty())
        .joinToString("|")

@Serializable
data class PartyPlayback(
    val seq: Long,
    @SerialName("member_id") val memberId: String? = null,
    val paused: Boolean,
    @SerialName("position_ms") val positionMs: Long,
    val rate: Float = 1f,
    val at: Long,
    @SerialName("media_key") val mediaKey: String? = null,
    val waiting: Boolean = false,
)

@Serializable
data class PartyMember(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_color") val avatarColor: String? = null,
)

@Serializable
data class PartyPreview(
    val media: PartyMedia? = null,
    val host: PartyMember? = null,
    val members: Int = 0,
    @SerialName("max_members") val maxMembers: Int = 0,
    val error: String? = null,
) {
    val isFull: Boolean
        get() = maxMembers in 1..members
}

@Serializable
enum class PartyMemberStatus {
    @SerialName("browsing") Browsing,
    @SerialName("loading") Loading,
    @SerialName("buffering") Buffering,
    @SerialName("ready") Ready,
    @SerialName("behind") Behind,
}

@Serializable
data class PartyPresence(
    @SerialName("member_id") val memberId: String,
    val status: PartyMemberStatus = PartyMemberStatus.Browsing,
    @SerialName("media_key") val mediaKey: String? = null,
    @SerialName("behind_ms") val behindMs: Long? = null,
)

@Serializable
internal data class PartyInfo(
    val id: String,
    val code: String,
    @SerialName("host_member_id") val hostMemberId: String? = null,
    @SerialName("host_only") val hostOnly: Boolean = false,
    val media: PartyMedia,
    val playback: PartyPlayback? = null,
    @SerialName("max_members") val maxMembers: Int = 3,
)

@Serializable
internal data class PartySnapshot(
    val party: PartyInfo? = null,
    val members: List<PartyMember> = emptyList(),
    @SerialName("member_id") val memberId: String? = null,
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    val error: String? = null,
    val ok: Boolean? = null,
)

enum class PartyError(val message: String) {
    NotFound("No watch party found with that code."),
    Full("This watch party is full."),
    Removed("You can’t join this watch party."),
    Ended("This watch party has ended."),
    RateLimited("Too many attempts. Try again in a few minutes."),
    SignInRequired("Sign in to your Fetch account to use watch parties."),
    Unavailable("Watch parties aren’t available right now."),
    Network("Couldn’t reach the watch party. Check your connection."),
}

internal fun partyErrorOf(code: String?): PartyError? = when (code) {
    null -> null
    "party_not_found" -> PartyError.NotFound
    "party_full" -> PartyError.Full
    "removed", "not_member" -> PartyError.Removed
    "party_ended" -> PartyError.Ended
    "rate_limited" -> PartyError.RateLimited
    "unavailable" -> PartyError.Unavailable
    else -> PartyError.Network
}

data class ActiveParty(
    val id: String,
    val code: String,
    val memberId: String,
    val hostMemberId: String?,
    val members: List<PartyMember>,
    val presence: Map<String, PartyPresence>,
    val media: PartyMedia,
    val maxMembers: Int,
    val connected: Boolean,
    val hostOnly: Boolean = false,
    val sharing: Boolean = false,
    val inviteLink: String = "fetch://party/$code",
) {
    val isHost: Boolean
        get() = memberId == hostMemberId

    val canControl: Boolean
        get() = !hostOnly || isHost

    fun member(id: String?): PartyMember? = members.firstOrNull { it.id == id }
}

data class PartyUiState(
    val party: ActiveParty? = null,
    val busy: Boolean = false,
)

sealed interface PartyEvent {
    data class MemberJoined(val name: String) : PartyEvent
    data class MemberLeft(val name: String) : PartyEvent
    data object Removed : PartyEvent
    data object Ended : PartyEvent
    data class MediaChanged(val media: PartyMedia) : PartyEvent
    data class ControlChanged(val hostOnly: Boolean) : PartyEvent

    /** The TV's toast texts. */
    val message: String
        get() = when (this) {
            is MemberJoined -> "$name joined the watch party"
            is MemberLeft -> "$name left the watch party"
            Removed -> "You were removed from the watch party"
            Ended -> "The watch party has ended"
            is MediaChanged -> "The party is now watching ${media.title}"
            is ControlChanged -> if (hostOnly) "Only the host can control playback now" else "Everyone can control playback now"
        }
}

enum class PartyAction {
    Paused,
    Resumed,
    Seeked,
    Speed,
}

data class PartyNotice(
    val name: String,
    val action: PartyAction,
    val positionMs: Long,
    val rate: Float,
)

data class PartyIdentity(
    val deviceId: String,
    val displayName: String,
    val avatarUrl: String?,
    val avatarColor: String?,
)

fun normalizePartyCode(input: String): String =
    input.uppercase().filter { it in PARTY_CODE_ALPHABET }

fun isValidPartyCode(input: String): Boolean = normalizePartyCode(input).length == PARTY_CODE_LENGTH

fun formatPartyCode(code: String): String =
    normalizePartyCode(code).let { if (it.length == PARTY_CODE_LENGTH) "${it.take(3)} ${it.drop(3)}" else it }

/** A code from a `fetch://party/<code>` invite link, or the text itself when it is a code. */
fun partyCodeFrom(input: String): String {
    val trimmed = input.trim()
    val fromLink = Regex("^fetch://party/([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
    return normalizePartyCode(fromLink ?: trimmed).take(PARTY_CODE_LENGTH)
}

const val PARTY_CODE_LENGTH = 6
const val PARTY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
