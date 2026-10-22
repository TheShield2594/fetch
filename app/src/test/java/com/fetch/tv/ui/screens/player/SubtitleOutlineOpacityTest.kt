package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleOutlineOpacityTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val transparent = 0x00000000

    @Test
    fun `opaque text keeps its colors and a fully visible layer`() {
        val colors = subtitleLayerColors(white, transparent, black, outlineEnabled = true)
        assertEquals(SubtitleLayerColors(white, transparent, black, 1f), colors)
    }

    @Test
    fun `translucent text without an outline is left to the painter`() {
        val text = 0x66FFFFFF
        val colors = subtitleLayerColors(text, transparent, black, outlineEnabled = false)
        assertEquals(SubtitleLayerColors(text, transparent, black, 1f), colors)
    }

    @Test
    fun `translucent outlined text is painted opaque and the layer carries the opacity`() {
        val colors = subtitleLayerColors(0x66FFFFFF, transparent, black, outlineEnabled = true)
        assertEquals(white, colors.text)
        assertEquals(black, colors.outline)
        assertEquals(transparent, colors.background)
        assertEquals(0x66 / 255f, colors.layerAlpha, 0.0001f)
    }

    @Test
    fun `background keeps its own opacity under the faded layer`() {
        // 40% text over a 20% background: the layer fades by 0.4, so the background is raised to 50%.
        val colors = subtitleLayerColors(0x66FFFFFF, 0x33000000, black, outlineEnabled = true)
        assertEquals(0x7F, (colors.background ushr 24) and 0xFF)
    }

    @Test
    fun `background more opaque than the text is capped at fully opaque`() {
        val colors = subtitleLayerColors(0x66FFFFFF, 0xCC000000.toInt(), black, outlineEnabled = true)
        assertEquals(black, colors.background)
    }
}
