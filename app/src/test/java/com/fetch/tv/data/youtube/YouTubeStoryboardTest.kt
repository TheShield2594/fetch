package com.fetch.tv.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeStoryboardTest {

    // The shape of a real spec: a base URL, then the "default" picture and two levels of sheets.
    private val spec = "https://i.ytimg.com/sb/abcdefghijk/storyboard3_L\$L/\$N.jpg?sqp=xyz" +
        "|48#27#100#10#10#0#default#rs\$AAA" +
        "|80#45#58#10#10#2000#M\$M#rs\$BBB" +
        "|160#90#58#5#5#2000#M\$M#rs\$CCC"

    @Test
    fun `the widest level is used`() {
        val board = YouTubeStoryboard.parse(spec)!!
        assertEquals(160, board.frameWidth)
        assertEquals(90, board.frameHeight)
        assertEquals(5, board.columns)
        assertEquals(2000L, board.intervalMs)
    }

    @Test
    fun `a frame is found on its sheet by time`() {
        val board = YouTubeStoryboard.parse(spec)!!
        // 61 s is frame 30: the second sheet (25 a sheet), its sixth frame, the second row's first.
        val frame = board.frameAt(61_000, 116_000)!!
        assertEquals("https://i.ytimg.com/sb/abcdefghijk/storyboard3_L2/M1.jpg?sqp=xyz&sigh=rs\$CCC", frame.sheetUrl)
        assertEquals(0, frame.x)
        assertEquals(90, frame.y)
        assertEquals(YouTubeStoryboard.Frame(frame.sheetUrl, 0, 90, 160, 90), frame)
    }

    @Test
    fun `positions past the end show the last frame`() {
        val board = YouTubeStoryboard.parse(spec)!!
        val frame = board.frameAt(10_000_000, 116_000)!!
        // Frame 57: third sheet (index 2), its eighth frame: row 1, column 2.
        assertEquals("https://i.ytimg.com/sb/abcdefghijk/storyboard3_L2/M2.jpg?sqp=xyz&sigh=rs\$CCC", frame.sheetUrl)
        assertEquals(320, frame.x)
        assertEquals(90, frame.y)
    }

    @Test
    fun `without an interval the frames spread over the video`() {
        val board = YouTubeStoryboard.parse("https://x/\$L/\$N.jpg?a|100#50#10#10#1#0#M\$M#s")!!
        // 10 frames over 100 s: 55 s is frame 5.
        assertEquals(500, board.frameAt(55_000, 100_000)!!.x)
        assertNull(board.frameAt(55_000, 0))
    }

    @Test
    fun `an unreadable spec gives no storyboard`() {
        assertNull(YouTubeStoryboard.parse(null))
        assertNull(YouTubeStoryboard.parse(""))
        assertNull(YouTubeStoryboard.parse("not a url|1#2"))
        assertNull(YouTubeStoryboard.parse("https://x|48#27#100#10#10#0#default#s"))
    }
}
