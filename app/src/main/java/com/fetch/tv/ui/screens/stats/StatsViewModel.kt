package com.fetch.tv.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.website.FetchWebsiteClient
import com.fetch.tv.data.website.FetchWebsiteTrackingRepository
import com.fetch.tv.data.website.WebsiteDetailedStats
import com.fetch.tv.data.website.WebsiteWatchStats
import com.fetch.tv.data.website.WebsiteYearInReview
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The totals and the detailed breakdown, read together as the website's stats page reads them. */
data class StatsOverview(
    val totals: WebsiteWatchStats,
    val detailed: WebsiteDetailedStats
) {
    /** Nothing watched: no figure here has anything to be built from. */
    val isEmpty: Boolean get() = totals.totalMovies == 0 && totals.totalEpisodes == 0
}

data class YearReviewUiState(
    val year: Int,
    val state: WebsiteReadState<WebsiteYearInReview> = WebsiteReadState.Loading
)

/**
 * Watch statistics for the active profile, from the Fetch website: totals, streaks, badges, the
 * last twelve months, genres, top-rated titles and a year in review. Read again when the profile
 * or the website sign-in changes.
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val website: FetchWebsiteTrackingRepository,
    private val client: FetchWebsiteClient,
    profileManager: ProfileManager
) : ViewModel() {
    private val currentYear = LocalDate.now().year

    /** This year and the five before it. */
    val yearOptions: List<Int> = yearInReviewOptions(currentYear)

    private val _overview = MutableStateFlow<WebsiteReadState<StatsOverview>>(WebsiteReadState.Loading)
    val overview: StateFlow<WebsiteReadState<StatsOverview>> = _overview.asStateFlow()

    // The year you're in, not the one that just ended, as on the website.
    private val selectedYear = MutableStateFlow(currentYear)
    private val _yearReview = MutableStateFlow(YearReviewUiState(currentYear))
    val yearReview: StateFlow<YearReviewUiState> = _yearReview.asStateFlow()

    private val retries = MutableStateFlow(0)

    init {
        val account = combine(profileManager.activeProfileId, website.isAuthenticated) { slot, signedIn -> slot to signedIn }
            .distinctUntilChanged()
        viewModelScope.launch {
            combine(account, retries) { key, attempt -> key to attempt }.collectLatest {
                _overview.value = WebsiteReadState.Loading
                _overview.value = website.readForActiveProfile { profileId, token ->
                    coroutineScope {
                        val totals = async { client.watchStats(profileId, token) }
                        val detailed = async { client.detailedStats(profileId, token) }
                        StatsOverview(totals.await(), detailed.await())
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(account, retries, selectedYear) { _, _, year -> year }.collectLatest { year ->
                _yearReview.value = YearReviewUiState(year)
                val state = website.readForActiveProfile { profileId, token -> client.yearInReview(profileId, token, year) }
                _yearReview.update { if (it.year == year) it.copy(state = state) else it }
            }
        }
    }

    fun selectYear(year: Int) {
        if (year in yearOptions) selectedYear.value = year
    }

    fun retry() {
        retries.update { it + 1 }
    }
}
