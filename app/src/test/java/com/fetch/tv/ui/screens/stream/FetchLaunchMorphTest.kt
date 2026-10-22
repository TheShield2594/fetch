package com.fetch.tv.ui.screens.stream

import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FetchLaunchMorphTest {

    @Test
    fun `answered addons lead in the order they answered`() {
        val chips = listOf(
            SourceChipItem("Torrentio", SourceChipStatus.LOADING),
            SourceChipItem("Comet", SourceChipStatus.SUCCESS),
            SourceChipItem("MediaFusion", SourceChipStatus.ERROR),
            SourceChipItem("Jackett", SourceChipStatus.LOADING)
        )

        val ordered = addonsInAnswerOrder(chips, answerOrder = listOf("MediaFusion", "Comet"))

        assertEquals(listOf("MediaFusion", "Comet", "Torrentio", "Jackett"), ordered.map { it.name })
    }

    @Test
    fun `an answer not yet recorded keeps its place after the recorded ones`() {
        val chips = listOf(
            SourceChipItem("Torrentio", SourceChipStatus.SUCCESS),
            SourceChipItem("Comet", SourceChipStatus.SUCCESS)
        )

        val ordered = addonsInAnswerOrder(chips, answerOrder = listOf("Comet"))

        assertEquals(listOf("Comet", "Torrentio"), ordered.map { it.name })
    }

    @Test
    fun `panel content shows only at the end of the morph`() {
        assertEquals(0f, launchContentAlpha(0f), 0f)
        assertEquals(0f, launchContentAlpha(0.5f), 0f)
        assertEquals(1f, launchContentAlpha(1f), 0f)
        assertEquals(0.5f, launchContentAlpha(0.775f), 0.001f)
    }
}
