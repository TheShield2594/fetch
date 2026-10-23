package com.fetch.tv.ui.screens.player

/**
 * Colors for the ExoPlayer subtitle view, plus an alpha for the view as a whole.
 *
 * Media3 draws an outline by stroking and filling the glyph in the edge color, then painting the
 * text color on top. A translucent text color lets that dark fill show through, so lowered
 * opacity turns the text grey instead of fading it (NuvioTV#3906). With an outline, the text is
 * painted opaque and the opacity moves to the whole layer, so text and outline fade together.
 */
internal data class SubtitleLayerColors(
    val text: Int,
    val background: Int,
    val outline: Int,
    val layerAlpha: Float
)

internal fun subtitleLayerColors(
    textColor: Int,
    backgroundColor: Int,
    outlineColor: Int,
    outlineEnabled: Boolean
): SubtitleLayerColors {
    val textAlpha = alphaOf(textColor)
    if (!outlineEnabled || textAlpha >= 255 || textAlpha <= 0) {
        return SubtitleLayerColors(textColor, backgroundColor, outlineColor, layerAlpha = 1f)
    }
    val layerAlpha = textAlpha / 255f
    // Undo the layer fade on the background so it keeps the opacity it was given, as far as
    // an opaque background allows.
    val backgroundAlpha = (alphaOf(backgroundColor) / layerAlpha).toInt().coerceAtMost(255)
    return SubtitleLayerColors(
        text = withAlpha(textColor, 255),
        background = withAlpha(backgroundColor, backgroundAlpha),
        outline = outlineColor,
        layerAlpha = layerAlpha
    )
}

private fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF

private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
