package com.fetch.tv.ui.theme

import androidx.annotation.FontRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Typography
import com.fetch.tv.R
import com.fetch.tv.domain.model.AppFont

/**
 * One weight of a variable font. Without the weight axis set, Android draws the font's default
 * instance for every weight, so SemiBold and Bold came out Regular.
 */
@OptIn(ExperimentalTextApi::class)
private fun variableFont(@FontRes resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

val DMSansFamily = FontFamily(
    variableFont(R.font.dm_sans_variable, FontWeight.Normal),
    variableFont(R.font.dm_sans_variable, FontWeight.Medium),
    variableFont(R.font.dm_sans_variable, FontWeight.SemiBold),
    variableFont(R.font.dm_sans_variable, FontWeight.Bold)
)

val InterFamily = FontFamily(
    variableFont(R.font.inter_variable, FontWeight.Normal),
    variableFont(R.font.inter_variable, FontWeight.Medium),
    variableFont(R.font.inter_variable, FontWeight.SemiBold),
    variableFont(R.font.inter_variable, FontWeight.Bold)
)

val OpenSansFamily = FontFamily(
    variableFont(R.font.opensans_variable, FontWeight.Normal),
    variableFont(R.font.opensans_variable, FontWeight.Medium),
    variableFont(R.font.opensans_variable, FontWeight.SemiBold),
    variableFont(R.font.opensans_variable, FontWeight.Bold)
)

val SchibstedGroteskFamily = FontFamily(
    variableFont(R.font.schibsted_grotesk_variable, FontWeight.Normal),
    variableFont(R.font.schibsted_grotesk_variable, FontWeight.Medium),
    variableFont(R.font.schibsted_grotesk_variable, FontWeight.SemiBold),
    variableFont(R.font.schibsted_grotesk_variable, FontWeight.Bold),
    variableFont(R.font.schibsted_grotesk_variable, FontWeight.ExtraBold)
)

fun getFontFamily(appFont: AppFont): FontFamily = when (appFont) {
    AppFont.SCHIBSTED_GROTESK -> SchibstedGroteskFamily
    AppFont.INTER -> InterFamily
    AppFont.DM_SANS -> DMSansFamily
    AppFont.OPEN_SANS -> OpenSansFamily
}

@Immutable
data class FetchTextStyleTokens(
    val headline: TextStyle,
    val sectionTitle: TextStyle,
    val cardTitle: TextStyle,
    /** A title that leads its row or panel: the card title's size in SemiBold. */
    val titleStrong: TextStyle,
    val body: TextStyle,
    val bodyCompact: TextStyle,
    val metadata: TextStyle,
    /** Stream specs ("4K · DV · Atmos · Cached"). */
    val spec: TextStyle,
    val badge: TextStyle,
    /** Tracked capitals above or beside a title, such as the Library source label. */
    val eyebrow: TextStyle,
    val button: TextStyle,
    val tab: TextStyle
)

/**
 * The scale has one style per size and weight. Material's slots that used to differ only by a
 * hair of letter spacing (bodyMedium and bodySmall; titleSmall, labelLarge and labelMedium) now
 * share one style each, so 14sp text looks the same whichever slot a screen picked.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
fun buildFetchTypography(fontFamily: FontFamily): Typography {
    // 14sp body text: secondary lines people actually read (settings subtitles, addon names,
    // episode descriptions, exclusion reasons). 28px at 1080p, not the 12sp badge floor.
    val body14 = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )
    // 14sp labels: small titles, buttons and metadata lines ("2024 · 2h 10m · 4K"), read rather
    // than glanced at.
    val label14 = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )
    return Typography(
        // Display sizes are titles over artwork: medium, not heavy, so they read as a cinema
        // title card rather than a block headline.
        displayLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 48.sp,
            lineHeight = 52.sp,
            letterSpacing = (-1).sp
        ),
        displayMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 36.sp,
            lineHeight = 42.sp,
            letterSpacing = (-0.5).sp
        ),
        displaySmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            letterSpacing = (-0.5).sp
        ),
        headlineLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.25).sp
        ),
        headlineMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            letterSpacing = 0.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            letterSpacing = 0.sp
        ),
        titleLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp
        ),
        titleSmall = label14,
        bodyLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp
        ),
        bodyMedium = body14,
        bodySmall = body14,
        labelLarge = label14,
        labelMedium = label14,
        labelSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            // 12sp is the floor for every style: 24px at 1080p. Only badges and chips sit here;
            // anything read as a sentence or a metadata line uses bodySmall or labelMedium (14sp).
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.2.sp
        )
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
val FetchTypography = buildFetchTypography(SchibstedGroteskFamily)

@OptIn(ExperimentalTvMaterial3Api::class)
fun buildFetchTextStyles(typography: Typography): FetchTextStyleTokens = FetchTextStyleTokens(
    headline = typography.headlineLarge,
    sectionTitle = typography.headlineMedium,
    cardTitle = typography.titleMedium,
    titleStrong = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    body = typography.bodyLarge,
    bodyCompact = typography.bodyMedium,
    metadata = typography.labelMedium,
    // No tabular figures: they widen the decimal point ("18 . 2 GB") and a spec line never
    // lines up in columns.
    spec = typography.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp
    ),
    badge = typography.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp
    ),
    eyebrow = typography.labelLarge.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp
    ),
    button = typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    tab = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
)

@OptIn(ExperimentalTvMaterial3Api::class)
val FetchTextStyles = buildFetchTextStyles(FetchTypography)
