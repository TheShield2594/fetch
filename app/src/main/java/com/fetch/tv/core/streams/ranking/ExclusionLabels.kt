package com.fetch.tv.core.streams.ranking

import android.content.Context
import com.fetch.tv.R

/** Why Best match passed over a stream, for its card in a source list. */
fun ExclusionReason.label(context: Context, descriptor: StreamDescriptor): String = when (this) {
    ExclusionReason.ABOVE_MAX_RESOLUTION -> context.getString(R.string.exclusion_above_max_resolution)
    ExclusionReason.BELOW_MIN_RESOLUTION -> context.getString(R.string.exclusion_below_min_resolution)
    ExclusionReason.OVER_MAX_SIZE -> context.getString(R.string.exclusion_over_max_size)
    ExclusionReason.DOLBY_VISION_PROFILE_EXCLUDED -> context.getString(R.string.exclusion_dv_profile_excluded)
    ExclusionReason.DOLBY_VISION_EXCLUDED -> context.getString(R.string.exclusion_dv_excluded)
    ExclusionReason.HDR_EXCLUDED -> context.getString(R.string.exclusion_hdr_excluded)
    ExclusionReason.CAM_OR_TELESYNC -> context.getString(R.string.exclusion_cam)
    ExclusionReason.THREE_D -> context.getString(R.string.exclusion_3d)
    ExclusionReason.EXCLUDED_KEYWORD -> context.getString(R.string.exclusion_keyword)
    ExclusionReason.UNSUPPORTED_CODEC -> context.getString(R.string.exclusion_unsupported_codec, descriptor.videoCodec.label)
    ExclusionReason.UNSUPPORTED_DOLBY_VISION -> context.getString(R.string.exclusion_unsupported_dv)
    ExclusionReason.UNSUPPORTED_RESOLUTION -> context.getString(R.string.exclusion_unsupported_resolution)
    ExclusionReason.UNSUPPORTED_AUDIO -> context.getString(
        R.string.exclusion_unsupported_audio,
        descriptor.audio.minByOrNull { it.ordinal }?.label.orEmpty()
    )
    ExclusionReason.NOT_PLAYABLE -> context.getString(R.string.exclusion_not_playable)
}
