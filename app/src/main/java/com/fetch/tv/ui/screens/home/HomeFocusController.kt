package com.fetch.tv.ui.screens.home

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.fetch.tv.ui.util.StableRef

/**
 * Where focus is on Home: the active row and card, each row's last card, a pending focus request,
 * the focused catalog selection and the expanded card. Rows report focus through its methods.
 *
 * The values are snapshot state, exposed read-only, so a composable or derived state reading one
 * is invalidated by the change; write them only from event handlers and effects.
 */
@Stable
internal class HomeFocusController {
    private val activeRowKeyState = mutableStateOf<String?>(null)
    private val activeItemIndexState = mutableIntStateOf(0)
    private val pendingRowFocusKeyState = mutableStateOf<String?>(null)
    private val pendingRowFocusIndexState = mutableStateOf<Int?>(null)
    private val pendingRowFocusNonceState = mutableIntStateOf(0)
    private val focusedCatalogSelectionState = mutableStateOf<FocusedCatalogSelection?>(null)
    private val expandedCatalogFocusKeyState = mutableStateOf<String?>(null)
    private val focusedHeroMediaNonceState = mutableIntStateOf(0)
    private val expansionInteractionNonceState = mutableIntStateOf(0)
    private val lastHeroNavigationAtMsState = mutableLongStateOf(0L)
    private val lastFocusedContinueWatchingIndexState = mutableIntStateOf(-1)
    private val fastScrollingState: MutableState<Boolean> = mutableStateOf(false)

    val activeRowKey: State<String?> get() = activeRowKeyState
    val activeItemIndex: State<Int> get() = activeItemIndexState

    /** The card each row last had focus on. */
    val focusedItemByRow: SnapshotStateMap<String, Int> = mutableStateMapOf()
    val stableFocusedItemByRow = StableRef<MutableMap<String, Int>>(focusedItemByRow)

    val pendingRowFocusKey: State<String?> get() = pendingRowFocusKeyState
    val pendingRowFocusIndex: State<Int?> get() = pendingRowFocusIndexState
    val pendingRowFocusNonce: State<Int> get() = pendingRowFocusNonceState

    val focusedCatalogSelection: State<FocusedCatalogSelection?> get() = focusedCatalogSelectionState
    val expandedCatalogFocusKey: State<String?> get() = expandedCatalogFocusKeyState
    /** Bumped when a collection folder is focused again, to restart its hero video. */
    val focusedHeroMediaNonce: State<Int> get() = focusedHeroMediaNonceState
    /** Bumped by a backdrop interaction, to restart the expand delay. */
    val expansionInteractionNonce: State<Int> get() = expansionInteractionNonceState
    /** When focus last moved to a new card, for the held D-pad detection and the hero settle. */
    val lastHeroNavigationAtMs: State<Long> get() = lastHeroNavigationAtMsState
    val lastFocusedContinueWatchingIndex: State<Int> get() = lastFocusedContinueWatchingIndexState
    val isFastScrolling: State<Boolean> get() = fastScrollingState

    /** Told when a row takes focus, since a system Home press never runs the dispose save. */
    var onFocusedRowKeyChanged: (String?) -> Unit = {}

    /** Moves the hero's focus without telling [onFocusedRowKeyChanged] (restores and resets). */
    fun setActive(rowKey: String?, index: Int) {
        activeRowKeyState.value = rowKey
        activeItemIndexState.intValue = index
    }

    fun setActiveItemIndex(index: Int) {
        activeItemIndexState.intValue = index
    }

    /** Asks [rowKey] to take focus on [index] (a null key clears the request but still bumps it). */
    fun requestRowFocus(rowKey: String?, index: Int?) {
        pendingRowFocusKeyState.value = rowKey
        pendingRowFocusIndexState.value = index
        pendingRowFocusNonceState.intValue++
    }

    fun clearPendingRowFocus() {
        pendingRowFocusKeyState.value = null
        pendingRowFocusIndexState.value = null
    }

    fun selectCatalog(selection: FocusedCatalogSelection?) {
        focusedCatalogSelectionState.value = selection
    }

    /** No selection and no expanded card (also how a full-screen trailer is dismissed). */
    fun clearCatalogSelection() {
        focusedCatalogSelectionState.value = null
        expandedCatalogFocusKeyState.value = null
    }

    fun setExpandedCatalogFocusKey(key: String?) {
        expandedCatalogFocusKeyState.value = key
    }

    fun onBackdropInteraction() {
        expansionInteractionNonceState.intValue++
    }

    fun setFastScrolling(scrolling: Boolean) {
        fastScrollingState.value = scrolling
    }

    /** A card in a row took focus. [isSeeAll] is the See all card at the end of the row. */
    fun onRowItemFocused(
        rowKey: String,
        index: Int,
        isContinueWatchingRow: Boolean,
        isSeeAll: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val rowBecameActive = activeRowKeyState.value != rowKey
        val itemChanged = activeItemIndexState.intValue != index
        if (rowBecameActive || itemChanged) {
            lastHeroNavigationAtMsState.longValue = nowMs
            focusRow(rowKey)
            activeItemIndexState.intValue = index
        }

        // Always keep every row's index in sync.
        if (focusedItemByRow[rowKey] != index) {
            focusedItemByRow[rowKey] = index
        }

        // The See all card has no title behind it, so nothing stays selected.
        if (isSeeAll && focusedCatalogSelectionState.value != null) {
            focusedCatalogSelectionState.value = null
        }

        if (isContinueWatchingRow) {
            if (lastFocusedContinueWatchingIndexState.intValue != index) {
                lastFocusedContinueWatchingIndexState.intValue = index
            }
            if (focusedCatalogSelectionState.value != null) {
                focusedCatalogSelectionState.value = null
            }
        }
    }

    /** A catalog, collection or YouTube card took focus. */
    fun onCatalogSelectionFocused(selection: FocusedCatalogSelection) {
        val isCollectionFolder = selection.payload is ModernPayload.CollectionFolder
        if (focusedCatalogSelectionState.value != selection || isCollectionFolder) {
            focusedCatalogSelectionState.value = selection
            if (isCollectionFolder) focusedHeroMediaNonceState.intValue++
        }
    }

    /** A vertical fast scroll lands on [rowKey]: focus returns to that row's last card. */
    fun landOnRow(rowKey: String, index: Int) {
        focusRow(rowKey)
        activeItemIndexState.intValue = index
    }

    private fun focusRow(rowKey: String?) {
        activeRowKeyState.value = rowKey
        onFocusedRowKeyChanged(rowKey)
    }
}
