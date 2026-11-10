package com.fetch.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.backend.SyncBackends
import com.fetch.tv.domain.repository.WatchProgressRepository
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
    val state: WebsiteReadState<List<CalendarDay>> = WebsiteReadState.Loading,
    /** Watch history only: series TMDB couldn't be asked about, so the calendar may be missing some. */
    val failedSeries: Int = 0
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
    private val syncBackends: SyncBackends,
    progress: WatchProgressRepository,
    profileManager: ProfileManager
) : ViewModel() {
    private val range = MutableStateFlow(DefaultCalendarRange)
    private val retries = MutableStateFlow(0)

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        // The sync method, and not only the website sign-in: a TV can stay signed in to the website
        // while local only is the method chosen. The history's first full load counts too, since a
        // read before it would find Trakt's history still empty.
        val account = combine(
            profileManager.activeProfileId,
            website.isAuthenticated,
            syncBackends.storedChoice,
            progress.observeRemoteProgressLoaded()
        ) { slot, signedIn, _, historyLoaded -> listOf(slot, signedIn && syncBackends.isWebsiteActive, historyLoaded) }
            .distinctUntilChanged()
        viewModelScope.launch {
            combine(account, retries, range) { key, _, days -> (key[1] as Boolean) to days }.collectLatest { (useWebsite, days) ->
                val initialSource = if (useWebsite) CalendarSource.WEBSITE else CalendarSource.WATCH_HISTORY
                _uiState.value = CalendarUiState(days, initialSource)
                val loaded = load(useWebsite, days)
                _uiState.update { if (it.rangeDays == days) loaded.copy(rangeDays = days) else it }
            }
        }
    }

    private suspend fun load(useWebsite: Boolean, days: Int): CalendarUiState {
        if (useWebsite) {
            val fromWebsite = website.readForActiveProfile { profileId, token ->
                groupCalendarByDay(client.calendar(profileId, token, days), LocalDate.now())
            }
            // A profile only on this TV, or a TV the website just signed out, still has a history here.
            if (fromWebsite != WebsiteReadState.NoProfile && fromWebsite != WebsiteReadState.NotPaired) {
                return CalendarUiState(days, CalendarSource.WEBSITE, fromWebsite)
            }
        }
        return try {
            val today = LocalDate.now()
            val lookup = watchHistory.upcoming(days, today)
            CalendarUiState(
                days,
                CalendarSource.WATCH_HISTORY,
                WebsiteReadState.Loaded(groupCalendarByDay(lookup.entries, today)),
                failedSeries = lookup.failedSeries
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CalendarUiState(days, CalendarSource.WATCH_HISTORY, WebsiteReadState.Failed(error.message))
        }
    }

    fun selectRange(days: Int) {
        if (days in CalendarRanges) range.value = days
    }

    fun retry() {
        retries.update { it + 1 }
    }
}
