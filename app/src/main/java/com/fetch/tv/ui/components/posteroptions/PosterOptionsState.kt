package com.fetch.tv.ui.components.posteroptions

import androidx.compose.runtime.Immutable
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.LibrarySourceMode
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.fetch.tv.core.tracking.usesLibraryListPicker

@Immutable
data class PosterOptionsState(
    val target: MetaPreview? = null,
    /** The original item ID before canonicalization (e.g. "tmdb:123"). Used for optimistic badge updates. */
    val originalItemId: String? = null,
    val addonBaseUrl: String = "",
    val isInLibrary: Boolean = false,
    val isWatched: Boolean = false,
    val isLibraryPending: Boolean = false,
    val isWatchedPending: Boolean = false,
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val libraryListTabs: List<LibraryListTab> = emptyList(),
    val listPickerActive: Boolean = false,
    val listPickerTitle: String? = null,
    val listPickerContentType: String? = null,
    val listPickerMembership: Map<String, Boolean> = emptyMap(),
    val listPickerPending: Boolean = false,
    val listPickerError: String? = null,
    val removalConfirmations: List<TrackingMembershipRemovalConfirmation> = emptyList(),
    /** A library or watched write that failed after the menu closed; shown once, then consumed. */
    val actionError: String? = null,
    /** A library, list or watched write that went through; shown once, then consumed. */
    val actionConfirmation: String? = null
)

/** Long-press "Add to list" opens the list picker rather than toggling the saved library. */
val PosterOptionsState.usesListPicker: Boolean
    get() = usesLibraryListPicker(librarySourceMode == LibrarySourceMode.LOCAL, libraryListTabs)
