package com.fetch.tv.ui.screens.stats

import com.fetch.tv.data.website.FetchWebsiteException
import com.fetch.tv.data.website.FetchWebsiteSignedOutException
import com.fetch.tv.data.website.FetchWebsiteTrackingRepository
import kotlinx.coroutines.CancellationException

/**
 * Something the TV reads from the Fetch website for the active profile, such as the stats or the
 * calendar. Both are worked out by the website from the history it keeps, so without a website
 * there is nothing to show, and each way of having none says why.
 */
sealed interface WebsiteReadState<out T> {
    data object Loading : WebsiteReadState<Nothing>

    /** This TV isn't signed in to a Fetch website. */
    data object NotPaired : WebsiteReadState<Nothing>

    /** Signed in, but this TV profile isn't one of the website's profiles. */
    data object NoProfile : WebsiteReadState<Nothing>

    /** The website profile has a PIN and it hasn't been entered on this TV. */
    data object Locked : WebsiteReadState<Nothing>

    data class Failed(val message: String?) : WebsiteReadState<Nothing>

    data class Loaded<T>(val value: T) : WebsiteReadState<T>
}

/** Runs [block] as the active profile's website profile, and says what happened as a [WebsiteReadState]. */
internal suspend fun <T : Any> FetchWebsiteTrackingRepository.readForActiveProfile(
    block: suspend (profileId: String, profileToken: String?) -> T
): WebsiteReadState<T> {
    if (!isAuthenticated.value) return WebsiteReadState.NotPaired
    return try {
        val value = asProfile { profileId, profileToken -> block(profileId, profileToken) }
        if (value == null) WebsiteReadState.NoProfile else WebsiteReadState.Loaded(value)
    } catch (error: CancellationException) {
        throw error
    } catch (_: FetchWebsiteSignedOutException) {
        WebsiteReadState.NotPaired
    } catch (error: FetchWebsiteException) {
        if (error.code == PROFILE_LOCKED_CODE) WebsiteReadState.Locked else WebsiteReadState.Failed(error.message)
    } catch (error: Exception) {
        WebsiteReadState.Failed(error.message)
    }
}

private const val PROFILE_LOCKED_CODE = "profile_verification_required"
