package com.fetch.tv.ui.screens.player

import androidx.annotation.StringRes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.fetch.tv.R
import com.fetch.tv.core.util.findCause

/**
 * What went wrong with playback, in the user's terms (#406). The error screen leads with
 * [headline] and [advice]; the technical message (HTTP status, error-code constant, decoder
 * name) goes in a small detail line underneath, for reports.
 */
enum class PlayerErrorKind(@StringRes val headline: Int, @StringRes val advice: Int) {
    LINK_EXPIRED(R.string.player_error_kind_link_expired, R.string.player_error_advice_other_source),
    LINK_REMOVED(R.string.player_error_kind_link_removed, R.string.player_error_advice_other_source),
    RATE_LIMITED(R.string.player_error_kind_rate_limited, R.string.player_error_advice_wait),
    SERVER_UNAVAILABLE(R.string.player_error_kind_server_unavailable, R.string.player_error_advice_other_source),
    ACCESS_BLOCKED(R.string.player_error_kind_access_blocked, R.string.player_error_advice_other_source),
    NOT_A_VIDEO(R.string.player_error_kind_not_a_video, R.string.player_error_advice_other_source),
    DAMAGED_FILE(R.string.player_error_kind_damaged_file, R.string.player_error_advice_other_source),
    UNSUPPORTED_FORMAT(R.string.player_error_kind_unsupported_format, R.string.player_error_advice_other_source_or_engine),
    DECODER_FAILED(R.string.player_error_kind_decoder_failed, R.string.player_error_advice_other_source_or_engine),
    AUDIO_FAILED(R.string.player_error_kind_audio_failed, R.string.player_error_advice_audio),
    NETWORK(R.string.player_error_kind_network, R.string.player_error_advice_network),
    TIMEOUT(R.string.player_error_kind_timeout, R.string.player_error_advice_wait),
    PROTECTED(R.string.player_error_kind_protected, R.string.player_error_advice_other_source),
    GENERIC(R.string.player_error_kind_generic, R.string.player_error_advice_other_source),
}

/** Ties a [PlayerErrorKind] to the error message it was worked out for. */
data class PlayerErrorTag(val message: String, val kind: PlayerErrorKind)

/** The kind of the error on screen, or null when the current error was set without one. */
internal fun PlayerUiState.currentErrorKind(): PlayerErrorKind? =
    errorTag?.takeIf { it.message == error }?.kind

internal fun playerErrorKindForHttpStatus(code: Int): PlayerErrorKind? = when (code) {
    401, 410 -> PlayerErrorKind.LINK_EXPIRED
    404 -> PlayerErrorKind.LINK_REMOVED
    429 -> PlayerErrorKind.RATE_LIMITED
    in 500..599 -> PlayerErrorKind.SERVER_UNAVAILABLE
    in 400..499 -> PlayerErrorKind.ACCESS_BLOCKED
    else -> null
}

internal fun playerErrorKindForErrorCode(errorCode: Int): PlayerErrorKind = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlayerErrorKind.SERVER_UNAVAILABLE
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> PlayerErrorKind.NETWORK
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT -> PlayerErrorKind.TIMEOUT
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlayerErrorKind.LINK_REMOVED
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> PlayerErrorKind.ACCESS_BLOCKED
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> PlayerErrorKind.NOT_A_VIDEO
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> PlayerErrorKind.DAMAGED_FILE
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> PlayerErrorKind.UNSUPPORTED_FORMAT
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> PlayerErrorKind.DECODER_FAILED
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> PlayerErrorKind.AUDIO_FAILED
    in DRM_ERROR_CODES -> PlayerErrorKind.PROTECTED
    else -> PlayerErrorKind.GENERIC
}

private val DRM_ERROR_CODES = 6000..6999

/** What this ExoPlayer error means for the user; never null, so a code never becomes the title. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.playerErrorKind(): PlayerErrorKind {
    findInvalidResponseCodeException()?.let { response ->
        playerErrorKindForHttpStatus(response.responseCode)?.let { return it }
    }
    if (findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null) {
        return PlayerErrorKind.NOT_A_VIDEO
    }
    return playerErrorKindForErrorCode(errorCode)
}

/** What an mpv failure means for the user, from the HTTP status in its log or its file error. */
internal fun mpvPlayerErrorKind(httpCode: Int?, nonHttpKind: MpvNonHttpExplanationKind?): PlayerErrorKind? {
    if (httpCode != null) return playerErrorKindForHttpStatus(httpCode) ?: PlayerErrorKind.GENERIC
    return when (nonHttpKind) {
        MpvNonHttpExplanationKind.InvalidContent -> PlayerErrorKind.NOT_A_VIDEO
        MpvNonHttpExplanationKind.UnsupportedFormat -> PlayerErrorKind.UNSUPPORTED_FORMAT
        MpvNonHttpExplanationKind.OpenFailed -> PlayerErrorKind.SERVER_UNAVAILABLE
        null -> null
    }
}

/**
 * The error screen's text: either a [kind] (its headline and advice are resources) or a
 * [plainHeadline] already in plain words, plus the technical [detail], if any.
 */
internal data class PlayerErrorDisplay(
    val kind: PlayerErrorKind?,
    val plainHeadline: String?,
    val detail: String?,
)

/**
 * Splits [message] for the error screen, so a constant is never the first thing the user reads.
 *
 * With a [kind], the kind's sentence leads. The detail is the message's technical paragraphs
 * (codes, HTTP statuses, exception names); when it has none, everything after its first
 * paragraph, which is the plain explanation the kind replaces.
 *
 * Without a kind, a first paragraph in plain words leads and the rest is the detail. When even
 * the first paragraph is technical, [PlayerErrorKind.GENERIC] leads and the whole message is
 * the detail.
 */
internal fun playerErrorDisplay(message: String, kind: PlayerErrorKind?): PlayerErrorDisplay {
    val paragraphs = message.split(PARAGRAPH_BREAK)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    fun List<String>.asDetail(): String? = joinToString("\n").takeIf { it.isNotEmpty() }
    if (kind != null) {
        val technical = paragraphs.filter(::looksTechnical)
        val detail = if (technical.isNotEmpty()) technical else paragraphs.drop(1)
        return PlayerErrorDisplay(kind = kind, plainHeadline = null, detail = detail.asDetail())
    }
    val first = paragraphs.firstOrNull()
    if (first == null || looksTechnical(first)) {
        return PlayerErrorDisplay(kind = PlayerErrorKind.GENERIC, plainHeadline = null, detail = paragraphs.asDetail())
    }
    return PlayerErrorDisplay(kind = null, plainHeadline = first, detail = paragraphs.drop(1).asDetail())
}

private val PARAGRAPH_BREAK = Regex("""\n\s*\n""")
private val ERROR_CONSTANT = Regex("""\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+){2,}\b""")
private val HTTP_STATUS = Regex("""^HTTP\s+\d{3}\b""")
private val EXCEPTION_NAME = Regex("""\b\w+(?:Exception|Error)\b""")

/** True for text a user shouldn't have to read: error constants, HTTP statuses, stack names. */
internal fun looksTechnical(paragraph: String): Boolean =
    ERROR_CONSTANT.containsMatchIn(paragraph) ||
        HTTP_STATUS.containsMatchIn(paragraph) ||
        EXCEPTION_NAME.containsMatchIn(paragraph) ||
        paragraph.contains("://")

internal const val PLAYER_ERROR_HEADLINE_TAG = "player_error_headline"
internal const val PLAYER_ERROR_DETAIL_TAG = "player_error_detail"
