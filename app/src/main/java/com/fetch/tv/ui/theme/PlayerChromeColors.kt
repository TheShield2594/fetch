package com.fetch.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colours for chrome drawn over video: the controls, the title block, overlays and side-panel
 * scrims. Video can be any colour, so these are white and black at set strengths rather than
 * theme surfaces; the theme still owns the accent (progress, primary actions) and focus.
 *
 * Before these existed the player used about thirty different white and black alphas for the
 * same handful of jobs. Each job now has one value, and "Increase contrast" lifts the text and
 * deepens the scrims.
 */
@Immutable
class FetchPlayerChromeColors internal constructor(highContrast: Boolean) {
    private val white = FetchPrimitives.white
    private val black = FetchPrimitives.black

    /** Titles, times, focused labels. */
    val TextPrimary: Color = white
    /** The episode line, panel values, anything read but not leading. */
    val TextSecondary: Color = white.copy(alpha = if (highContrast) 0.95f else 0.86f)
    /** Hints, "via" lines, captions under controls. */
    val TextTertiary: Color = white.copy(alpha = if (highContrast) 0.86f else 0.70f)
    /** Disabled text and icons. */
    val TextDisabled: Color = white.copy(alpha = 0.45f)

    /** An unfocused control's fill: visible on bright frames, quiet on dark ones. */
    val Control: Color = white.copy(alpha = if (highContrast) 0.20f else 0.12f)
    /** A selected-but-unfocused row in a player list (current audio track). */
    val ControlSelected: Color = white.copy(alpha = 0.22f)
    /** Hairlines and dividers inside player panels. */
    val Divider: Color = white.copy(alpha = 0.15f)

    /** A focused control's fill and its content: focus is white everywhere, video included. */
    val Focus: Color = white
    val OnFocus: Color = black

    /** Progress and slider tracks at rest, and the track while its control has focus. */
    val Track: Color = white.copy(alpha = 0.30f)
    val TrackFocused: Color = white.copy(alpha = 0.45f)

    /** Behind the controls, at the edge of the frame where text sits. */
    val ScrimStrong: Color = black.copy(alpha = if (highContrast) 0.92f else 0.85f)
    /** Overlays and dialogs that sit over playing video. */
    val Scrim: Color = black.copy(alpha = if (highContrast) 0.85f else 0.72f)
    /** Dims the frame behind a side panel without hiding it. */
    val ScrimLight: Color = black.copy(alpha = if (highContrast) 0.6f else 0.45f)
}
