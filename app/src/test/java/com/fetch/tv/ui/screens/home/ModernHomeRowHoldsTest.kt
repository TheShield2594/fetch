package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.PLACEHOLDER_IMAGE_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomeRowHoldsTest {

    private val neverAsked: () -> Boolean = { throw AssertionError("isActiveRow should not be read") }

    @Test
    fun `a row that starts with real cards never pins or follows focus`() {
        val pin = FirstCardPin(pinned = false).next(
            firstIsPlaceholder = false,
            firstItemKey = "a",
            isActiveRow = neverAsked
        )

        assertEquals(FirstCardPin(pinned = false), pin)
    }

    @Test
    fun `the pin is lent to the card that replaces the placeholder while the ring is on the row`() {
        val loading = FirstCardPin(pinned = true).next(true, "placeholder", neverAsked)
        val loaded = loading.next(firstIsPlaceholder = false, firstItemKey = "a", isActiveRow = { true })

        assertTrue(loaded.pinned)
        assertEquals("a", loaded.itemKey)
        // Lent to the card, not the slot: a card moving into slot 0 does not take it.
        assertEquals("a", loaded.next(false, "b") { true }.itemKey)
    }

    @Test
    fun `the pin is released once the ring leaves the row, and not armed again`() {
        val loaded = FirstCardPin(pinned = true, itemKey = "a")
        val released = loaded.next(firstIsPlaceholder = false, firstItemKey = "a", isActiveRow = { false })

        assertEquals(FirstCardPin(pinned = false, spent = true, itemKey = null), released)
        assertFalse(released.next(firstIsPlaceholder = true, firstItemKey = "p", isActiveRow = neverAsked).pinned)
    }

    @Test
    fun `a lazily loaded row arms the pin when its placeholder arrives`() {
        val pin = FirstCardPin(pinned = false).next(firstIsPlaceholder = true, firstItemKey = "p", isActiveRow = neverAsked)

        assertTrue(pin.pinned)
        assertNull(pin.itemKey)
    }

    @Test
    fun `a card's logo starts from the data layer's frozen value`() {
        assertEquals("frozen", initialCardLogoUrl("frozen", "logo"))
        assertEquals("logo", initialCardLogoUrl(PLACEHOLDER_IMAGE_URL, "logo"))
        assertNull(initialCardLogoUrl(null, PLACEHOLDER_IMAGE_URL))
    }

    @Test
    fun `a missing logo takes the item's logo`() {
        assertEquals("logo", resolveCardLogoUrl(null, "logo", null, useLandscapeOverlayTreatment = true))
        assertEquals("logo", resolveCardLogoUrl(PLACEHOLDER_IMAGE_URL, "logo", null, useLandscapeOverlayTreatment = true))
    }

    @Test
    fun `the enriched logo replaces the logo outside landscape`() {
        assertEquals("enriched", resolveCardLogoUrl("logo", "logo", "enriched", useLandscapeOverlayTreatment = false))
    }

    @Test
    fun `in landscape the logo stays frozen unless there was none`() {
        assertEquals("logo", resolveCardLogoUrl("logo", "logo", "enriched", useLandscapeOverlayTreatment = true))
        assertEquals("enriched", resolveCardLogoUrl(null, null, "enriched", useLandscapeOverlayTreatment = true))
    }

    @Test
    fun `the backdrop is frozen in landscape and follows enrichment outside it`() {
        assertEquals("backdrop", resolveCardBackdropUrl("backdrop", "backdrop", "enriched", useLandscapeOverlayTreatment = true))
        assertEquals("enriched", resolveCardBackdropUrl("backdrop", "backdrop", "enriched", useLandscapeOverlayTreatment = false))
        assertEquals("backdrop", resolveCardBackdropUrl(PLACEHOLDER_IMAGE_URL, "backdrop", null, useLandscapeOverlayTreatment = true))
        assertEquals("frozen", initialCardBackdropUrl("frozen", "backdrop"))
    }
}
