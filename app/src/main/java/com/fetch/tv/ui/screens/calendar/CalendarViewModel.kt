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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the calendar came from, so the screen can say. */
enum class CalendarSource { WEBSITE, WATCH_HISTORY }

data class CalendarUiState(
    val rangeDays: Int = DefaultCalendarRange,
    val source: CalendarSource = CalendarSource.WATCH_HISTORY,
    val state: WebsiteReadState<List<CalendarDay>> = WebsiteReadState.Loading
)

/**
 * Upcoming episodes of the series the active profile has started, grouped by the day they air.
 *
 * With the Fetch website as the sync method, they're the website's own calendar. With any other
 * (or a TV profile the website doesn't have), they're worked out on the TV from the profile's
 * watch history and TMDB by [WatchHistoryCalendarSource]. Read again when the range, the profile
 * or the website sign-in changes.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val website: FetchWebsiteTrackingRepository,
    private val client: FetchWebsiteClient,
    private val watchHistory: WatchHistoryCalendarSource,
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
            combine(account, retries, range) { (_, signedIn), _, days -> signedIn to days }.collectLatest { (signedIn, days) ->
                val initialSource = if (signedIn) CalendarSource.WEBSITE else CalendarSource.WATCH_HISTORY
                _uiState.value = CalendarUiState(days, initialSource)
                val (source, state) = load(signedIn, days)
                _uiState.update { if (it.rangeDays == days) it.copy(source = source, state = state) else it }
            }
        }
    }

    private suspend fun load(signedIn: Boolean, days: Int): Pair<CalendarSource, WebsiteReadState<List<CalendarDay>>> {
        if (signedIn) {
            val fromWebsite = website.readForActiveProfile { profileId, token ->
                groupCalendarByDay(client.calendar(profileId, token, days), LocalDate.now())
            }
            // A profile only on this TV, or a TV the website just signed out, still has a history here.
            if (fromWebsite != WebsiteReadState.NoProfile && fromWebsite != WebsiteReadState.NotPaired) {
                return CalendarSource.WEBSITE to fromWebsite
            }
        }
        val state = try {
            val today = LocalDate.now()
            WebsiteReadState.Loaded(groupCalendarByDay(watchHistory.upcoming(days, today), today))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WebsiteReadState.Failed(error.message)
        }
        return CalendarSource.WATCH_HISTORY to state
    }

    fun selectRange(days: Int) {
        if (days in CalendarRanges) range.value = days
    }

    fun retry() {
        retries.update { it + 1 }
    }
}
