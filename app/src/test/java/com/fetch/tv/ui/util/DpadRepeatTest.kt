package com.fetch.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadRepeatTest {

    @Test
    fun `vertical repeats use the vertical gate`() {
        val gate = DpadRepeat.gateFor(
            isVertical = true,
            fastHorizontal = true,
            horizontalGateMs = DpadRepeat.HORIZONTAL_GATE_MS,
            verticalGateMs = DpadRepeat.VERTICAL_GATE_MS,
        )
        assertEquals(DpadRepeat.VERTICAL_GATE_MS, gate)
    }

    @Test
    fun `horizontal repeats use the horizontal gate unless fast navigation is on`() {
        assertEquals(
            DpadRepeat.HORIZONTAL_GATE_MS,
            DpadRepeat.gateFor(false, false, DpadRepeat.HORIZONTAL_GATE_MS, DpadRepeat.VERTICAL_GATE_MS),
        )
        assertEquals(
            DpadRepeat.FAST_HORIZONTAL_GATE_MS,
            DpadRepeat.gateFor(false, true, DpadRepeat.HORIZONTAL_GATE_MS, DpadRepeat.VERTICAL_GATE_MS),
        )
    }

    @Test
    fun `a repeat inside the gate is dropped and one at the gate is accepted`() {
        assertTrue(DpadRepeat.shouldDrop(sinceLastMs = 111L, gateMs = 112L))
        assertFalse(DpadRepeat.shouldDrop(sinceLastMs = 112L, gateMs = 112L))
    }

    @Test
    fun `a zero gate never drops`() {
        assertFalse(DpadRepeat.shouldDrop(sinceLastMs = 0L, gateMs = 0L))
    }
}
