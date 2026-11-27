package com.fetch.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** #798: when every catalog fails, Home shows the error with Retry, not "No catalogs". */
class HomeLoadErrorTest {
    private val none = HomeErrorState(kind = null, message = null)

    private fun after(
        hasContent: Boolean = false,
        pendingLoads: Int = 0,
        failedCatalogs: Int = 0,
        message: String? = null,
        current: HomeErrorState = none
    ) = homeErrorAfterRowsUpdate(hasContent, pendingLoads, failedCatalogs, message, current)

    @Test
    fun `every catalog failed - the load failed, with the first error's message`() {
        assertEquals(
            HomeErrorState(HomeErrorKind.LOAD_FAILED, "timeout"),
            after(failedCatalogs = 4, message = "timeout")
        )
    }

    @Test
    fun `every catalog loaded empty - no error, so Home shows the empty state`() {
        assertEquals(none, after())
    }

    @Test
    fun `loads still running keep the current state`() {
        assertEquals(none, after(pendingLoads = 1, failedCatalogs = 3, message = "timeout"))
    }

    @Test
    fun `a retry in flight keeps the error up`() {
        val failed = HomeErrorState(HomeErrorKind.LOAD_FAILED, "timeout")
        assertEquals(failed, after(pendingLoads = 1, failedCatalogs = 4, current = failed))
    }

    @Test
    fun `rows on screen clear the error`() {
        assertEquals(
            none,
            after(hasContent = true, failedCatalogs = 2, current = HomeErrorState(HomeErrorKind.LOAD_FAILED, "x"))
        )
    }

    @Test
    fun `an addon state is not replaced by a load failure`() {
        val noCatalogAddons = HomeErrorState(HomeErrorKind.NO_CATALOG_ADDONS, null)
        assertEquals(noCatalogAddons, after(failedCatalogs = 1, message = "x", current = noCatalogAddons))
    }

    @Test
    fun `a failure without a message still reports the load failure`() {
        assertEquals(HomeErrorState(HomeErrorKind.LOAD_FAILED, null), after(failedCatalogs = 1))
    }

    private val nothing = HomeDrawableContent(
        hasCatalogItems = false,
        hasCatalogRowsBesidePlaceholders = false,
        hasCollections = false,
        hasContinueWatching = false,
        hasPendingContinueWatchingRemoval = false
    )

    @Test
    fun `catalogs that loaded empty are not content, so Home reaches its empty state`() {
        assertEquals(false, nothing.any)
    }

    @Test
    fun `anything drawable keeps Home on its content`() {
        assertEquals(true, nothing.copy(hasCatalogItems = true).any)
        assertEquals(true, nothing.copy(hasCatalogRowsBesidePlaceholders = true).any)
        assertEquals(true, nothing.copy(hasCollections = true).any)
        assertEquals(true, nothing.copy(hasContinueWatching = true).any)
        assertEquals(true, nothing.copy(hasPendingContinueWatchingRemoval = true).any)
    }
}
