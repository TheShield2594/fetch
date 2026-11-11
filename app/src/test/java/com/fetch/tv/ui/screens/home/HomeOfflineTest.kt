package com.fetch.tv.ui.screens.home

import com.fetch.tv.core.network.NetworkReach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeOfflineTest {
    @Test
    fun `no network shows the offline state at once, while still loading`() {
        assertTrue(
            shouldShowHomeOffline(NetworkReach.OFFLINE, hasAnyContent = false, isLoading = true, errorKind = null)
        )
    }

    @Test
    fun `cached rows stay on screen when offline`() {
        assertFalse(
            shouldShowHomeOffline(NetworkReach.OFFLINE, hasAnyContent = true, isLoading = false, errorKind = null)
        )
    }

    @Test
    fun `missing addons keep their own state`() {
        assertFalse(
            shouldShowHomeOffline(
                NetworkReach.OFFLINE,
                hasAnyContent = false,
                isLoading = false,
                errorKind = HomeErrorKind.NO_ADDONS
            )
        )
    }

    @Test
    fun `unvalidated network waits for the load to end`() {
        assertFalse(
            shouldShowHomeOffline(NetworkReach.UNCONFIRMED, hasAnyContent = false, isLoading = true, errorKind = null)
        )
        assertTrue(
            shouldShowHomeOffline(NetworkReach.UNCONFIRMED, hasAnyContent = false, isLoading = false, errorKind = null)
        )
        assertTrue(
            shouldShowHomeOffline(
                NetworkReach.UNCONFIRMED,
                hasAnyContent = false,
                isLoading = true,
                errorKind = HomeErrorKind.LOAD_FAILED
            )
        )
    }

    @Test
    fun `online never shows the offline state`() {
        assertFalse(
            shouldShowHomeOffline(
                NetworkReach.ONLINE,
                hasAnyContent = false,
                isLoading = false,
                errorKind = HomeErrorKind.LOAD_FAILED
            )
        )
    }

    @Test
    fun `network coming back reloads an empty or failed Home`() {
        assertEquals(
            HomeReachReload.FULL,
            homeReloadOnReach(NetworkReach.OFFLINE, NetworkReach.ONLINE, hasCatalogRows = false, errorKind = null)
        )
        assertEquals(
            HomeReachReload.FULL,
            homeReloadOnReach(
                NetworkReach.UNCONFIRMED,
                NetworkReach.ONLINE,
                hasCatalogRows = true,
                errorKind = HomeErrorKind.LOAD_FAILED
            )
        )
        assertEquals(
            HomeReachReload.VISIBLE_ROWS,
            homeReloadOnReach(NetworkReach.OFFLINE, NetworkReach.ONLINE, hasCatalogRows = true, errorKind = null)
        )
    }

    @Test
    fun `first reading or a worse network reloads nothing`() {
        assertEquals(
            HomeReachReload.NONE,
            homeReloadOnReach(null, NetworkReach.ONLINE, hasCatalogRows = false, errorKind = null)
        )
        assertEquals(
            HomeReachReload.NONE,
            homeReloadOnReach(NetworkReach.ONLINE, NetworkReach.OFFLINE, hasCatalogRows = false, errorKind = null)
        )
    }
}
