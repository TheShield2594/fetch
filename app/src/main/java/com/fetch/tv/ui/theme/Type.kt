package com.fetch.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Typography
import com.fetch.tv.R
import com.fetch.tv.domain.model.AppFont

val DMSansFamily = FontFamily(
    Font(R.font.dm_sans_variable, FontWeight.Normal),
    Font(R.font.dm_sans_variable, FontWeight.Medium),
    Font(R.font.dm_sans_variable, FontWeight.SemiBold),
    Font(R.font.dm_sans_variable, FontWeight.Bold)
)

val InterFamily = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal),
    Font(R.font.inter_variable, FontWeight.Medium),
    Font(R.font.inter_variable, FontWeight.SemiBold),
    Font(R.font.inter_variable, FontWeight.Bold)
)

val OpenSansFamily = FontFamily(
    Font(R.font.opensans_variable, FontWeight.Normal),
    Font(R.font.opensans_variable, FontWeight.Medium),
    Font(R.font.opensans_variable, FontWeight.SemiBold),
    Font(R.font.opensans_variable, FontWeight.Bold)
)

val SchibstedGroteskFamily = FontFamily(
    Font(R.font.schibsted_grotesk_variable, FontWeight.Normal),
    Font(R.font.schibsted_grotesk_variable, FontWeight.Medium),
    Font(R.font.schibsted_grotesk_variable, FontWeight.SemiBold),
    Font(R.font.schibsted_grotesk_variable, FontWeight.Bold),
    Font(R.font.schibsted_grotesk_variable, FontWeight.ExtraBold)
)

/** Fetch's display face: titles, hero names and section headings. */
val BricolageGrotesqueFamily = FontFamily(
    Font(R.font.bricolage_grotesque_variable, FontWeight.Medium),
    Font(R.font.bricolage_grotesque_variable, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_variable, FontWeight.Bold),
    Font(R.font.bricolage_grotesque_variable, FontWeight.ExtraBold)
)

fun getFontFamily(appFont: AppFont): FontFamily = when (appFont) {
    AppFont.SCHIBSTED_GROTESK -> SchibstedGroteskFamily
    AppFont.INTER -> InterFamily
    AppFont.DM_SANS -> DMSansFamily
    AppFont.OPEN_SANS -> OpenSansFamily
}

/**
 * The Fetch font pairs Schibsted Grotesk text with Bricolage Grotesque headings. Picking another
 * font in settings uses that font for headings too.
 */
fun getDisplayFontFamily(appFont: AppFont): FontFamily = when (appFont) {
    AppFont.SCHIBSTED_GROTESK -> BricolageGrotesqueFamily
    else -> getFontFamily(appFont)
}

@Immutable
data class FetchTextStyleTokens(
    val display: TextStyle,
    val displayCompact: TextStyle,
    val headline: TextStyle,
    val sectionTitle: TextStyle,
    val cardTitle: TextStyle,
    val body: TextStyle,
    val bodyCompact: TextStyle,
    val metadata: TextStyle,
    /** Stream specs ("4K · DV · Atmos · Cached"). */
    val spec: TextStyle,
    val badge: TextStyle,
    val button: TextStyle,
    val tab: TextStyle,
    val nav: TextStyle,
    val playerControl: TextStyle
)

@OptIn(ExperimentalTvMaterial3Api::class)
fun buildFetchTypography(
    fontFamily: FontFamily,
    displayFontFamily: FontFamily = fontFamily
): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = (-1).sp
    ),
    displayMedium = TextStyle(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp
    ),
    displaySmall = TextStyle(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = displayFontFamily,
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
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

@OptIn(ExperimentalTvMaterial3Api::class)
val FetchTypography = buildFetchTypography(SchibstedGroteskFamily, BricolageGrotesqueFamily)

@OptIn(ExperimentalTvMaterial3Api::class)
fun buildFetchTextStyles(typography: Typography): FetchTextStyleTokens = FetchTextStyleTokens(
    display = typography.displayLarge,
    displayCompact = typography.displayMedium,
    headline = typography.headlineLarge,
    sectionTitle = typography.headlineMedium,
    cardTitle = typography.titleMedium,
    body = typography.bodyLarge,
    bodyCompact = typography.bodyMedium,
    metadata = typography.labelMedium,
    // No tabular figures: they widen the decimal point ("18 . 2 GB") and a spec line never
    // lines up in columns.
    spec = typography.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp
    ),
    badge = typography.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp
    ),
    button = typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    tab = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    nav = typography.titleMedium,
    playerControl = typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
)

@OptIn(ExperimentalTvMaterial3Api::class)
val FetchTextStyles = buildFetchTextStyles(FetchTypography)
