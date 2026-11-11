package com.fetch.tv.ui.components

import androidx.compose.ui.focus.FocusRequester

/**
 * The [FocusRequester] stored under [key], created on first use.
 *
 * Lists and rows keep one requester per item in a map so focus can be restored to the same item.
 * The map itself must be remembered (`remember { mutableMapOf<K, FocusRequester>() }`, or passed
 * down from a caller that remembers it): then each requester is created once and survives
 * recomposition. A map that is not remembered would hand out a new requester on every
 * recomposition and break focus restoration.
 */
fun <K> MutableMap<K, FocusRequester>.focusRequesterFor(key: K): FocusRequester =
    getOrPut(key) { FocusRequester() }
