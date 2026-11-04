package com.fetch.tv.ui.screens.stats

import com.fetch.tv.data.website.WebsiteMonthlyCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsPresentationTest {
    @Test
    fun `badges are earned at each threshold reached, with the next one of each kind still to come`() {
        val summary = milestoneSummary(totalMovies = 55, totalEpisodes = 99, longestStreak = 7)

        assertEquals(
            listOf(
                Milestone(MilestoneKind.MOVIES, 10),
                Milestone(MilestoneKind.MOVIES, 50),
                Milestone(MilestoneKind.STREAK, 3),
                Milestone(MilestoneKind.STREAK, 7)
            ),
            summary.earned
        )
        assertEquals(
            listOf(
                MilestoneProgress(Milestone(MilestoneKind.MOVIES, 100), 55),
                MilestoneProgress(Milestone(MilestoneKind.EPISODES, 100), 99),
                MilestoneProgress(Milestone(MilestoneKind.STREAK, 30), 7)
            ),
            summary.upcoming
        )
    }

    @Test
    fun `a kind with every badge earned has nothing left to come`() {
        val summary = milestoneSummary(totalMovies = 600, totalEpisodes = 0, longestStreak = 0)

        assertEquals(5, summary.earned.count { it.kind == MilestoneKind.MOVIES })
        assertEquals(listOf(MilestoneKind.EPISODES, MilestoneKind.STREAK), summary.upcoming.map { it.milestone.kind })
    }

    @Test
    fun `month over month compares the last two months`() {
        val monthly = listOf(
            WebsiteMonthlyCount("2026-09", movies = 1, episodes = 1),
            WebsiteMonthlyCount("2026-10", movies = 2, episodes = 2),
            WebsiteMonthlyCount("2026-11", movies = 3, episodes = 3)
        )

        assertEquals(MonthComparison(diff = 2, percent = 50), monthOverMonth(monthly))
    }

    @Test
    fun `month over month has no percentage after an empty month, and nothing with one month`() {
        val afterEmpty = monthOverMonth(listOf(WebsiteMonthlyCount("2026-10"), WebsiteMonthlyCount("2026-11", movies = 4)))

        assertEquals(MonthComparison(diff = 4, percent = null), afterEmpty)
        assertNull(monthOverMonth(listOf(WebsiteMonthlyCount("2026-11", movies = 4))))
    }

    @Test
    fun `bars are a fraction of the tallest and never below zero or above one`() {
        assertEquals(0.5f, barFraction(5, 10))
        assertEquals(0f, barFraction(0, 10))
        assertEquals(0f, barFraction(3, 0))
        assertEquals(1f, barFraction(12, 10))
    }

    @Test
    fun `the year in review offers this year and the five before it`() {
        assertEquals(listOf(2026, 2025, 2024, 2023, 2022, 2021), yearInReviewOptions(2026))
    }

    @Test
    fun `months are read from the website's year-month keys`() {
        assertEquals(0, monthIndexOf("2026-01"))
        assertEquals(11, monthIndexOf("2026-12"))
        assertNull(monthIndexOf("2026-13"))
        assertNull(monthIndexOf("2026"))
        assertEquals(2026, yearOf("2026-04"))
    }

    @Test
    fun `ratings print as the website prints them`() {
        assertEquals("8.4", formatTenPointRating(8.44))
        assertEquals("8", formatTenPointRating(8.0))
        assertEquals("4.5", formatStars(9.0))
        assertEquals("5", formatStars(10.0))
    }
}
