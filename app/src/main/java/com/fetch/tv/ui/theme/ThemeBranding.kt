package com.fetch.tv.ui.theme

import androidx.annotation.DrawableRes
import com.fetch.tv.R
import com.fetch.tv.domain.model.AppTheme

/**
 * The wordmark drawn in each theme's colour. Fetch and White use the white wordmark, and so does
 * Custom, whose colours are only known at runtime. The bitmaps come from
 * scripts/brand/generate_brand.py.
 */
@get:DrawableRes
val AppTheme.brandWordmarkResource: Int
    get() = when (this) {
        AppTheme.GOLD -> R.drawable.app_logo_wordmark_gold
        AppTheme.JADE -> R.drawable.app_logo_wordmark_jade
        AppTheme.ROSE_GOLD -> R.drawable.app_logo_wordmark_rose_gold
        AppTheme.ARCTIC_BLUE -> R.drawable.app_logo_wordmark_arctic_blue
        AppTheme.GRAPHITE -> R.drawable.app_logo_wordmark_graphite
        AppTheme.CRIMSON -> R.drawable.app_logo_wordmark_crimson
        AppTheme.OCEAN -> R.drawable.app_logo_wordmark_ocean
        AppTheme.VIOLET -> R.drawable.app_logo_wordmark_violet
        AppTheme.EMERALD -> R.drawable.app_logo_wordmark_emerald
        AppTheme.AMBER -> R.drawable.app_logo_wordmark_amber
        AppTheme.ROSE -> R.drawable.app_logo_wordmark_rose
        AppTheme.FETCH, AppTheme.WHITE, AppTheme.CUSTOM -> R.drawable.app_logo_wordmark
    }
