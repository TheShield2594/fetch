package com.fetch.tv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours that carry data or identity rather than the theme: subtitle presets the viewer picks,
 * the episode ratings scale, brand gradients and member badges. They stay the same in every
 * theme, so a saved subtitle colour still matches its preset and a brand still looks like itself.
 */
object FetchContentColors {
    /** Subtitle text colours offered in Settings → Playback. */
    val subtitleSettingsTextPresets = listOf(
        Color.White,
        Color(0xFFD9D9D9),
        Color.Yellow,
        Color.Cyan,
        Color.Green,
        Color.Magenta,
        Color(0xFFFF6B6B),
        Color(0xFFFFA500),
        Color(0xFF90EE90)
    )

    /** Half-transparent black, one of the subtitle background presets. */
    val subtitleBackgroundHalfBlack = Color(0x80000000)

    /** Subtitle text colours offered by the in-player pickers. */
    val subtitlePlayerTextPresets = listOf(
        Color.White,
        Color(0xFFD9D9D9),
        Color(0xFFFFD700),
        Color(0xFF00E5FF),
        Color(0xFFFF5C5C),
        Color(0xFF00FF88)
    )

    /** Subtitle outline colours offered by the in-player pickers. */
    val subtitlePlayerOutlinePresets = listOf(
        Color.Black,
        Color.White,
        Color(0xFF00E5FF),
        Color(0xFFFF5C5C)
    )

    /** The episode ratings heatmap, best to worst. */
    val ratingExceptional = Color(0xFF1DA1F3)
    val ratingGreat = Color(0xFF186A3B)
    val ratingVeryGood = Color(0xFF28B463)
    val ratingGood = Color(0xFFF4D03F)
    val ratingFair = Color(0xFFF39C12)
    val ratingPoor = Color(0xFFE85445)
    val ratingBad = Color(0xFF633974)

    /** Text on every heatmap cell except the two darkest (Great and Bad), which take white. */
    val ratingTextOnLight = Color(0xFF1D1D1F)

    /** Continue Watching badges for a new episode or a new season. */
    val newEpisodeBadge = Color(0xFF1D4ED8)
    val newSeasonBadge = Color(0xFFB45309)

    /** The badge on an episode that isn't available yet. */
    val episodeUnavailableBadge = Color(0xCC5D4037)

    /** The primary profile's star and label. */
    val primaryProfileBadge = Color(0xFFFFB300)

    /** Member badge sweeps. */
    val supporterGold = Color(0xFFD4843D)
    val supporterGoldLight = Color(0xFFFFDE90)
    val supporterPlusBlue = Color(0xFF91A8FF)
    val supporterPlusPink = Color(0xFFF08BD8)
    val supporterPlusCoral = Color(0xFFFF9B8E)

    /** Tracking provider cards (Trakt, Simkl, MDBList) and the text drawn on them. */
    val traktCardGradient = listOf(Color(0xFF7D279B), Color(0xFFD61F56), Color(0xFFF22125))
    val simklCardGradient = listOf(Color(0xFF050505), Color(0xFF292929), FetchPrimitives.neutral925)
    val mdblistCardGradient = listOf(Color(0xFF173D69), Color(0xFF225C97), Color(0xFF16385D))
    val trackingBrandButtonContent = Color(0xFF171717)
    val trackingBrandError = Color(0xFFFFDAD6)
}

/**
 * Error and state tones drawn on dark surfaces: messages, destructive buttons and banners. They
 * sit beside [FetchColorScheme.Error] so every error in the app uses the same few reds.
 */
object FetchStatusTones {
    /** Error message text on a dark surface. */
    val errorText = Color(0xFFFFB6B6)

    /** Fill of a destructive button such as Delete. */
    val errorContainer = Color(0xFF4A2323)

    /** Background of an error banner. */
    val errorBanner = Color(0xFF5A1C1C)

    /** Border of a field in an error state. */
    val errorBorder = Color(0xFFE35D5D)

    /** Fill of a toggle that is on, such as "In library". */
    val toggleOn = Color(0xFF7CFF9B)
}
