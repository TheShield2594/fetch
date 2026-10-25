package com.fetch.tv.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeResponseParserTest {

    private fun parse(text: String) = YouTubeResponseParser.parseShelves(Json.parseToJsonElement(text))

    private fun tile(videoId: String, title: String, type: String = "TILE_CONTENT_TYPE_VIDEO") = """
        {"tileRenderer": {
          "contentType": "$type",
          "onSelectCommand": {"watchEndpoint": {"videoId": "$videoId"}},
          "header": {"tileHeaderRenderer": {
            "thumbnail": {"thumbnails": [
              {"url": "https://i.ytimg.com/vi/$videoId/default.jpg", "width": 120},
              {"url": "https://i.ytimg.com/vi/$videoId/hq720.jpg", "width": 1280}
            ]},
            "thumbnailOverlays": [
              {"thumbnailOverlayTimeStatusRenderer": {"text": {"simpleText": "12:34"}}},
              {"thumbnailOverlayResumePlaybackRenderer": {"percentDurationWatched": 40}}
            ]
          }},
          "metadata": {"tileMetadataRenderer": {
            "title": {"simpleText": "$title"},
            "lines": [
              {"lineRenderer": {"items": [{"lineItemRenderer": {"text": {"runs": [{"text": "Some Channel"}]}}}]}},
              {"lineRenderer": {"items": [
                {"lineItemRenderer": {"text": {"simpleText": "1.2M views"}}},
                {"lineItemRenderer": {"text": {"simpleText": "•"}}},
                {"lineItemRenderer": {"text": {"simpleText": "3 days ago"}}}
              ]}}
            ]
          }}
        }}
    """

    @Test
    fun `tv home shelves keep their titles and tiles`() {
        val shelves = parse(
            """
            {"contents": {"tvBrowseRenderer": {"content": {"tvSurfaceContentRenderer": {"content": {
              "sectionListRenderer": {"contents": [
                {"shelfRenderer": {
                  "headerRenderer": {"shelfHeaderRenderer": {"title": {"simpleText": "Recommended"}}},
                  "content": {"horizontalListRenderer": {"items": [${tile("aaa", "First")}, ${tile("bbb", "Second")}]}}
                }},
                {"shelfRenderer": {
                  "headerRenderer": {"shelfHeaderRenderer": {"title": {"runs": [{"text": "Music"}]}}},
                  "content": {"horizontalListRenderer": {"items": [${tile("ccc", "Third")}]}}
                }}
              ]}
            }}}}}}
            """
        )

        assertEquals(listOf("Recommended", "Music"), shelves.map { it.title })
        assertEquals(listOf("aaa", "bbb"), shelves[0].videos.map { it.videoId })
        val first = shelves[0].videos[0]
        assertEquals("First", first.title)
        assertEquals("Some Channel", first.channelName)
        assertEquals("1.2M views • 3 days ago", first.detail)
        assertEquals("https://i.ytimg.com/vi/aaa/hq720.jpg", first.thumbnailUrl)
        assertEquals("12:34", first.duration)
        assertEquals(40, first.watchedPercent)
        assertEquals("https://www.youtube.com/watch?v=aaa", first.watchUrl)
    }

    @Test
    fun `tiles outside any shelf form one untitled row, without duplicates`() {
        val shelves = parse(
            """{"gridRenderer": {"items": [${tile("aaa", "First")}, ${tile("aaa", "First")}, ${tile("bbb", "Second")}]}}"""
        )

        assertEquals(1, shelves.size)
        assertNull(shelves[0].title)
        assertEquals(listOf("aaa", "bbb"), shelves[0].videos.map { it.videoId })
    }

    @Test
    fun `channel and playlist tiles are skipped`() {
        val shelves = parse(
            """{"items": [${tile("chan", "A channel", "TILE_CONTENT_TYPE_CHANNEL")}, ${tile("vid", "A video")}]}"""
        )

        assertEquals(listOf("vid"), shelves.single().videos.map { it.videoId })
    }

    @Test
    fun `web video renderers are read too`() {
        val shelves = parse(
            """
            {"itemSectionRenderer": {"contents": [{"videoRenderer": {
              "videoId": "web1",
              "title": {"runs": [{"text": "Web "}, {"text": "video"}]},
              "ownerText": {"runs": [{"text": "Owner"}]},
              "shortViewCountText": {"simpleText": "10 views"},
              "lengthText": {"simpleText": "1:00"},
              "thumbnail": {"thumbnails": [{"url": "//i.ytimg.com/vi/web1/hq.jpg", "width": 480}]}
            }}]}}
            """
        )

        val video = shelves.single().videos.single()
        assertEquals("Web video", video.title)
        assertEquals("Owner", video.channelName)
        assertEquals("10 views", video.detail)
        assertEquals("1:00", video.duration)
        assertEquals("https://i.ytimg.com/vi/web1/hq.jpg", video.thumbnailUrl)
    }

    @Test
    fun `lockup view models are read, and non-video lockups skipped`() {
        val shelves = parse(
            """
            {"items": [
              {"lockupViewModel": {
                "contentId": "lock1",
                "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                "contentImage": {"thumbnailViewModel": {
                  "image": {"sources": [{"url": "https://i.ytimg.com/vi/lock1/hq.jpg", "width": "480"}]},
                  "overlays": [{"thumbnailBottomOverlayViewModel": {
                    "progressBar": {"thumbnailOverlayProgressBarViewModel": {"startPercent": 75}},
                    "badges": [{"thumbnailBadgeViewModel": {"text": "8:01"}}]
                  }}]
                }},
                "metadata": {"lockupMetadataViewModel": {
                  "title": {"content": "A lockup video"},
                  "metadata": {"contentMetadataViewModel": {"metadataRows": [
                    {"metadataParts": [{"text": {"content": "Lockup Channel"}}]},
                    {"metadataParts": [{"text": {"content": "5K views"}}, {"text": {"content": "1 week ago"}}]}
                  ]}}
                }},
                "rendererContext": {"commandContext": {"onTap": {"innertubeCommand": {"watchEndpoint": {"videoId": "lock1"}}}}}
              }},
              {"lockupViewModel": {
                "contentId": "PL123",
                "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
                "metadata": {"lockupMetadataViewModel": {"title": {"content": "A playlist"}}},
                "rendererContext": {"commandContext": {"onTap": {"innertubeCommand": {"watchEndpoint": {"videoId": "first", "playlistId": "PL123"}}}}}
              }}
            ]}
            """
        )

        val video = shelves.single().videos.single()
        assertEquals("lock1", video.videoId)
        assertEquals("A lockup video", video.title)
        assertEquals("Lockup Channel", video.channelName)
        assertEquals("5K views • 1 week ago", video.detail)
        assertEquals("https://i.ytimg.com/vi/lock1/hq.jpg", video.thumbnailUrl)
        assertEquals("8:01", video.duration)
        assertEquals(75, video.watchedPercent)
    }

    @Test
    fun `a tile without a thumbnail falls back to YouTube's default image`() {
        val shelves = parse(
            """{"tileRenderer": {"onSelectCommand": {"watchEndpoint": {"videoId": "xyz"}},
               "metadata": {"tileMetadataRenderer": {"title": {"simpleText": "No art"}}}}}"""
        )

        assertEquals("https://i.ytimg.com/vi/xyz/hqdefault.jpg", shelves.single().videos.single().thumbnailUrl)
    }

    @Test
    fun `responses with no videos give no shelves`() {
        assertTrue(parse("""{"contents": {"messageRenderer": {"text": {"simpleText": "Nothing"}}}}""").isEmpty())
    }

    @Test
    fun `the selected account is read from the accounts list`() {
        val account = YouTubeResponseParser.parseAccount(
            Json.parseToJsonElement(
                """
                {"contents": [{"accountSectionListRenderer": {"contents": [{"accountItemSectionRenderer": {"contents": [
                  {"accountItem": {"accountName": {"simpleText": "Other"}, "isSelected": false}},
                  {"accountItem": {
                    "accountName": {"simpleText": "Main Account"},
                    "channelHandle": {"simpleText": "@main"},
                    "accountPhoto": {"thumbnails": [{"url": "https://yt3.ggpht.com/photo", "width": 88}]},
                    "isSelected": true
                  }}
                ]}}]}}]}
                """
            )
        )

        assertEquals(
            YouTubeAccount("Main Account", "@main", "https://yt3.ggpht.com/photo", isSelected = true),
            account
        )
    }

    @Test
    fun `brand accounts carry their page id, and disabled ones are left out`() {
        val accounts = YouTubeResponseParser.parseAccounts(
            Json.parseToJsonElement(
                """
                {"contents": [
                  {"accountItem": {"accountName": {"simpleText": "Me"}, "accountByline": {"simpleText": "me@example.com"}, "isSelected": true}},
                  {"accountItem": {"accountName": {"simpleText": "My Brand"}, "channelHandle": {"simpleText": "@brand"},
                    "serviceEndpoint": {"selectActiveIdentityEndpoint": {"supportedTokens": [
                      {"accountStateToken": {"hasChannel": true}},
                      {"pageIdToken": {"pageId": "112233"}}
                    ]}}}},
                  {"accountItem": {"accountName": {"simpleText": "Gone"}, "isDisabled": true}}
                ]}
                """
            )
        )

        assertEquals(listOf("Me", "My Brand"), accounts.map { it.name })
        assertEquals("me@example.com", accounts[0].email)
        assertNull(accounts[0].pageId)
        assertEquals("112233", accounts[1].pageId)
    }

    @Test
    fun `the tv home keeps its page token and each row's own token`() {
        val page = YouTubeResponseParser.parsePage(
            Json.parseToJsonElement(
                """
                {"sectionListRenderer": {
                  "contents": [{"shelfRenderer": {
                    "headerRenderer": {"shelfHeaderRenderer": {"title": {"simpleText": "Row"}}},
                    "content": {"horizontalListRenderer": {
                      "items": [${tile("aaa", "First")}],
                      "continuations": [{"nextContinuationData": {"continuation": "ROW_TOKEN"}}]
                    }}
                  }}],
                  "continuations": [{"nextContinuationData": {"continuation": "PAGE_TOKEN"}}]
                }}
                """
            )
        )

        assertEquals("PAGE_TOKEN", page.continuation)
        assertEquals("ROW_TOKEN", page.shelves.single().continuation)
    }

    @Test
    fun `a continuation item renderer gives the page token`() {
        val page = YouTubeResponseParser.parsePage(
            Json.parseToJsonElement(
                """
                {"gridRenderer": {"items": [
                  ${tile("aaa", "First")},
                  {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "NEXT"}}}}
                ]}}
                """
            )
        )

        assertEquals("NEXT", page.continuation)
        assertEquals(listOf("aaa"), page.shelves.single().videos.map { it.videoId })
    }
}
