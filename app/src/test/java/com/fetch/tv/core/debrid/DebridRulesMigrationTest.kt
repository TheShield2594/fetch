package com.fetch.tv.core.debrid

import com.fetch.tv.core.streams.ranking.AudioFormat
import com.fetch.tv.core.streams.ranking.ReleaseSource
import com.fetch.tv.core.streams.ranking.StreamLanguage
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.core.streams.ranking.StreamResolution
import com.fetch.tv.core.streams.ranking.VideoCodec
import com.fetch.tv.domain.model.DebridStreamAudioTag
import com.fetch.tv.domain.model.DebridStreamEncode
import com.fetch.tv.domain.model.DebridStreamLanguage
import com.fetch.tv.domain.model.DebridStreamPreferences
import com.fetch.tv.domain.model.DebridStreamQuality
import com.fetch.tv.domain.model.DebridStreamResolution
import com.fetch.tv.domain.model.DebridStreamVisualTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridRulesMigrationTest {
    @Test
    fun `default debrid rules change nothing`() {
        val rules = StreamRankingRules()
        assertEquals(rules, DebridRulesMigration.merge(rules, DebridStreamPreferences()))
    }

    @Test
    fun `moves every debrid rule that has a ranking equivalent`() {
        val merged = DebridRulesMigration.merge(
            StreamRankingRules(),
            DebridStreamPreferences(
                maxResults = 20,
                maxPerResolution = 3,
                maxPerQuality = 2,
                sizeMinGb = 1,
                sizeMaxGb = 40,
                requiredResolutions = listOf(DebridStreamResolution.P1080, DebridStreamResolution.P720),
                excludedQualities = listOf(DebridStreamQuality.HDTV, DebridStreamQuality.CAM),
                excludedVisualTags = listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.THREE_D),
                excludedAudioTags = listOf(DebridStreamAudioTag.AAC),
                requiredEncodes = listOf(DebridStreamEncode.HEVC),
                preferredLanguages = listOf(DebridStreamLanguage.IT, DebridStreamLanguage.EN),
                excludedLanguages = listOf(DebridStreamLanguage.HI),
                requiredReleaseGroups = listOf("FLUX"),
                excludedReleaseGroups = listOf("YIFY")
            )
        )
        assertEquals(20, merged.maxResults)
        assertEquals(3, merged.maxPerResolution)
        assertEquals(2, merged.maxPerSource)
        assertEquals(1.0, merged.minSizeGb!!, 0.0)
        assertEquals(40.0, merged.maxSizeGb!!, 0.0)
        assertEquals(StreamResolution.FHD_1080, merged.maxResolution)
        assertEquals(StreamResolution.HD_720, merged.minResolution)
        assertEquals(setOf(ReleaseSource.HDTV, ReleaseSource.CAM), merged.excludedSources)
        assertTrue(merged.excludeDolbyVision)
        assertTrue(merged.exclude3d)
        assertEquals(setOf(AudioFormat.AAC), merged.excludedAudio)
        assertEquals(VideoCodec.entries.filter { it != VideoCodec.UNKNOWN && it != VideoCodec.HEVC }.toSet(), merged.excludedCodecs)
        assertEquals(listOf(StreamLanguage.ITALIAN, StreamLanguage.ENGLISH), merged.preferredLanguages)
        assertEquals(setOf(StreamLanguage.HINDI), merged.excludedLanguages)
        assertEquals(listOf("FLUX"), merged.preferredReleaseGroups)
        assertEquals(listOf("YIFY"), merged.excludedReleaseGroups)
    }

    @Test
    fun `existing ranking rules win when they are stricter`() {
        val merged = DebridRulesMigration.merge(
            StreamRankingRules(maxSizeGb = 10.0, maxResults = 5, preferredReleaseGroups = listOf("flux")),
            DebridStreamPreferences(sizeMaxGb = 40, maxResults = 20, requiredReleaseGroups = listOf("FLUX"))
        )
        assertEquals(10.0, merged.maxSizeGb!!, 0.0)
        assertEquals(5, merged.maxResults)
        assertEquals(listOf("flux"), merged.preferredReleaseGroups)
    }
}
