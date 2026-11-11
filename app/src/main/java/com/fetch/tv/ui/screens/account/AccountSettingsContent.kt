@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.sync.backend.SyncBackendKind
import com.fetch.tv.ui.theme.FetchTheme

/**
 * Settings → Account. The first row picks where this TV keeps its account ([SyncBackendRow]);
 * what follows is a note for local only, or pairing for a Fetch website.
 */
@Composable
fun AccountSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    backendViewModel: SyncBackendViewModel = hiltViewModel()
) {
    val chosen by backendViewModel.chosen.collectAsStateWithLifecycle()
    val backendRowModifier = if (initialFocusRequester != null) {
        Modifier.focusRequester(initialFocusRequester)
    } else {
        Modifier
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FetchTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "account_sync_backend") {
            SyncBackendRow(modifier = backendRowModifier, viewModel = backendViewModel)
        }
        when (chosen) {
            SyncBackendKind.LOCAL_ONLY -> item(key = "account_local_only") {
                AccountInlineNote(text = stringResource(R.string.sync_backend_local_note))
            }

            SyncBackendKind.FETCH_WEBSITE -> item(key = "account_fetch_website") { FetchWebsiteSection() }
        }
    }
}

@Composable
private fun AccountInlineNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = FetchTheme.colors.TextTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FetchTheme.spacing.md)
    )
}
