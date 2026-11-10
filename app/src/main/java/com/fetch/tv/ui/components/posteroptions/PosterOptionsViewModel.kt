package com.fetch.tv.ui.components.posteroptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Standalone ViewModel that owns a [PosterOptionsController] for screens that
 * don't have a dedicated ViewModel of their own (e.g. screens that reuse another
 * feature's ViewModel as their primary). Obtain via `hiltViewModel<PosterOptionsViewModel>()`.
 */
@HiltViewModel
class PosterOptionsViewModel @Inject constructor(
    val controller: PosterOptionsController,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val _showHoldHint = MutableStateFlow(false)

    /** True once, the first time this profile focuses a card: the screen shows "Hold OK for options". */
    val showHoldHint: StateFlow<Boolean> = _showHoldHint.asStateFlow()

    private var holdHintChecked = false

    init {
        controller.bind(viewModelScope)
    }

    /** Call when a card gets focus. Shows the long-press hint the first time per profile. */
    fun onCardFocused() {
        if (holdHintChecked) return
        holdHintChecked = true
        viewModelScope.launch {
            if (runCatching { layoutPreferenceDataStore.posterOptionsHintSeen.first() }.getOrDefault(true)) {
                return@launch
            }
            // A failed write must not crash Home; the hint stays due for the next card focus.
            if (runCatching { layoutPreferenceDataStore.setPosterOptionsHintSeen() }.isFailure) {
                holdHintChecked = false
                return@launch
            }
            _showHoldHint.value = true
        }
    }

    fun consumeHoldHint() {
        _showHoldHint.value = false
    }
}
