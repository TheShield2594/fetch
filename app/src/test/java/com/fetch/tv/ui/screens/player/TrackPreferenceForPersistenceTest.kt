package com.fetch.tv.ui.screens.player

import com.fetch.tv.ui.screens.player.PlayerRuntimeController.RememberedSubtitleSelection
import com.fetch.tv.ui.screens.player.PlayerRuntimeController.RememberedTrackSelection
import com.fetch.tv.ui.screens.player.PlayerRuntimeController.TrackPreference
import org.junit.Assert.assertEquals
import org.junit.Test

/** #691: a save must not erase the title's loaded choice once restore has drained it. */
class TrackPreferenceForPersistenceTest {

    private val japanese = RememberedTrackSelection(language = "ja", name = "Japanese")
    private val french = RememberedTrackSelection(language = "fr", name = "French")
    private val englishSubs = RememberedSubtitleSelection.Internal(
        RememberedTrackSelection(language = "en", name = "English")
    )

    @Test
    fun `fully drained pending keeps the loaded audio and subtitle`() {
        val loaded = TrackPreference(audio = japanese, subtitle = englishSubs)
        val saved = trackPreferenceForPersistence(remembered = null, loaded = loaded, pending = null)
        assertEquals(loaded, saved)
    }

    @Test
    fun `partly drained pending still keeps the loaded audio`() {
        val loaded = TrackPreference(audio = japanese, subtitle = englishSubs)
        val pending = TrackPreference(audio = null, subtitle = englishSubs)
        val saved = trackPreferenceForPersistence(remembered = null, loaded = loaded, pending = pending)
        assertEquals(loaded, saved)
    }

    @Test
    fun `an audio pick this session keeps the loaded subtitle`() {
        val loaded = TrackPreference(audio = japanese, subtitle = englishSubs)
        val remembered = TrackPreference(audio = french)
        val saved = trackPreferenceForPersistence(remembered = remembered, loaded = loaded, pending = null)
        assertEquals(TrackPreference(audio = french, subtitle = englishSubs), saved)
    }

    @Test
    fun `disabling subtitles this session overrides the loaded subtitle`() {
        val loaded = TrackPreference(audio = japanese, subtitle = englishSubs)
        val remembered = TrackPreference(subtitle = RememberedSubtitleSelection.Disabled)
        val saved = trackPreferenceForPersistence(remembered = remembered, loaded = loaded, pending = null)
        assertEquals(TrackPreference(audio = japanese, subtitle = RememberedSubtitleSelection.Disabled), saved)
    }

    @Test
    fun `nothing loaded and nothing picked saves an empty preference`() {
        assertEquals(TrackPreference(), trackPreferenceForPersistence(null, null, null))
    }
}
