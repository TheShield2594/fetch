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
        assertEquals(listOf("--hwdec=auto-safe", "--fs"), MpvArgs.build(PlayerSettings()))
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
                "--audio-spdif=ac3,eac3,dts,dts-hd,truehd",
                "--sub-file=https://subs/a.srt",
                "--volume=80",
                "--sub-font=Noto Sans"
            ),
            args
        )
    }
}
