package com.fetch.tv.core.tracking

import com.fetch.tv.data.local.WatchProgressSource
import com.fetch.tv.domain.model.LibrarySourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingSourcesTest {
    @Test
    fun `MDBList is available independently for watch and library sources`() {
        val connected = setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL, TrackingProviderId.MDBLIST)
        assertEquals(WatchProgressSource.MDBLIST, WatchProgressSource.fromStorage("MDBLIST"))
        assertEquals(TrackingProviderId.MDBLIST, WatchProgressSource.MDBLIST.providerId)
        assertEquals(
            listOf(WatchProgressSource.FETCH_SYNC, WatchProgressSource.TRAKT, WatchProgressSource.SIMKL, WatchProgressSource.MDBLIST),
            availableWatchProgressSources(connected)
        )
        assertEquals(
            listOf(LibrarySourceMode.LOCAL, LibrarySourceMode.TRAKT, LibrarySourceMode.SIMKL, LibrarySourceMode.MDBLIST),
            availableLibrarySourceModes(connected)
        )
        val selection = TrackingSourceSelection(WatchProgressSource.MDBLIST, LibrarySourceMode.TRAKT)
        assertEquals(selection, effectiveTrackingSourceSelection(selection, connected))
        assertEquals(
            selection.copy(watchProgressSource = WatchProgressSource.FETCH_SYNC),
            effectiveTrackingSourceSelection(selection, connected - TrackingProviderId.MDBLIST)
        )
    }

    @Test
    fun `legacy source names retain their stored meaning`() {
        assertEquals(WatchProgressSource.TRAKT, WatchProgressSource.fromStorage("TRAKT"))
        assertEquals(WatchProgressSource.FETCH_SYNC, WatchProgressSource.fromStorage("FETCH_SYNC"))
        assertEquals(LibrarySourceMode.TRAKT, LibrarySourceMode.valueOf("TRAKT"))
    }

    @Test
    fun `remote watch source falls back to Fetch Sync when disconnected`() {
        assertEquals(
            WatchProgressSource.FETCH_SYNC,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { false }
        )
        assertEquals(
            WatchProgressSource.SIMKL,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { it == TrackingProviderId.SIMKL }
        )
    }

    @Test
    fun `remote library source falls back to local when disconnected`() {
        assertEquals(
            LibrarySourceMode.LOCAL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { false }
        )
        assertEquals(
            LibrarySourceMode.SIMKL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { it == TrackingProviderId.SIMKL }
        )
    }

    @Test
    fun `local sources do not map to remote providers`() {
        assertNull(WatchProgressSource.FETCH_SYNC.providerId)
        assertNull(LibrarySourceMode.LOCAL.providerId)
    }

    @Test
    fun `source reconciliation covers every provider connection combination`() {
        val requested = TrackingSourceSelection(
            watchProgressSource = WatchProgressSource.SIMKL,
            librarySourceMode = LibrarySourceMode.TRAKT
        )

        assertEquals(
            TrackingSourceSelection(
                WatchProgressSource.FETCH_SYNC,
                LibrarySourceMode.LOCAL
            ),
            effectiveTrackingSourceSelection(requested, emptySet())
        )
        assertEquals(
            TrackingSourceSelection(
                WatchProgressSource.FETCH_SYNC,
                LibrarySourceMode.TRAKT
            ),
            effectiveTrackingSourceSelection(requested, setOf(TrackingProviderId.TRAKT))
        )
        assertEquals(
            TrackingSourceSelection(
                WatchProgressSource.SIMKL,
                LibrarySourceMode.LOCAL
            ),
            effectiveTrackingSourceSelection(requested, setOf(TrackingProviderId.SIMKL))
        )
        assertEquals(
            requested,
            effectiveTrackingSourceSelection(
                requested,
                setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL)
            )
        )
    }

    @Test
    fun `disconnecting an active provider resets only its selected source`() {
        val requested = TrackingSourceSelection(
            watchProgressSource = WatchProgressSource.SIMKL,
            librarySourceMode = LibrarySourceMode.TRAKT
        )

        assertEquals(
            TrackingSourceSelection(
                watchProgressSource = WatchProgressSource.FETCH_SYNC,
                librarySourceMode = LibrarySourceMode.TRAKT
            ),
            effectiveTrackingSourceSelection(
                requested,
                setOf(TrackingProviderId.TRAKT)
            )
        )
    }

    @Test
    fun `disconnecting an inactive provider preserves selected sources`() {
        val requested = TrackingSourceSelection(
            watchProgressSource = WatchProgressSource.TRAKT,
            librarySourceMode = LibrarySourceMode.TRAKT
        )

        assertEquals(
            requested,
            effectiveTrackingSourceSelection(
                requested,
                setOf(TrackingProviderId.TRAKT)
            )
        )
    }

    @Test
    fun `both connected pickers expose Fetch Trakt and Simkl in stable order`() {
        val connected = setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL)

        assertEquals(
            listOf(
                WatchProgressSource.FETCH_SYNC,
                WatchProgressSource.TRAKT,
                WatchProgressSource.SIMKL
            ),
            availableWatchProgressSources(connected)
        )
        assertEquals(
            listOf(
                LibrarySourceMode.LOCAL,
                LibrarySourceMode.TRAKT,
                LibrarySourceMode.SIMKL
            ),
            availableLibrarySourceModes(connected)
        )
    }

    @Test
    fun `disconnected providers are excluded from source pickers`() {
        assertEquals(
            listOf(WatchProgressSource.FETCH_SYNC),
            availableWatchProgressSources(emptySet())
        )
        assertEquals(
            listOf(LibrarySourceMode.LOCAL),
            availableLibrarySourceModes(emptySet())
        )
        assertEquals(
            listOf(WatchProgressSource.FETCH_SYNC, WatchProgressSource.SIMKL),
            availableWatchProgressSources(setOf(TrackingProviderId.SIMKL))
        )
        assertEquals(
            listOf(LibrarySourceMode.LOCAL, LibrarySourceMode.TRAKT),
            availableLibrarySourceModes(setOf(TrackingProviderId.TRAKT))
        )
    }
}
