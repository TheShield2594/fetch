package com.fetch.tv.ui.navigation

import android.annotation.SuppressLint
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.flow.StateFlow

// The one place Fetch reads a NavController's whole back stack. Navigation 2.8 has no public
// API for it: NavController.currentBackStack is @RestrictTo(LIBRARY_GROUP), and the public
// visibleEntries holds only the entries on screen, not a detail page under the player.
// Keeping the access here confines the RestrictedApi suppression to these two lines.

/** The whole back stack, oldest first, as a flow that updates on every navigation. */
@SuppressLint("RestrictedApi")
internal fun NavController.backStackFlow(): StateFlow<List<NavBackStackEntry>> = currentBackStack

/** A snapshot of the whole back stack, oldest first. */
internal fun NavController.backStackEntries(): List<NavBackStackEntry> = backStackFlow().value
