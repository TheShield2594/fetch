package com.fetch.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.website.FetchWebsiteClient
import com.fetch.tv.data.website.FetchWebsiteTrackingRepository
import com.fetch.tv.ui.screens.stats.WebsiteReadState
import com.fetch.tv.ui.screens.stats.readForActiveProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarUiState(
    val rangeDays: Int = DefaultCalendarRange,
    val state: WebsiteReadState<List<CalendarDay>> = WebsiteReadState.Loading
)

/**
 * Upcoming episodes of the series the active profile has started, from the Fetch website's
 * calendar, grouped by the day they air. Read again when the range, the profile or the website
 * sign-in changes.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val website: FetchWebsiteTrackingRepository,
    private val client: FetchWebsiteClient,
    profileManager: ProfileManager
) : ViewModel() {
    private val range = MutableStateFlow(DefaultCalendarRange)
    private val retries = MutableStateFlow(0)

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        val account = combine(profileManager.activeProfileId, website.isAuthenticated) { slot, signedIn -> slot to signedIn }
            .distinctUntilChanged()
        viewModelScope.launch {
            combine(account, retries, range) { _, _, days -> days }.collectLatest { days ->
                _uiState.value = CalendarUiState(days)
                val state = website.readForActiveProfile { profileId, token ->
                    groupCalendarByDay(client.calendar(profileId, token, days), LocalDate.now())
                }
                _uiState.update { if (it.rangeDays == days) it.copy(state = state) else it }
            }
        }
    }

    fun selectRange(days: Int) {
        if (days in CalendarRanges) range.value = days
    }

    fun retry() {
        retries.update { it + 1 }
    }
}
