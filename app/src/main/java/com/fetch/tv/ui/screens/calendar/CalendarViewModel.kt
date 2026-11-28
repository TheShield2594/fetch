package com.fetch.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.backend.SyncBackends
import com.fetch.tv.data.website.WebsiteCalendarEntry
import com.fetch.tv.domain.model.WatchProgress
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
    val failedSeries: Int = 0,
    /** Entries ([calendarEntryKey]) whose watched mark is being saved; their button waits. */
    val pendingWatched: Set<String> = emptySet(),
    /** A mark that couldn't be saved, shown until the next one. */
    val watchedError: Boolean = false
)

/** One calendar entry, the same however it was read. */
internal fun calendarEntryKey(entry: WebsiteCalendarEntry): String =
    "${entry.seriesImdbId}:${entry.season}:${entry.episode}"

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
    private val progress: WatchProgressRepository,
    profileManager: ProfileManager
) : ViewModel() {
    private val range = MutableStateFlow(DefaultCalendarRange)
    private val retries = MutableStateFlow(0)

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    // Watched marks outlive a reload: a calendar read before a mark was saved mustn't put the old
    // value back. They belong to one profile and are dropped when another becomes active.
    private var markProfile: Any? = null
    /** Entries whose mark is being saved. */
    private val savingMarks = mutableSetOf<String>()
    /** The watched value each marked entry should show, by [calendarEntryKey]. */
    private val marks = mutableMapOf<String, Boolean>()

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
            combine(account, retries, range) { key, _, days -> Triple(key[0], key[1] as Boolean, days) }
                .collectLatest { (profile, useWebsite, days) ->
                    if (profile != markProfile) {
                        markProfile = profile
                        savingMarks.clear()
                        marks.clear()
                    }
                    val initialSource = if (useWebsite) CalendarSource.WEBSITE else CalendarSource.WATCH_HISTORY
                    _uiState.value = CalendarUiState(days, initialSource, pendingWatched = savingMarks.toSet())
                    val loaded = withMarks(load(useWebsite, days))
                    _uiState.update { if (it.rangeDays == days) loaded.copy(rangeDays = days) else it }
                }
        }
    }

    private suspend fun load(useWebsite: Boolean, days: Int): CalendarUiState {
        if (useWebsite) {
            val fromWebsite = website.readForActiveProfile { profileId, token ->
                val today = LocalDate.now()
                val upcoming = client.calendar(profileId, token, days)
                // The past week, for what aired recently and whether it was watched. Extra: if it
                // can't be read, the days ahead still show.
                val recent = try {
                    client.calendar(profileId, token, CalendarPastDays, start = today.minusDays(CalendarPastDays.toLong()))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
                groupCalendarByDay(recent + upcoming, today)
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

    /**
     * Marks [entry] watched, or unwatched when it already is, through the same path as the title's
     * own page, so it reaches Trakt or the website as any other mark does. The row changes at once
     * and changes back if the mark can't be saved.
     */
    fun toggleWatched(entry: WebsiteCalendarEntry) {
        val key = calendarEntryKey(entry)
        if (key in savingMarks) return
        val profile = markProfile
        val watched = !entry.watched
        marks[key] = watched
        savingMarks += key
        setWatched(key, watched)
        viewModelScope.launch {
            val saved = try {
                if (watched) markWatched(entry) else markUnwatched(entry)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            // Another profile is on screen now: this result isn't its to show.
            if (markProfile != profile) return@launch
            savingMarks -= key
            if (!saved) marks[key] = !watched
            setWatched(key, marks.getValue(key))
            _uiState.update { it.copy(watchedError = !saved) }
        }
    }

    private suspend fun markWatched(entry: WebsiteCalendarEntry) {
        val now = System.currentTimeMillis()
        if (entry.isMovie) {
            val ids = setOf(entry.seriesImdbId)
            progress.applyOptimisticWatchedMovie(ids, add = true)
            try {
                progress.markAsCompleted(completedProgress(entry, "movie", entry.seriesImdbId, now))
            } catch (error: Exception) {
                progress.revertOptimisticWatchedMovie(ids, add = true)
                throw error
            }
        } else {
            val videoId = "${entry.seriesImdbId}:${entry.season}:${entry.episode}"
            progress.markAsCompleted(completedProgress(entry, "series", videoId, now))
        }
    }

    private suspend fun markUnwatched(entry: WebsiteCalendarEntry) {
        if (entry.isMovie) {
            progress.removeFromHistory(entry.seriesImdbId, videoId = entry.seriesImdbId)
        } else {
            progress.removeFromHistory(
                entry.seriesImdbId,
                videoId = "${entry.seriesImdbId}:${entry.season}:${entry.episode}",
                season = entry.season,
                episode = entry.episode
            )
        }
    }

    private fun completedProgress(entry: WebsiteCalendarEntry, type: String, videoId: String, now: Long) = WatchProgress(
        contentId = entry.seriesImdbId,
        contentType = type,
        name = entry.seriesName,
        poster = entry.poster,
        backdrop = entry.still ?: entry.backdrop,
        logo = null,
        videoId = videoId,
        season = entry.season,
        episode = entry.episode,
        episodeTitle = entry.episodeName,
        position = 1L,
        duration = 1L,
        lastWatched = now,
        progressPercent = 100f
    )

    /**
     * Sets the watched mark of the entry with [key] wherever it's listed. A calendar still loading
     * gets it from [withMarks] once it has loaded.
     */
    private fun setWatched(key: String, watched: Boolean) {
        _uiState.update { state ->
            val pending = savingMarks.toSet()
            val loaded = state.state as? WebsiteReadState.Loaded ?: return@update state.copy(pendingWatched = pending)
            val days = loaded.value.map { day ->
                day.copy(entries = day.entries.map { if (calendarEntryKey(it) == key) it.copy(watched = watched) else it })
            }
            state.copy(state = WebsiteReadState.Loaded(days), pendingWatched = pending)
        }
    }

    /** [state] with this profile's marks applied over what was read, and the saves still running. */
    private fun withMarks(state: CalendarUiState): CalendarUiState {
        val loaded = state.state as? WebsiteReadState.Loaded
        val days = loaded?.value?.takeIf { marks.isNotEmpty() }?.map { day ->
            day.copy(entries = day.entries.map { entry -> marks[calendarEntryKey(entry)]?.let { entry.copy(watched = it) } ?: entry })
        }
        return state.copy(
            state = days?.let { WebsiteReadState.Loaded(it) } ?: state.state,
            pendingWatched = savingMarks.toSet()
        )
    }
}
