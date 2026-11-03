package com.fetch.desktop.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile menus' feedback tokens ("Not interested", "Remove from watch history"), in the shapes
 * the TV client sends: SmartTube's saved TV browse responses (in MediaServiceCore), trimmed
 * to one tile each.
 */
class YouTubeFeedbackTokenTest {

    private fun page(tile: String) = YouTubeResponseParser.parsePage(
        Json.parseToJsonElement(
            """
            {"continuationContents": {"sectionListContinuation": {"contents": [
              {"shelfRenderer": {"content": {"horizontalListRenderer": {"items": [$tile]}}}}
            ]}}}
            """
        )
    )

    private fun video(tile: String) = page(tile).shelves.single().videos.single()

    private fun tile(menu: String) = """
        {"tileRenderer": {
          "contentType": "TILE_CONTENT_TYPE_VIDEO",
          "onSelectCommand": {"watchEndpoint": {"videoId": "vid"}},
          "metadata": {"tileMetadataRenderer": {"title": {"simpleText": "A video"}}},
          $menu
        }}
    """

    private fun item(text: String, endpoint: String) =
        """{"menuServiceItemRenderer": {"text": {"runs": [{"text": "$text"}]}, $endpoint}}"""

    private fun feedback(token: String) =
        """"serviceEndpoint": {"feedbackEndpoint": {"feedbackToken": "$token", "uiActions": {"hideEnclosingContainer": true}}}"""

    // The home feed's long-press menu (2025.07.30_home_continuation.json).
    private val homeMenu = """
        "onLongPressCommand": {"showMenuCommand": {"menu": {"menuRenderer": {"items": [
          {"menuNavigationItemRenderer": {"text": {"runs": [{"text": "Play"}]}, "navigationEndpoint": {"watchEndpoint": {"videoId": "vid"}}}},
          ${item("Save to Watch later", """"serviceEndpoint": {"playlistEditEndpoint": {"playlistId": "WL"}}""")},
          ${item("Not interested", feedback("NOT_INTERESTED_TOKEN"))},
          ${item("Don't recommend channel", """"command": {"openPopupAction": {"popup": {"overlaySectionRenderer": {"overlay": {
            "overlayTwoPanelRenderer": {"actionPanel": {"overlayPanelRenderer": {"content": {"overlayPanelItemListRenderer": {"items": [
              {"compactLinkRenderer": {"serviceEndpoint": {"commandExecutorCommand": {"commands": [
                {"feedbackEndpoint": {"feedbackToken": "CHANNEL_TOKEN"}}
              ]}}}}
            ]}}}}}
          }}}}}""")}
        ]}}}}
    """

    @Test
    fun `a home tile's menu gives its not interested token, not the channel's`() {
        val video = video(tile(homeMenu))

        assertEquals("NOT_INTERESTED_TOKEN", video.notInterestedToken)
        assertNull(video.removeFromHistoryToken)
    }

    @Test
    fun `a subscriptions tile's Hide is its not interested token`() {
        val menu = """
            "onLongPressCommand": {"showMenuCommand": {"menu": {"menuRenderer": {"items": [
              ${item("Hide", feedback("HIDE_TOKEN"))}
            ]}}}}
        """

        assertEquals("HIDE_TOKEN", video(tile(menu)).notInterestedToken)
    }

    @Test
    fun `the history feed's token removes the video from history`() {
        // history_25.01.2023.json: the older tile menu, under "menu".
        val menu = """
            "menu": {"menuRenderer": {"items": [
              ${item("Remove from watch history", feedback("HISTORY_TOKEN"))}
            ]}}
        """
        val history = YouTubeResponseParser.asHistory(page(tile(menu))).shelves.single().videos.single()

        assertEquals("HISTORY_TOKEN", history.removeFromHistoryToken)
        assertNull(history.notInterestedToken)
    }

    @Test
    fun `an item's icon says which token it is`() {
        val menu = """
            "menu": {"menuRenderer": {"items": [
              {"menuServiceItemRenderer": {"icon": {"iconType": "DELETE"}, ${feedback("REMOVE_TOKEN")}}},
              {"menuServiceItemRenderer": {"icon": {"iconType": "NOT_INTERESTED"}, ${feedback("NI_TOKEN")}}}
            ]}}
        """
        val video = video(tile(menu))

        assertEquals("REMOVE_TOKEN", video.removeFromHistoryToken)
        assertEquals("NI_TOKEN", video.notInterestedToken)
    }

    @Test
    fun `a tile without feedback items has no tokens`() {
        val video = video(tile(""""menu": {"menuRenderer": {"items": []}}"""))

        assertNull(video.notInterestedToken)
        assertNull(video.removeFromHistoryToken)
    }

    @Test
    fun `feedback counts as sent unless YouTube says it wasn't processed`() {
        assertTrue(processed("""{"feedbackResponses": [{"isProcessed": true}]}"""))
        assertTrue(processed("""{}"""))
        assertFalse(processed("""{"feedbackResponses": [{"isProcessed": false}]}"""))
    }

    private fun processed(body: String) = YouTubeResponseParser.parseFeedbackProcessed(Json.parseToJsonElement(body))
}
