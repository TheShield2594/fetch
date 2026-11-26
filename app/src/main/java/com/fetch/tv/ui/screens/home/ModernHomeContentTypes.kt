package com.fetch.tv.ui.screens.home

/*
 * Lazy list content types for Home (TheShield2594/fetch#826). Compose only reuses a slot for an
 * item of the same content type, so a type should name the composable an item becomes: one type
 * per composable, not per data value. Too fine (one per catalog media type) and a `movie` card
 * can't reuse a `series` card's slot although both are the same card; too coarse (Continue
 * Watching sharing a type with collection rows) and a slot is reused for a different layout.
 */

internal const val MODERN_ROW_CONTENT_TYPE_CONTINUE_WATCHING = "modern_cw_row"
internal const val MODERN_ROW_CONTENT_TYPE_CATALOG = "modern_catalog_row"
internal const val MODERN_ROW_CONTENT_TYPE_OTHER = "modern_home_row"

internal const val MODERN_CARD_CONTENT_TYPE_CONTINUE_WATCHING = "modern_cw_card"
internal const val MODERN_CARD_CONTENT_TYPE_CATALOG = "modern_catalog_card"
internal const val MODERN_CARD_CONTENT_TYPE_PLACEHOLDER = "placeholder"
internal const val MODERN_CARD_CONTENT_TYPE_COLLECTION_FOLDER = "modern_collection_folder_card"
internal const val MODERN_CARD_CONTENT_TYPE_YOUTUBE = "modern_youtube_card"

/**
 * Continue Watching style rows (Continue Watching, Upcoming, Ready tonight) lay out landscape
 * progress cards; addon catalog rows of every media type lay out the same poster cards; anything
 * else (collections, YouTube, ...) gets its own type.
 */
internal fun modernHomeRowContentType(row: HeroCarouselRow): String = when {
    isContinueWatchingStyleRow(row.key) -> MODERN_ROW_CONTENT_TYPE_CONTINUE_WATCHING
    row.apiType != null -> MODERN_ROW_CONTENT_TYPE_CATALOG
    else -> MODERN_ROW_CONTENT_TYPE_OTHER
}

/** Catalog cards share one type whatever their media type: `movie` and `series` are one composable. */
internal fun modernCarouselItemContentType(item: ModernCarouselItem): String =
    when (val payload = item.payload) {
        is ModernPayload.ContinueWatching -> MODERN_CARD_CONTENT_TYPE_CONTINUE_WATCHING
        is ModernPayload.Catalog ->
            if (payload.itemId.startsWith("__placeholder_")) MODERN_CARD_CONTENT_TYPE_PLACEHOLDER
            else MODERN_CARD_CONTENT_TYPE_CATALOG
        is ModernPayload.CollectionFolder -> MODERN_CARD_CONTENT_TYPE_COLLECTION_FOLDER
        is ModernPayload.YouTubeVideo -> MODERN_CARD_CONTENT_TYPE_YOUTUBE
    }
