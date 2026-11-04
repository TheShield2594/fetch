package com.fetch.tv.ui.screens.stats

import com.fetch.tv.data.website.WebsiteMonthlyCount
import kotlin.math.roundToInt

/** What a badge counts. */
enum class MilestoneKind { MOVIES, EPISODES, STREAK }

data class Milestone(val kind: MilestoneKind, val threshold: Int)

/** The next badge of one kind, and how far along it [value] is. */
data class MilestoneProgress(val milestone: Milestone, val value: Int)

/** The same badges the website's stats page awards, so a profile has the same ones on both. */
internal val StatsMilestones: Map<MilestoneKind, List<Int>> = linkedMapOf(
    MilestoneKind.MOVIES to listOf(10, 50, 100, 250, 500),
    MilestoneKind.EPISODES to listOf(100, 500, 1_000, 2_500),
    MilestoneKind.STREAK to listOf(3, 7, 30, 100)
)

data class StatsMilestoneSummary(
    val earned: List<Milestone>,
    val upcoming: List<MilestoneProgress>
)

internal fun milestoneSummary(totalMovies: Int, totalEpisodes: Int, longestStreak: Int): StatsMilestoneSummary {
    val values = mapOf(
        MilestoneKind.MOVIES to totalMovies,
        MilestoneKind.EPISODES to totalEpisodes,
        MilestoneKind.STREAK to longestStreak
    )
    val earned = mutableListOf<Milestone>()
    val upcoming = mutableListOf<MilestoneProgress>()
    StatsMilestones.forEach { (kind, thresholds) ->
        val value = values.getValue(kind)
        thresholds.filter { value >= it }.mapTo(earned) { Milestone(kind, it) }
        thresholds.firstOrNull { value < it }?.let { upcoming += MilestoneProgress(Milestone(kind, it), value) }
    }
    return StatsMilestoneSummary(earned, upcoming)
}

/** This month against last month; [percent] is null when last month had nothing to compare with. */
data class MonthComparison(val diff: Int, val percent: Int?)

internal fun monthOverMonth(monthly: List<WebsiteMonthlyCount>): MonthComparison? {
    if (monthly.size < 2) return null
    val current = monthly[monthly.size - 1].total
    val previous = monthly[monthly.size - 2].total
    val diff = current - previous
    val percent = if (previous > 0) (diff * 100.0 / previous).roundToInt() else null
    return MonthComparison(diff, percent)
}

/** How tall [value] stands against the tallest of its chart, from 0 to 1. */
internal fun barFraction(value: Int, max: Int): Float =
    if (max <= 0 || value <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)

/** The years the year-in-review offers: this one first, then the five before it, as on the website. */
internal fun yearInReviewOptions(currentYear: Int, count: Int = 6): List<Int> = List(count) { currentYear - it }

/** `2026-03` → 2 (0-based month), or null for anything else. */
internal fun monthIndexOf(month: String): Int? {
    val parts = month.split("-")
    if (parts.size != 2) return null
    val value = parts[1].toIntOrNull() ?: return null
    return (value - 1).takeIf { it in 0..11 }
}

/** `2026-03` → 2026, or null. */
internal fun yearOf(month: String): Int? = month.split("-").firstOrNull()?.toIntOrNull()

/** A rating out of ten as the website prints it: one decimal, no trailing `.0`. */
internal fun formatTenPointRating(rating: Double): String {
    val rounded = (rating * 10).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/** A stored 1-10 rating as stars out of five, `9` → `4.5`. */
internal fun formatStars(rating: Double): String = formatTenPointRating(rating / 2)
