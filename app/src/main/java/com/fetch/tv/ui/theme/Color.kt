package com.fetch.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

@Immutable
data class FetchSurfaceColors(
    val background: Color,
    val raised: Color,
    val card: Color,
    val default: Color,
    val variant: Color,
    val panel: Color,
    val overlay: Color,
    val field: Color,
    val menu: Color,
    val modal: Color,
    val playerOverlay: Color,
    val divider: Color
)

@Immutable
data class FetchTextColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val disabled: Color,
    val inverse: Color,
    val onAccent: Color,
    val onOverlay: Color,
    val metadata: Color
)

@Immutable
data class FetchFocusColors(
    val ring: Color,
    val background: Color,
    val content: Color,
    val accent: Color,
    val scrim: Color
)

@Immutable
data class FetchSelectionColors(
    val background: Color,
    val foreground: Color,
    val border: Color,
    val mutedBackground: Color,
    val mutedForeground: Color
)

@Immutable
data class FetchMediaColors(
    val heroScrim: Color,
    val imageScrim: Color,
    val posterFallback: Color,
    val videoControlsScrim: Color,
    val glassPanelTop: Color,
    val glassPanelMiddle: Color,
    val glassPanelBottom: Color,
    val glow: Color
)

@Immutable
data class FetchStatusColors(
    val rating: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
    val info: Color,
    val watched: Color,
    val unwatched: Color,
    val cached: Color,
    val torrent: Color,
    val premium: Color
)

@Immutable
data class FetchDisabledColors(
    val container: Color,
    val content: Color,
    val border: Color,
    val overlay: Color
)

@Immutable
data class FetchSourceColors(
    val trakt: Color,
    val tmdb: Color,
    val imdb: Color,
    val mdblist: Color
)

@Immutable
data class FetchContrastPair(
    val foreground: Color,
    val background: Color,
    /** Names the pair in ThemeContrastTest's report. */
    val label: String = ""
)

/**
 * @param highContrast the "Increase contrast" setting: secondary and tertiary text, borders,
 * dividers and the focus ring move to brighter neutrals, and scrims behind text get darker.
 */
class FetchColorScheme(
    palette: ThemeColorPalette,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false,
    highContrast: Boolean = false
) {
    private val pureBlack = FetchPrimitives.black
    private val pureBlackSurfaces = amoledMode && amoledSurfacesMode

    val Background = if (amoledMode) pureBlack else palette.background
    val BackgroundElevated = if (pureBlackSurfaces) pureBlack else palette.backgroundElevated
    val BackgroundCard = if (pureBlackSurfaces) pureBlack else palette.backgroundCard
    val Surface = if (pureBlackSurfaces) pureBlack else palette.surface
    val SurfaceVariant = if (pureBlackSurfaces) pureBlack else palette.surfaceVariant
    val Panel = if (pureBlackSurfaces) pureBlack else palette.panel
    val Overlay = palette.overlay
    val Field = if (pureBlackSurfaces) pureBlack else palette.field
    val Menu = if (pureBlackSurfaces) pureBlack else palette.menu
    val Modal = if (pureBlackSurfaces) pureBlack else palette.modal
    val PlayerOverlay = palette.playerOverlay
    val Divider = if (highContrast) FetchPrimitives.neutral600 else FetchPrimitives.neutral750

    val Primary = FetchPrimitives.neutral500
    val PrimaryVariant = FetchPrimitives.neutral650
    val OnPrimary = FetchPrimitives.white
    val Secondary = palette.secondary
    val SecondaryVariant = palette.secondaryVariant
    val OnSecondary = palette.onSecondary
    val OnSecondaryVariant = palette.onSecondaryVariant

    val TextPrimary = FetchPrimitives.white
    val TextSecondary = if (highContrast) FetchPrimitives.neutral200 else FetchPrimitives.neutral400
    val TextTertiary = if (highContrast) FetchPrimitives.neutral400 else FetchPrimitives.neutral450
    val TextDisabled = FetchPrimitives.neutral700
    val TextInverse = FetchPrimitives.neutral925

    val FocusRing = if (highContrast) FetchPrimitives.white else FetchPrimitives.neutral100
    val FocusBackground = palette.focusBackground
    val FocusContent = FetchPrimitives.white

    /**
     * Neutral fill for a focused or selected control that isn't the screen's primary action
     * (tabs, filter chips, the current sidebar page). The accent stays for primary actions.
     */
    val Highlight = FetchPrimitives.white
    val OnHighlight = FetchPrimitives.neutral925
    val FocusScrim = FetchPrimitives.black.copy(alpha = 0.32f)

    val Rating = FetchPrimitives.rating
    val Error = FetchPrimitives.error
    val Warning = FetchPrimitives.warning
    val Success = FetchPrimitives.success
    val Info = FetchPrimitives.info
    val Watched = FetchPrimitives.success
    val Unwatched = FetchPrimitives.neutral600
    val Cached = FetchPrimitives.blue300
    val Torrent = FetchPrimitives.torrent
    val Premium = FetchPrimitives.premium

    val Border = if (highContrast) FetchPrimitives.neutral600 else FetchPrimitives.neutral750
    val BorderFocused = FocusRing
    val BorderMuted = Border.copy(alpha = 0.58f)

    val Scrim = FetchPrimitives.black.copy(alpha = if (highContrast) 0.78f else 0.62f)
    val ImageScrim = FetchPrimitives.black.copy(alpha = if (highContrast) 0.74f else 0.58f)
    val VideoControlsScrim = FetchPrimitives.black.copy(alpha = 0.72f)
    val PosterFallback = BackgroundCard

    val DisabledContainer = SurfaceVariant.copy(alpha = 0.42f)
    val DisabledContent = TextDisabled
    val DisabledBorder = Border.copy(alpha = 0.48f)
    val DisabledOverlay = FetchPrimitives.black.copy(alpha = 0.42f)

    val surfaces = FetchSurfaceColors(
        background = Background,
        raised = BackgroundElevated,
        card = BackgroundCard,
        default = Surface,
        variant = SurfaceVariant,
        panel = Panel,
        overlay = Overlay,
        field = Field,
        menu = Menu,
        modal = Modal,
        playerOverlay = PlayerOverlay,
        divider = Divider
    )

    val text = FetchTextColors(
        primary = TextPrimary,
        secondary = TextSecondary,
        tertiary = TextTertiary,
        disabled = TextDisabled,
        inverse = TextInverse,
        onAccent = OnSecondary,
        onOverlay = FetchPrimitives.white,
        metadata = TextSecondary
    )

    val focus = FetchFocusColors(
        ring = FocusRing,
        background = FocusBackground,
        content = FocusContent,
        accent = Secondary,
        scrim = FocusScrim
    )

    val selection = FetchSelectionColors(
        background = Secondary,
        foreground = OnSecondary,
        border = SecondaryVariant,
        mutedBackground = FocusBackground,
        mutedForeground = TextPrimary
    )

    val media = FetchMediaColors(
        heroScrim = Scrim,
        imageScrim = ImageScrim,
        posterFallback = PosterFallback,
        videoControlsScrim = VideoControlsScrim,
        glassPanelTop = Color(0xD64A4F59),
        glassPanelMiddle = Color(0xCC3F454F),
        glassPanelBottom = Color(0xC640474F),
        glow = FocusRing.copy(alpha = 0.32f)
    )

    val status = FetchStatusColors(
        rating = Rating,
        error = Error,
        warning = Warning,
        success = Success,
        info = Info,
        watched = Watched,
        unwatched = Unwatched,
        cached = Cached,
        torrent = Torrent,
        premium = Premium
    )

    val disabled = FetchDisabledColors(
        container = DisabledContainer,
        content = DisabledContent,
        border = DisabledBorder,
        overlay = DisabledOverlay
    )

    val source = FetchSourceColors(
        trakt = FetchPrimitives.trakt,
        tmdb = FetchPrimitives.tmdb,
        imdb = FetchPrimitives.imdb,
        mdblist = FetchPrimitives.mdblist
    )

    /**
     * Every text colour on every surface it is drawn on. ThemeContrastTest holds each to WCAG AA
     * (4.5:1) in every built-in theme; translucent surfaces are measured over [Background].
     */
    val contrastPairs: List<FetchContrastPair> by lazy(LazyThreadSafetyMode.NONE) {
        buildList {
            val surfacesByName = listOf(
                "background" to Background,
                "elevated" to BackgroundElevated,
                "card" to BackgroundCard,
                "surface" to Surface,
                "surfaceVariant" to SurfaceVariant,
                "panel" to Panel,
                "field" to Field,
                "menu" to Menu,
                "modal" to Modal,
                "overlay" to Overlay,
                "playerOverlay" to PlayerOverlay,
                "focusBackground" to FocusBackground
            )
            val textByName = listOf(
                "textPrimary" to TextPrimary,
                "textSecondary" to TextSecondary,
                "textTertiary" to TextTertiary,
                "error" to Error,
                "warning" to Warning,
                "success" to Success
            )
            textByName.forEach { (textName, text) ->
                surfacesByName.forEach { (surfaceName, surface) ->
                    add(FetchContrastPair(text, surface, "$textName on $surfaceName"))
                }
            }
            add(FetchContrastPair(OnSecondary, Secondary, "onSecondary on secondary"))
            add(FetchContrastPair(OnSecondaryVariant, SecondaryVariant, "onSecondaryVariant on secondaryVariant"))
            add(FetchContrastPair(FocusContent, FocusBackground, "focusContent on focusBackground"))
            add(FetchContrastPair(OnHighlight, Highlight, "onHighlight on highlight"))
        }
    }
}

object FetchColors {
    val Background: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.Background

    val BackgroundElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.BackgroundElevated

    val BackgroundCard: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.BackgroundCard

    val Surface: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.Surface

    val SurfaceVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.SurfaceVariant

    val Primary = FetchPrimitives.neutral500
    val PrimaryVariant = FetchPrimitives.neutral650
    val OnPrimary = FetchPrimitives.white
    val TextPrimary = FetchPrimitives.white
    val TextSecondary = FetchPrimitives.neutral400
    val TextTertiary = FetchPrimitives.neutral450
    val TextDisabled = FetchPrimitives.neutral700
    val Rating = FetchPrimitives.rating
    val Error = FetchPrimitives.error
    val Success = FetchPrimitives.success
    val Warning = FetchPrimitives.warning
    val Info = FetchPrimitives.info
    val Border = FetchPrimitives.neutral750

    val Secondary: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.Secondary

    val SecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.SecondaryVariant

    val OnSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.OnSecondary

    val OnSecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.OnSecondaryVariant

    val FocusRing: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.FocusRing

    val FocusBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.FocusBackground

    val BorderFocused: Color
        @Composable
        @ReadOnlyComposable
        get() = FetchTheme.colors.BorderFocused
}
