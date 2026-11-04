package com.fetch.tv.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.stats.LocalStatsSource
import com.fetch.tv.core.stats.LocalWatchHistory
import com.fetch.tv.core.stats.localDetailedStats
import com.fetch.tv.core.stats.localTotals
import com.fetch.tv.core.stats.localYearInReview
import com.fetch.tv.data.website.FetchWebsiteClient
import com.fetch.tv.data.website.FetchWebsiteTrackingRepository
import com.fetch.tv.data.website.WebsiteDetailedStats
import com.fetch.tv.data.website.WebsiteWatchStats
import com.fetch.tv.data.website.WebsiteYearInReview
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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

/** Where the figures came from, so the screen can say. */
enum class StatsSource { WEBSITE, WATCH_HISTORY }

/** The totals and the detailed breakdown, read together as the website's stats page reads them. */
data class StatsOverview(
    val totals: WebsiteWatchStats,
    val detailed: WebsiteDetailedStats,
    val source: StatsSource = StatsSource.WEBSITE,
    /** Watch history only: genres and ratings cover the most recently watched titles, not all. */
    val detailsPartial: Boolean = false
) {
    /** Nothing watched: no figure here has anything to be built from. */
    val isEmpty: Boolean get() = totals.totalMovies == 0 && totals.totalEpisodes == 0
}

data class YearReviewUiState(
    val year: Int,
    val state: WebsiteReadState<WebsiteYearInReview> = WebsiteReadState.Loading
)

/**
 * Watch statistics for the active profile: totals, streaks, badges, the last twelve months,
 * genres, top-rated titles and a year in review.
 *
 * With the Fetch website as the sync method they're the website's own figures. With any other
 * (or a TV profile the website doesn't have), they're worked out on the TV from the profile's
 * watch history by [LocalStatsSource]. Read again when the profile or the website sign-in changes.
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val website: FetchWebsiteTrackingRepository,
    private val client: FetchWebsiteClient,
    private val localStats: LocalStatsSource,
    profileManager: ProfileManager
) : ViewModel() {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val currentYear = LocalDate.now().year

    /** This year and the five before it. */
    val yearOptions: List<Int> = yearInReviewOptions(currentYear)

    private val _overview = MutableStateFlow<WebsiteReadState<StatsOverview>>(WebsiteReadState.Loading)
    val overview: StateFlow<WebsiteReadState<StatsOverview>> = _overview.asStateFlow()

    // The year you're in, not the one that just ended, as on the website.
    private val selectedYear = MutableStateFlow(currentYear)
    private val _yearReview = MutableStateFlow(YearReviewUiState(currentYear))
    val yearReview: StateFlow<YearReviewUiState> = _yearReview.asStateFlow()

    private val _source = MutableStateFlow(StatsSource.WATCH_HISTORY)

    /** Where the figures on screen come from, or are being read from. */
    val source: StateFlow<StatsSource> = _source.asStateFlow()

    private val retries = MutableStateFlow(0)

    /** The source read last, and the history when it was the TV's own: a year change needs no new read. */
    private val loaded = MutableStateFlow<Pair<StatsSource, LocalWatchHistory?>?>(null)

    init {
        val account = combine(profileManager.activeProfileId, website.isAuthenticated) { slot, signedIn -> slot to signedIn }
            .distinctUntilChanged()
        viewModelScope.launch {
            combine(account, retries) { (_, signedIn), _ -> signedIn }.collectLatest { signedIn ->
                loaded.value = null
                _source.value = if (signedIn) StatsSource.WEBSITE else StatsSource.WATCH_HISTORY
                _overview.value = WebsiteReadState.Loading
                _overview.value = loadOverview(signedIn)
            }
        }
        viewModelScope.launch {
            combine(loaded, selectedYear) { source, year -> source to year }.collectLatest { (source, year) ->
                _yearReview.value = YearReviewUiState(year)
                val state = when (source?.first) {
                    null -> return@collectLatest
                    StatsSource.WEBSITE -> website.readForActiveProfile { profileId, token -> client.yearInReview(profileId, token, year) }
                    StatsSource.WATCH_HISTORY -> source.second
                        ?.let { WebsiteReadState.Loaded(localYearInReview(it, year, zone)) }
                        ?: WebsiteReadState.Failed(null)
                }
                _yearReview.update { if (it.year == year) it.copy(state = state) else it }
            }
        }
    }

    private suspend fun loadOverview(signedIn: Boolean): WebsiteReadState<StatsOverview> {
        if (signedIn) {
            val fromWebsite = website.readForActiveProfile { profileId, token ->
                coroutineScope {
                    val totals = async { client.watchStats(profileId, token) }
                    val detailed = async { client.detailedStats(profileId, token) }
                    StatsOverview(totals.await(), detailed.await())
                }
            }
            // A profile only on this TV, or a TV the website just signed out, still has a history here.
            if (fromWebsite != WebsiteReadState.NoProfile && fromWebsite != WebsiteReadState.NotPaired) {
                if (fromWebsite is WebsiteReadState.Loaded) loaded.value = StatsSource.WEBSITE to null
                return fromWebsite
            }
        }
        _source.value = StatsSource.WATCH_HISTORY
        return try {
            val history = localStats.read()
            loaded.value = StatsSource.WATCH_HISTORY to history
            WebsiteReadState.Loaded(
                StatsOverview(
                    totals = localTotals(history, Instant.now()),
                    detailed = localDetailedStats(history, LocalDate.now(zone), zone),
                    source = StatsSource.WATCH_HISTORY,
                    detailsPartial = history.detailsPartial
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WebsiteReadState.Failed(error.message)
        }
    }

    fun selectYear(year: Int) {
        if (year in yearOptions) selectedYear.value = year
    }

    fun retry() {
        retries.update { it + 1 }
    }
}
