package com.fetch.tv.ui.screens.phone

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.qr.QrCodeGenerator
import com.fetch.tv.core.server.PhoneHubController
import com.fetch.tv.core.server.PhoneHubState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PhoneHubViewModel @Inject constructor(
    private val controller: PhoneHubController
) : ViewModel() {
    val state: StateFlow<PhoneHubState> = controller.state

    val qrCode: StateFlow<Bitmap?> = controller.state
        .map { it.url }
        .distinctUntilChanged()
        .map { url -> url?.let { QrCodeGenerator.generate(it, 512) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onScreenOpened() = controller.onScreenOpened()

    fun onScreenClosed() = controller.onScreenClosed()

    fun stop() = controller.stop()
}
