package com.fetch.tv.data.mapper

import com.fetch.tv.data.remote.dto.MetaResponseDto
import com.fetch.tv.data.remote.dto.StreamResponseDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One malformed nested entry (a subtitle without `lang`, a link without `category`,
 * a cast member without `name`) must not make Moshi reject an addon's whole response.
 */
class LenientAddonDtoTest {

    private val moshi = Moshi.Builder().build()

    @Test
    fun `subtitle without lang or url keeps the stream and drops only the bad entry`() {
        val response = moshi.adapter(StreamResponseDto::class.java).fromJson(
            """
            {"streams":[{"url":"https://example.com/v.mkv","subtitles":[
              {"id":"1","url":"https://example.com/a.srt"},
              {"id":"2","lang":"eng"},
              {"id":"3","url":"https://example.com/b.srt","lang":null}
            ]}]}
            """.trimIndent()
        )!!

        val stream = response.streams!!.single().toDomain("Addon", null)
        assertEquals(listOf("https://example.com/a.srt", "https://example.com/b.srt"), stream.subtitles.map { it.url })
        assertTrue(stream.subtitles.all { it.lang == "Unknown" })
    }

    @Test
    fun `link without category and cast member without name do not fail the meta`() {
        val meta = moshi.adapter(MetaResponseDto::class.java).fromJson(
            """
            {"meta":{"id":"tt1","type":"movie","name":"Movie",
              "links":[{"name":"Drama","url":"stremio:///search?search=Drama"},{"category":"Genres","url":"x"}],
              "app_extras":{"cast":[{"character":"Nobody"},{"name":"Someone"}]}}}
            """.trimIndent()
        )!!.meta!!.toDomain()

        assertEquals(listOf("Drama"), meta.links.map { it.name })
        assertEquals("", meta.links.single().category)
        assertEquals(listOf("Someone"), meta.castMembers.map { it.name })
    }
}
