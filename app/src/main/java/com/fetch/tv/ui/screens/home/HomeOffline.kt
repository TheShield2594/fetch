package com.fetch.tv.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.network.NetworkReach
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.components.StateActionButton
import com.fetch.tv.ui.theme.FetchTheme

/** A connected media server's library Home can open while the internet is unreachable (#447). */
@Immutable
data class HomeServerLibraryLink(
    /** "Living room Plex · Movies". */
    val title: String,
    val catalogId: String,
    val addonId: String,
    val type: String
)

/**
 * Whether Home shows its offline state instead of an empty or error state (#447).
 *
 * Rows Home already has (from the disk or HTTP cache) stay on screen: the state is only for a
 * Home with nothing to show. With no network at all it shows at once, without waiting for each
 * catalog to time out. On a network Android could not validate it waits for the load to end
 * empty or fail, because some networks fail validation yet work.
 */
internal fun shouldShowHomeOffline(
    reach: NetworkReach,
    hasAnyContent: Boolean,
    isLoading: Boolean,
    errorKind: HomeErrorKind?
): Boolean {
    if (hasAnyContent) return false
    // Nothing installed is not a network problem; that state says what to do.
    if (errorKind == HomeErrorKind.NO_ADDONS || errorKind == HomeErrorKind.NO_CATALOG_ADDONS) return false
    return when (reach) {
        NetworkReach.OFFLINE -> true
        NetworkReach.UNCONFIRMED -> !isLoading || errorKind == HomeErrorKind.LOAD_FAILED
        NetworkReach.ONLINE -> false
    }
}

internal enum class HomeReachReload { NONE, FULL, VISIBLE_ROWS }

/**
 * What Home reloads when the network's reach changes: nothing unless it improved; everything
 * when Home is empty or failed; otherwise the rows already on screen, which may be stale cache.
 */
internal fun homeReloadOnReach(
    previous: NetworkReach?,
    current: NetworkReach,
    hasCatalogRows: Boolean,
    errorKind: HomeErrorKind?
): HomeReachReload = when {
    previous == null || current.ordinal <= previous.ordinal -> HomeReachReload.NONE
    !hasCatalogRows || errorKind == HomeErrorKind.LOAD_FAILED -> HomeReachReload.FULL
    else -> HomeReachReload.VISIBLE_ROWS
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HomeOfflineState(
    reach: NetworkReach,
    serverLibrary: HomeServerLibraryLink?,
    onRetry: () -> Unit,
    onOpenServerLibrary: (HomeServerLibraryLink) -> Unit,
    modifier: Modifier = Modifier
) {
    val retryFocus = remember { FocusRequester() }
    // Nothing else on Home can hold focus here, so Retry takes it.
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = FetchTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.WifiOff,
            contentDescription = null,
            modifier = Modifier.size(FetchTheme.spacing.xxxl),
            tint = FetchTheme.colors.TextTertiary
        )
        Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
        Text(
            text = stringResource(
                if (reach == NetworkReach.OFFLINE) R.string.home_offline_title else R.string.home_no_internet_title
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = FetchTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
        Text(
            text = stringResource(
                when {
                    serverLibrary != null -> R.string.home_offline_subtitle_server
                    reach == NetworkReach.OFFLINE -> R.string.home_offline_subtitle
                    else -> R.string.home_no_internet_subtitle
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
            StateActionButton(
                StateAction(stringResource(R.string.action_retry), onRetry),
                modifier = Modifier.focusRequester(retryFocus)
            )
            if (serverLibrary != null) {
                StateActionButton(
                    StateAction(
                        stringResource(R.string.home_offline_open_server, serverLibrary.title),
                        { onOpenServerLibrary(serverLibrary) }
                    )
                )
            }
        }
    }
}
