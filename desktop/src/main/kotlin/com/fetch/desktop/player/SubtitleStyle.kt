package com.fetch.desktop.player

import com.fetch.desktop.data.PlayerSettings
import java.util.Locale

/**
 * The subtitle style as mpv properties, for the built-in player's live style panel: size, bold,
 * color and opacity, outline, background and position, with the TV's HDR override (another text
 * color, every color dimmed) on HDR video and its lift above the controls. Pure, so it's testable.
 */
object SubtitleStyle {

    /** The TV's in-player text colors (SubtitleColorPresets). */
    val TextPresets = listOf("#FFFFFF", "#D9D9D9", "#FFD700", "#00E5FF", "#FF5C5C", "#00FF88")

    /** The TV's in-player outline colors. */
    val OutlinePresets = listOf("#000000", "#FFFFFF", "#00E5FF", "#FF5C5C")

    /** The size steps of the panel: 50 % to 200 %, in tens, as on TV. */
    const val MIN_SCALE = 0.5
    const val MAX_SCALE = 2.0

    /** mpv's `sub-pos` range Fetch uses: 100 is the bottom of the picture. */
    const val MIN_POSITION = 40
    const val MAX_POSITION = 100

    /** The background box's color when on: black at 60 %. */
    const val BACKGROUND = "#99000000"

    /** Whether mpv's `video-params/gamma` says the video is HDR (PQ: HDR10, HDR10+, Dolby Vision; HLG). */
    fun isHdr(gamma: String?, primaries: String? = null): Boolean {
        val g = gamma?.lowercase()?.trim() ?: return false
        if (g == "pq" || g == "hlg" || g.contains("st2084") || g.contains("smpte2084") || g.contains("arib-std-b67")) return true
        // Dolby Vision without an HDR10 base layer reports its own transfer on some builds.
        return primaries?.lowercase()?.contains("2020") == true && g.contains("dolbyvision")
    }

    /** The TV's HDR brightness: 0–100 onto 30–100 % of full, so 0 still leaves readable text. */
    fun hdrFactor(brightness: Int): Double = (0.7 * brightness.coerceIn(0, 100) + 30.0) / 100.0

    /**
     * Every mpv property the style sets, as absolute values, for [settings] on HDR video or not.
     * [liftPercent] moves unpositioned subtitles up (above the controls or the Skip button).
     */
    fun properties(settings: PlayerSettings, hdr: Boolean = false, liftPercent: Int = 0): List<Pair<String, String>> {
        val extras = settings.extras
        val hdrStyle = hdr && extras.subtitleHdrStyle
        val factor = if (hdrStyle) hdrFactor(extras.subtitleHdrBrightness) else 1.0
        val text = Argb.parse(if (hdrStyle) extras.subtitleHdrColor else settings.subtitleColor) ?: Argb.WHITE
        val textAlpha = (text ushr 24) and 0xFF
        val outline = Argb.parse(settings.subtitleOutlineColor) ?: Argb.BLACK
        // With an outline, a lowered opacity fades text and outline together, as on TV.
        val outlineColor = if (textAlpha < 255) Argb.withAlpha(outline, textAlpha) else outline
        val position = (settings.subtitlePosition - liftPercent).coerceIn(0, 150)
        return buildList {
            add("sub-scale" to fmt(settings.subtitleScale.coerceIn(MIN_SCALE, MAX_SCALE)))
            add("sub-bold" to if (settings.subtitleBold) "yes" else "no")
            add("sub-color" to Argb.format(Argb.scale(text, factor)))
            if (settings.subtitleBackground) {
                add("sub-border-style" to "opaque-box")
                add("sub-back-color" to Argb.format(Argb.scale(Argb.parse(BACKGROUND)!!, factor)))
                add("sub-border-size" to "3")
                add("sub-border-color" to Argb.format(Argb.scale(Argb.parse(BACKGROUND)!!, factor)))
            } else {
                add("sub-border-style" to "outline-and-shadow")
                add("sub-back-color" to "#00000000")
                add("sub-border-size" to if (settings.subtitleOutline) fmt(settings.subtitleOutlineSize) else "0")
                add("sub-border-color" to Argb.format(Argb.scale(outlineColor, factor)))
            }
            add("sub-pos" to position.toString())
        }
    }

    /** Text opacity in percent, from a "#AARRGGBB" or "#RRGGBB" color. */
    fun opacityPercent(color: String): Int = (((Argb.parse(color) ?: Argb.WHITE) ushr 24) and 0xFF).let { Math.round(it * 100f / 255f) }

    /** [color] with [percent] opacity, written as "#AARRGGBB" (or "#RRGGBB" when opaque). */
    fun withOpacity(color: String, percent: Int): String {
        val argb = Argb.parse(color) ?: Argb.WHITE
        val alpha = Math.round(percent.coerceIn(0, 100) * 255f / 100f)
        return Argb.format(Argb.withAlpha(argb, alpha))
    }

    /** [preset] keeping the opacity [current] has. */
    fun keepOpacity(preset: String, current: String): String = withOpacity(preset, opacityPercent(current))

    /**
     * How far to lift subtitles above the player's bottom UI, in percent of the view (the TV's
     * SubtitleLiftRules): nothing while the style panel previews the configured position.
     */
    fun liftPercent(
        previewConfiguredPosition: Boolean,
        viewHeightPx: Float,
        controlsVisible: Boolean,
        controlsClearancePx: Float,
        skipVisible: Boolean,
        skipClearancePx: Float
    ): Int {
        if (previewConfiguredPosition || viewHeightPx <= 0f) return 0
        val px = maxOf(if (controlsVisible) controlsClearancePx else 0f, if (skipVisible) skipClearancePx else 0f)
        return Math.round(px / viewHeightPx * 100f).coerceIn(0, 60)
    }

    /**
     * The same style for text Fetch draws itself (YouTube captions, as the TV's caption
     * renderer draws them): colours as ARGB, the outline or background box, and how far up from
     * the bottom of the view the text sits, in percent.
     */
    fun captionLook(settings: PlayerSettings, hdr: Boolean = false, liftPercent: Int = 0): CaptionLook {
        val extras = settings.extras
        val hdrStyle = hdr && extras.subtitleHdrStyle
        val factor = if (hdrStyle) hdrFactor(extras.subtitleHdrBrightness) else 1.0
        val text = Argb.parse(if (hdrStyle) extras.subtitleHdrColor else settings.subtitleColor) ?: Argb.WHITE
        val textAlpha = (text ushr 24) and 0xFF
        val outline = Argb.parse(settings.subtitleOutlineColor) ?: Argb.BLACK
        val outlineColor = if (textAlpha < 255) Argb.withAlpha(outline, textAlpha) else outline
        val background = settings.subtitleBackground
        return CaptionLook(
            scale = settings.subtitleScale.coerceIn(MIN_SCALE, MAX_SCALE).toFloat(),
            bold = settings.subtitleBold,
            textArgb = Argb.scale(text, factor),
            outlineArgb = if (!background && settings.subtitleOutline && settings.subtitleOutlineSize > 0) Argb.scale(outlineColor, factor) else null,
            outlineSize = settings.subtitleOutlineSize.toFloat(),
            backgroundArgb = if (background) Argb.scale(Argb.parse(BACKGROUND)!!, factor) else null,
            bottomPercent = (100 - (settings.subtitlePosition - liftPercent)).coerceIn(0, 100)
        )
    }

    private fun fmt(v: Double) = "%.2f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')
}

/** [SubtitleStyle.captionLook]: subtitle text Fetch draws, in the player's style. */
data class CaptionLook(
    /** Times mpv's default size. */
    val scale: Float,
    val bold: Boolean,
    val textArgb: Int,
    /** The outline's colour, or null for none. */
    val outlineArgb: Int?,
    /** mpv's `sub-border-size`, in its scaled pixels (a 720-line view). */
    val outlineSize: Float,
    /** The box behind the text, or null for none. */
    val backgroundArgb: Int?,
    /** The text's bottom edge above the view's, in percent of its height (mpv's sub-pos, turned over). */
    val bottomPercent: Int
)

/** Colors as ARGB ints, read from and written as mpv's "#RRGGBB" / "#AARRGGBB". */
object Argb {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()

    fun parse(text: String?): Int? {
        val hex = text?.trim()?.removePrefix("#") ?: return null
        val value = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> (0xFF000000L or value).toInt()
            8 -> value.toInt()
            else -> null
        }
    }

    fun format(argb: Int): String {
        val alpha = (argb ushr 24) and 0xFF
        return if (alpha == 0xFF) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb.toLong() and 0xFFFFFFFFL)
    }

    fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** Every channel but alpha times [factor] (the TV's HDR dimming). */
    fun scale(argb: Int, factor: Double): Int {
        if (factor == 1.0) return argb
        val a = (argb ushr 24) and 0xFF
        val r = (((argb shr 16) and 0xFF) * factor).toInt()
        val g = (((argb shr 8) and 0xFF) * factor).toInt()
        val b = ((argb and 0xFF) * factor).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
