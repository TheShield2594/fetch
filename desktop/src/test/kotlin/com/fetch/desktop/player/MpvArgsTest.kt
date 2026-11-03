package com.fetch.desktop.player

import com.fetch.desktop.data.HwDec
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.SubtitleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvArgsTest {

    @Test
    fun `defaults are hardware decoding and fullscreen only`() {
        // Hearing-impaired text is removed by default, as on TV.
        assertEquals(listOf("--hwdec=auto-safe", "--fs", "--sub-filter-sdh=yes"), MpvArgs.build(PlayerSettings()))
    }

    @Test
    fun `languages are passed as both two and three letter codes`() {
        val args = MpvArgs.build(PlayerSettings(audioLanguage = "ja", subtitleLanguage = "en", fullscreen = false))
        assertTrue("--alang=ja,jpn" in args)
        assertTrue("--slang=en,eng" in args)
    }

    @Test
    fun `subtitles off drops addon subtitle files`() {
        val args = MpvArgs.build(PlayerSettings(subtitleMode = SubtitleMode.OFF), listOf("https://subs/a.srt"))
        assertTrue("--sid=no" in args)
        assertFalse(args.any { it.startsWith("--sub-file") })
    }

    @Test
    fun `style, passthrough, decoding and extra options`() {
        val args = MpvArgs.build(
            PlayerSettings(
                subtitleScale = 1.25,
                subtitleBackground = true,
                audioPassthrough = true,
                hwdec = HwDec.OFF,
                extraMpvArgs = """--volume=80 --sub-font="Noto Sans"""",
                fullscreen = false
            ),
            listOf("https://subs/a.srt")
        )
        assertEquals(
            listOf(
                "--hwdec=no",
                "--sub-scale=1.25",
                "--sub-border-style=opaque-box",
                "--sub-back-color=0.0/0.0/0.0/0.6",
                "--sub-filter-sdh=yes",
                "--audio-spdif=ac3,eac3,dts,dts-hd,truehd",
                "--sub-file=https://subs/a.srt",
                "--volume=80",
                "--sub-font=Noto Sans"
            ),
            args
        )
    }

    @Test
    fun `secondary languages follow the first`() {
        val args = MpvArgs.build(PlayerSettings(audioLanguage = "ja", secondaryAudioLanguage = "en", secondarySubtitleLanguage = "fr", fullscreen = false))
        assertTrue("--alang=ja,jpn,en,eng" in args)
        assertTrue(args.any { it.startsWith("--slang=") && it.endsWith("fr,fra,fre") })
        assertEquals("de,deu,ger", MpvArgs.languageCodes("de"))
    }

    @Test
    fun `subtitle style, mixing, picture and cache`() {
        val args = MpvArgs.build(
            PlayerSettings(
                fullscreen = false,
                stripSdh = false,
                useForcedSubtitles = false,
                subtitleBold = true,
                subtitleColor = "#FFFF00",
                subtitleOutlineSize = 5.0,
                subtitleOutlineColor = "#404040",
                subtitlePosition = 90,
                assOverride = com.fetch.desktop.data.AssOverride.FORCE,
                audioChannels = com.fetch.desktop.data.AudioChannels.STEREO,
                normalizeDownmix = true,
                volumeBoostDb = 6,
                videoFit = com.fetch.desktop.data.VideoFit.CROP,
                cacheMb = 300
            )
        )
        listOf(
            "--subs-fallback-forced=no", "--sub-bold=yes", "--sub-color=#FFFF00", "--sub-border-size=5.0",
            "--sub-border-color=#404040", "--sub-pos=90", "--sub-ass-override=force", "--audio-channels=stereo",
            "--audio-normalize-downmix=yes", "--af=lavfi=[volume=6dB]", "--panscan=1.0", "--cache=yes", "--demuxer-max-bytes=300MiB"
        ).forEach { assertTrue("$it in $args", it in args) }
        assertFalse(args.any { it.startsWith("--sub-filter-sdh") })
    }

    @Test
    fun `no outline means a zero border, unless the background box is on`() {
        assertTrue("--sub-border-size=0" in MpvArgs.build(PlayerSettings(subtitleOutline = false)))
        assertFalse(MpvArgs.build(PlayerSettings(subtitleOutline = false, subtitleBackground = true)).contains("--sub-border-size=0"))
    }
}
