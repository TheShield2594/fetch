package com.fetch.tv.ui.screens.search

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.components.FetchButton
import com.fetch.tv.ui.theme.FetchSizes
import com.fetch.tv.ui.theme.FetchSpacing
import com.fetch.tv.ui.theme.FetchTheme

/** The characters on the grid, row by row: letters, then digits. */
internal val SEARCH_KEYBOARD_ROWS: List<String> = listOf(
    "abcdef",
    "ghijkl",
    "mnopqr",
    "stuvwx",
    "yz1234",
    "567890"
)

/** What a key of the grid does to the query. */
internal sealed interface SearchKey {
    data class Character(val char: Char) : SearchKey
    data object Space : SearchKey
    data object Delete : SearchKey
    data object Clear : SearchKey
}

/** The query after [key] is pressed. A space is never typed first or twice in a row. */
internal fun applySearchKey(query: String, key: SearchKey): String = when (key) {
    is SearchKey.Character -> query + key.char
    SearchKey.Space -> if (query.isEmpty() || query.endsWith(' ')) query else "$query "
    SearchKey.Delete -> query.dropLast(1)
    SearchKey.Clear -> ""
}

private val KEY_SIZE: Dp = FetchSizes.tokens.controls.icon
private val KEY_GAP: Dp = FetchSpacing.tokens.sm
private val ROW_WIDTH: Dp = KEY_SIZE * 6 + KEY_GAP * 5

/** How wide the grid is, so the results beside it can start after it. */
internal val SEARCH_KEYBOARD_WIDTH: Dp = ROW_WIDTH

/**
 * An on-screen grid keyboard for Search (#484), shown beside the results so the merged rows and
 * the top result stay visible while typing, which the system IME covers on most TVs.
 *
 * [focusRequester] lands on the key used last, so Back from the results returns to it. Right from
 * the last column goes to [rightFocusRequester] (the top result) when there is one, so it is one
 * press away (the end side, so the left in RTL); Up from the first row goes to
 * [upFocusRequester] (the input row).
 */
@Composable
internal fun SearchGridKeyboard(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    rightFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    /** Focus left the keyboard towards the results: the user accepted what they typed. */
    onMoveToResults: () -> Unit = {}
) {
    val layoutDirection = LocalLayoutDirection.current
    // Armed by a key press towards the results; focus leaving the keyboard then means entering them.
    var movingToResults by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }
    val press: (SearchKey) -> Unit = { key -> onQueryChange(applySearchKey(query, key)) }
    val keyboardDescription = stringResource(R.string.search_keyboard_cd)
    Column(
        modifier = modifier
            .width(ROW_WIDTH)
            .semantics { contentDescription = keyboardDescription }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val towardResults = if (layoutDirection == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
                    movingToResults = event.key == towardResults
                }
                false
            }
            .onFocusChanged { state ->
                if (!state.hasFocus && movingToResults) onMoveToResults()
                if (!state.hasFocus) movingToResults = false
            }
            .focusRequester(focusRequester)
            .focusRestorer(firstKey)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(KEY_GAP)
    ) {
        SEARCH_KEYBOARD_ROWS.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                row.forEachIndexed { columnIndex, char ->
                    KeyboardKey(
                        onClick = { press(SearchKey.Character(char)) },
                        modifier = Modifier
                            .size(KEY_SIZE)
                            .then(if (rowIndex == 0 && columnIndex == 0) Modifier.focusRequester(firstKey) else Modifier)
                            .focusProperties {
                                // The results sit at the end side, which is the left in RTL.
                                if (columnIndex == row.lastIndex && rightFocusRequester != null) {
                                    end = rightFocusRequester
                                }
                                if (rowIndex == 0 && upFocusRequester != null) up = upFocusRequester
                            }
                    ) {
                        Text(
                            text = char.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            val wideKey = (ROW_WIDTH - KEY_GAP * 2) / 3
            val spaceLabel = stringResource(R.string.search_keyboard_space)
            KeyboardKey(
                onClick = { press(SearchKey.Space) },
                modifier = Modifier
                    .width(wideKey)
                    .height(KEY_SIZE)
                    .semantics { contentDescription = spaceLabel }
            ) {
                Icon(Icons.Default.SpaceBar, contentDescription = null, modifier = Modifier.size(FetchSizes.tokens.icons.sm))
            }
            val deleteLabel = stringResource(R.string.search_keyboard_delete)
            KeyboardKey(
                onClick = { press(SearchKey.Delete) },
                modifier = Modifier
                    .width(wideKey)
                    .height(KEY_SIZE)
                    .semantics { contentDescription = deleteLabel }
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null, modifier = Modifier.size(FetchSizes.tokens.icons.sm))
            }
            KeyboardKey(
                onClick = { press(SearchKey.Clear) },
                modifier = Modifier
                    .width(wideKey)
                    .height(KEY_SIZE)
                    .focusProperties { if (rightFocusRequester != null) end = rightFocusRequester }
            ) {
                Text(
                    text = stringResource(R.string.search_keyboard_clear),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun KeyboardKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Delete and Clear stay enabled on an empty query: disabling the focused key would throw
    // focus off the keyboard.
    FetchButton(
        onClick = onClick,
        modifier = modifier,
        // Keys sit close together; a key that grows much would cover its neighbours.
        focusedScale = FetchTheme.focus.subtleScale,
        contentPadding = PaddingValues(FetchSpacing.tokens.none)
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
    }
}
