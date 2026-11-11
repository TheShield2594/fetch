package com.fetch.desktop.ui

import androidx.compose.runtime.Composable
import com.fetch.desktop.AppState

/**
 * Settings › Account: pairing with the Fetch website, which keeps this computer's profiles,
 * addons, settings, Home rows and collections in step with the TV app.
 */
@Composable
fun AccountPage(state: AppState, header: Boolean = true) {
    if (header) PageHeader(
        "Account",
        subtitle = "Pair this computer with your Fetch website to sync profiles, addons, settings, Home rows and Home folders with the TV app."
    )
    FetchWebsiteSection(state)
}
