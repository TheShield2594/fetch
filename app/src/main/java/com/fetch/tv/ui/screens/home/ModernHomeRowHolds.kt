package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.isPlaceholder

/**
 * Values a row or card resolves during composition from what it held last time. Each is used in
 * the composition that resolves it and stored in a SideEffect, so composing never writes snapshot
 * state (which would recompose the row a second time).
 */
internal class CompositionHold<T>(var value: T)

/** A value resolved during composition, read by a row's item key lambda. */
internal class ResolvedValue<T>(val value: T)

/**
 * The first card of a row keeps a positional key while the focus ring can be on it, so the
 * placeholder the ring sits on is reused when real data replaces it instead of torn down.
 */
internal data class FirstCardPin(
    /** The first card is lent the positional key. */
    val pinned: Boolean,
    /** The pin has been released and is not armed again for this row. */
    val spent: Boolean = false,
    /** The real card the key is lent to, once data has replaced the placeholder. */
    val itemKey: String? = null
) {
    /**
     * Armed while the first card is a placeholder; lent to the card that replaces it, not to the
     * slot (slot 0 would hand the key to whatever lands there); released once the ring has left
     * the row, since swapping the node earlier is visible for nothing. [isActiveRow] is only read
     * once the pin could be released, so rows without one don't follow every focus move.
     */
    fun next(firstIsPlaceholder: Boolean, firstItemKey: String?, isActiveRow: () -> Boolean): FirstCardPin {
        var result = this
        if (firstIsPlaceholder && !result.spent) result = result.copy(pinned = true)
        if (result.pinned && !firstIsPlaceholder && result.itemKey == null) {
            result = result.copy(itemKey = firstItemKey)
        }
        if (result.pinned && !firstIsPlaceholder && !isActiveRow()) {
            result = FirstCardPin(pinned = false, spent = true, itemKey = null)
        }
        return result
    }
}

/** A row card's logo when it is first composed: the data layer's frozen value, else the logo. */
internal fun initialCardLogoUrl(frozenLogoUrl: String?, logo: String?): String? =
    frozenLogoUrl?.takeIf { !it.isPlaceholder() } ?: logo?.takeIf { !it.isPlaceholder() }

/**
 * A row card's logo, frozen so enrichment refreshes don't make it flicker. A missing logo takes
 * the item's logo; the enriched logo replaces it outside landscape (manual artwork lands at once),
 * and in landscape only when there was no logo at all (otherwise a card whose addon ships no logo
 * would fall back to the title text for good, even though TMDB has one).
 */
internal fun resolveCardLogoUrl(
    held: String?,
    logo: String?,
    enrichedLogoUrl: String?,
    useLandscapeOverlayTreatment: Boolean
): String? {
    var result = held
    if ((result.isNullOrBlank() || result.isPlaceholder()) && !logo.isNullOrBlank() && !logo.isPlaceholder()) {
        result = logo
    }
    if (!enrichedLogoUrl.isNullOrBlank() && result != enrichedLogoUrl) {
        if (!useLandscapeOverlayTreatment || result.isNullOrBlank()) result = enrichedLogoUrl
    }
    return result
}

/** A row card's backdrop when it is first composed: the data layer's frozen value, else the backdrop. */
internal fun initialCardBackdropUrl(frozenBackdropUrl: String?, backdrop: String?): String? =
    frozenBackdropUrl?.takeIf { !it.isPlaceholder() } ?: backdrop?.takeIf { !it.isPlaceholder() }

/**
 * A row card's backdrop, frozen in landscape so an enrichment update doesn't reload the image.
 * A missing backdrop takes the item's backdrop; outside landscape the enriched one replaces it.
 */
internal fun resolveCardBackdropUrl(
    held: String?,
    backdrop: String?,
    enrichedBackdropUrl: String?,
    useLandscapeOverlayTreatment: Boolean
): String? {
    var result = held
    if ((result.isNullOrBlank() || result.isPlaceholder()) && !backdrop.isNullOrBlank() && !backdrop.isPlaceholder()) {
        result = backdrop
    }
    if (!useLandscapeOverlayTreatment && !enrichedBackdropUrl.isNullOrBlank() && result != enrichedBackdropUrl) {
        result = enrichedBackdropUrl
    }
    return result
}
