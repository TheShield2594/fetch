package com.fetch.tv.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class YouTubeDetailsTest {

    private fun load(name: String): JsonElement = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/youtube/$name.json")!!.bufferedReader().use { it.readText() }
    )

    // --- The watch page's description (a web `next` response, trimmed; "Me at the zoo") ---

    @Test
    fun `the watch page gives the description and numbers`() {
        val details = YouTubeDetailsParser.parseVideoDetails(load("next_details"))!!
        assertEquals("Me at the zoo", details.title)
        assertEquals("448,566,342 views", details.viewCount)
        assertEquals("Apr 23, 2005", details.published)
        assertEquals("6.74M subscribers", details.subscriberCount)
        assertTrue(details.description.startsWith("All right, so here we are"))
        assertTrue(details.comments.isEmpty())
    }

    @Test
    fun `the watch page names the token that loads the comments`() {
        val token = YouTubeDetailsParser.parseCommentsContinuation(load("next_details"))
        assertEquals("Eg0SC2pOUVhBQzlJVlJ3GAYyJSIRIgtqTlFYQUM5SVZSdzAAeAJCEGNvbW1lbnRzLXNlY3Rpb24%3D", token)
    }

    @Test
    fun `a page without the two-column layout has no details`() {
        assertNull(YouTubeDetailsParser.parseVideoDetails(Json.parseToJsonElement("""{"contents":{}}""")))
        assertNull(YouTubeDetailsParser.parseCommentsContinuation(Json.parseToJsonElement("{}")))
    }

    // --- Comments: threads keyed into frameworkUpdates, as YouTube sends them today ---

    @Test
    fun `comments come from the entities their threads point to, in thread order`() {
        val page = YouTubeDetailsParser.parseComments(load("comments"))
        assertEquals(3, page.comments.size)
        val first = page.comments[0]
        assertEquals("We're so honored that the first ever YouTube video was filmed here!", first.text)
        assertEquals("@viewer1", first.author)
        assertEquals("6 years ago", first.published)
        assertEquals("4.8M", first.likeCount)
        assertEquals("986", first.replyCount)
        assertTrue(first.isPinned)
        assertFalse(first.isCreator)
        assertEquals("Second comment, with \"quotes\".", page.comments[1].text)
        assertTrue(page.comments[2].isCreator)
    }

    @Test
    fun `the comments header gives the count, and the last item the next page`() {
        val page = YouTubeDetailsParser.parseComments(load("comments"))
        assertEquals("10,644,286", page.count)
        assertTrue(page.continuation!!.startsWith("Eg0SC2pOUVhBQzlJVlJ3"))
    }

    @Test
    fun `older responses carry the comment itself`() {
        val page = YouTubeDetailsParser.parseComments(
            Json.parseToJsonElement(
                """
                {"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[
                  {"commentThreadRenderer":{"comment":{"commentRenderer":{
                    "commentId":"c1","authorText":{"simpleText":"@old"},
                    "contentText":{"runs":[{"text":"Hello "},{"text":"there"}]},
                    "publishedTimeText":{"runs":[{"text":"2 years ago"}]},
                    "voteCount":{"simpleText":"12"},"replyCount":3,"authorIsChannelOwner":true}}}}
                ]}}]}
                """
            )
        )
        val comment = page.comments.single()
        assertEquals("Hello there", comment.text)
        assertEquals("@old", comment.author)
        assertEquals("12", comment.likeCount)
        assertEquals("3", comment.replyCount)
        assertTrue(comment.isCreator)
        assertNull(page.continuation)
    }

    // --- Search filters ---

    private fun bytes(params: String?) = Base64.getDecoder().decode(params!!).map { it.toInt() and 0xff }

    @Test
    fun `no filters send no params, as YouTube's own search box`() {
        assertNull(YouTubeSearchFilter().params)
        assertTrue(YouTubeSearchFilter().isDefault)
    }

    @Test
    fun `single filters match the params YouTube's Filters menu sends`() {
        assertEquals("CAI=", YouTubeSearchFilter(sort = YouTubeSearchFilter.Sort.UPLOAD_DATE).params)
        assertEquals("CAM=", YouTubeSearchFilter(sort = YouTubeSearchFilter.Sort.VIEW_COUNT).params)
        assertEquals("EgIQAQ==", YouTubeSearchFilter(type = YouTubeSearchFilter.Type.VIDEO).params)
        assertEquals("EgIIAw==", YouTubeSearchFilter(uploadDate = YouTubeSearchFilter.UploadDate.THIS_WEEK).params)
        assertEquals("EgIYAg==", YouTubeSearchFilter(duration = YouTubeSearchFilter.Duration.OVER_20_MINUTES).params)
    }

    @Test
    fun `filters combine into one message after the sort`() {
        val params = YouTubeSearchFilter(
            sort = YouTubeSearchFilter.Sort.UPLOAD_DATE,
            uploadDate = YouTubeSearchFilter.UploadDate.TODAY,
            type = YouTubeSearchFilter.Type.VIDEO,
            duration = YouTubeSearchFilter.Duration.UNDER_4_MINUTES
        ).params
        assertEquals(listOf(0x08, 2, 0x12, 6, 0x08, 2, 0x10, 1, 0x18, 1), bytes(params))
    }

    // --- Resume ---

    @Test
    fun `Fetch's saved place stays unless YouTube's progress is clearly further on`() {
        // Saved at 40% of a 10-minute video.
        assertFalse(YouTubeResume.youTubeIsAhead(240_000, 600_000, watchedPercent = null))
        assertFalse(YouTubeResume.youTubeIsAhead(240_000, 600_000, watchedPercent = 42))
        assertTrue(YouTubeResume.youTubeIsAhead(240_000, 600_000, watchedPercent = 60))
        // Finished elsewhere.
        assertTrue(YouTubeResume.youTubeIsAhead(240_000, 600_000, watchedPercent = 100))
        // Fetch is further on than the card (it was loaded before this playback).
        assertFalse(YouTubeResume.youTubeIsAhead(500_000, 600_000, watchedPercent = 30))
    }
}
