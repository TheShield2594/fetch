package com.fetch.tv.core.streams.ranking

import android.content.Context
import com.fetch.tv.R

/**
 * The factor that ordered [self] and [other] in a source list's words, with the two streams'
 * values where they say something ("resolution (1080p vs 4K)"): [self]'s value first.
 */
fun RankFactor.label(context: Context, self: RankedStream, other: RankedStream): String {
    val mine = self.descriptor
    val theirs = other.descriptor
    fun versus(base: Int, a: String, b: String): String =
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) {
            context.getString(R.string.rank_factor_versus, context.getString(base), a, b)
        } else {
            context.getString(base)
        }
    return when (this) {
        RankFactor.OWN_LIBRARY -> context.getString(R.string.rank_factor_own_library)
        RankFactor.DEBRID_CACHED -> context.getString(R.string.rank_factor_debrid_cached)
        RankFactor.RESOLUTION -> versus(R.string.rank_factor_resolution, mine.resolution.label, theirs.resolution.label)
        RankFactor.PREFERRED_CODEC -> versus(R.string.rank_factor_codec, mine.videoCodec.label, theirs.videoCodec.label)
        RankFactor.HDR -> context.getString(R.string.rank_factor_hdr)
        RankFactor.AUDIO -> versus(
            R.string.rank_factor_audio,
            mine.audio.minByOrNull { it.ordinal }?.label.orEmpty(),
            theirs.audio.minByOrNull { it.ordinal }?.label.orEmpty()
        )
        RankFactor.PREFERRED_KEYWORD -> context.getString(R.string.rank_factor_keyword)
        RankFactor.LANGUAGE -> context.getString(R.string.rank_factor_language)
        RankFactor.PREFERRED_RELEASE_GROUP -> context.getString(R.string.rank_factor_release_group)
        RankFactor.PREFERRED_ADDON -> context.getString(R.string.rank_factor_addon)
        RankFactor.RELEASE_SOURCE -> versus(R.string.rank_factor_release_source, mine.source.label, theirs.source.label)
        RankFactor.SIZE -> versus(
            R.string.rank_factor_size,
            mine.sizeBytes?.let(StreamDescriptor::formatSize).orEmpty(),
            theirs.sizeBytes?.let(StreamDescriptor::formatSize).orEmpty()
        )
        RankFactor.SEEDERS -> versus(
            R.string.rank_factor_seeders,
            mine.seeders?.toString().orEmpty(),
            theirs.seeders?.toString().orEmpty()
        )
        RankFactor.ADDON_ORDER -> context.getString(R.string.rank_factor_addon_order)
    }
}

/**
 * The one line a source row shows about its place in Best match's order: why the pick leads
 * ([isFirst]), or what put this row ahead of or behind the eligible row shown above it.
 */
fun RankComparison.rowLine(context: Context, self: RankedStream, isFirst: Boolean): String {
    val other = if (ahead === self) behind else ahead
    val factorText = factor.label(context, self, other)
    return when {
        isFirst -> context.getString(R.string.source_why_pick, factorText)
        ahead === self -> context.getString(R.string.source_why_ahead, factorText)
        else -> context.getString(R.string.source_why_behind, factorText)
    }
}

/**
 * What Best match believes about the screen and the audio chain, as it ranked with them:
 * "Your setup: up to 4K · HDR10, DV · HEVC, AV1 · passthrough TrueHD, DD+".
 */
fun DeviceDecodeCapabilities.setupSummary(context: Context): String {
    val hdr = buildSet {
        addAll(hdrFormats)
        if (dolbyVisionProfiles.isNotEmpty()) add(HdrFormat.DOLBY_VISION)
    }.sortedBy { it.ordinal }
    val codecs = decodableCodecs.filter { it != VideoCodec.UNKNOWN }.sortedBy { it.ordinal }
    val passthrough = passthroughAudio.sortedBy { it.ordinal }
    val parts = listOfNotNull(
        maxResolution.label.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.source_setup_max_resolution, it) },
        if (hdr.isEmpty()) {
            context.getString(R.string.source_setup_sdr)
        } else {
            hdr.joinToString(", ") { it.label }
        },
        codecs.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label },
        if (passthrough.isEmpty()) {
            context.getString(R.string.source_setup_no_passthrough)
        } else {
            context.getString(R.string.source_setup_passthrough, passthrough.joinToString(", ") { it.label })
        }
    )
    return context.getString(R.string.source_setup, parts.joinToString(" · "))
}
