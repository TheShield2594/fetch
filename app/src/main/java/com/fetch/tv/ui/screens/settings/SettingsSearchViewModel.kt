package com.fetch.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.fetch.tv.core.remote.RemoteCommandBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Hands the settings screen a "Search settings" query sent from the phone hub. */
@HiltViewModel
class SettingsSearchViewModel @Inject constructor(
    private val remoteCommandBus: RemoteCommandBus
) : ViewModel() {
    val pendingPhoneQuery: StateFlow<String?> = remoteCommandBus.pendingSettingsSearch

    fun consumePhoneQuery(): String? = remoteCommandBus.consumePendingSettingsSearch()
}
