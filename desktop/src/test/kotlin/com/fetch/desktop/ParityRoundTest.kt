package com.fetch.desktop

import com.fetch.desktop.data.AutoPlayMode
import com.fetch.desktop.data.DesktopDefaults
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SegmentType
import com.fetch.tv.core.streams.ranking.StreamRanker
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ParityRoundTest {

    private fun stream(name: String, url: String? = "https://cdn.example/$name.mkv", addon: String = "Addon") = Stream(
        name = name, title = null, description = null, url = url,
        ytId = null, infoHash = if (url == null) "abc" else null, fileIdx = null, externalUrl = null,
        behaviorHints = null, addonName = addon, addonLogo = null
    )

    private val ranked = StreamRanker.rank(
        listOf(stream("720p WEB"), stream("2160p REMUX HDR"), stream("1080p BluRay")),
        rules = DesktopDefaults.rankingRules,
        isPlayable = { it.url != null }
    )

    @Test
    fun `best match plays the ranker's pick`() {
        assertEquals("2160p REMUX HDR", pickSource(ranked, AutoPlayMode.BEST_MATCH, "")?.stream?.name)
    }

    @Test
    fun `first source plays the addon's first playable result`() {
        assertEquals("720p WEB", pickSource(ranked, AutoPlayMode.FIRST_STREAM, "")?.stream?.name)
    }

    @Test
    fun `a pattern plays the first match in addon order, any case`() {
        assertEquals("1080p BluRay", pickSource(ranked, AutoPlayMode.REGEX_MATCH, "bluray")?.stream?.name)
        assertNull(pickSource(ranked, AutoPlayMode.REGEX_MATCH, "nothing-like-this"))
        assertNull("an invalid pattern plays nothing", pickSource(ranked, AutoPlayMode.REGEX_MATCH, "(["))
        assertNull("an empty pattern plays nothing", pickSource(ranked, AutoPlayMode.REGEX_MATCH, " "))
    }

    @Test
    fun `manual plays nothing`() {
        assertNull(pickSource(ranked, AutoPlayMode.MANUAL, ""))
    }

    private fun preview(releaseInfo: String?, released: String? = null) = MetaPreview(
        id = "tt1", type = ContentType.MOVIE, name = "M", poster = null, posterShape = PosterShape.POSTER, background = null,
        logo = null, description = null, releaseInfo = releaseInfo, imdbRating = null, genres = emptyList(), released = released
    )

    @Test
    fun `unreleased by date first, else by year`() {
        val today = LocalDate.of(2026, 11, 3)
        assertTrue(isUnreleased(preview("2026", released = "2026-12-01T00:00:00.000Z"), today))
        assertFalse(isUnreleased(preview("2027", released = "2026-10-01"), today))
        assertTrue(isUnreleased(preview("2027"), today))
        assertFalse(isUnreleased(preview("2008–2013"), today))
        assertFalse(isUnreleased(preview(null), today))
    }

    @Test
    fun `an episode without a date counts as aired`() {
        val today = LocalDate.of(2026, 11, 3)
        fun ep(date: String?) = Video(id = "x", title = "", released = date, thumbnail = null, season = 1, episode = 1, overview = null)
        assertTrue(isAired(ep(null), today))
        assertTrue(isAired(ep("2026-11-03"), today))
        assertFalse(isAired(ep("2026-11-04T00:00:00Z"), today))
    }

    @Test
    fun `version order follows semantic versioning`() {
        fun v(s: String) = Version.parse(s)!!
        assertTrue(v("1.3.1") > v("1.3.0"))
        assertTrue(v("1.3.0") > v("1.3.0-beta.2"))
        assertTrue(v("1.3.0-beta.10") > v("1.3.0-beta.2"))
        assertTrue(v("v2.0.0") > v("1.99.99"))
        assertNull(Version.parse("dev"))
    }

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false) = UpdateChecker.Release(
        tag_name = tag,
        html_url = "https://github.com/x/releases/$tag",
        prerelease = prerelease,
        draft = draft,
        assets = listOf(
            UpdateChecker.Asset("fetch-$tag-linux-amd64.deb", "https://dl/$tag.deb"),
            UpdateChecker.Asset("fetch-$tag-linux-x64.tar.gz", "https://dl/$tag.tar.gz")
        )
    )

    @Test
    fun `the update check skips betas unless asked, and picks this install's package`() {
        val releases = listOf(release("v1.4.0-beta.1", prerelease = true), release("v1.3.1"), release("v1.5.0", draft = true), release("v1.2.0"))
        val stable = UpdateChecker.pick(releases, "1.3.0", beta = false, kind = ".deb")!!
        assertEquals("1.3.1", stable.version)
        assertEquals("https://dl/v1.3.1.deb", stable.packageUrl)
        assertEquals("1.4.0-beta.1", UpdateChecker.pick(releases, "1.3.0", beta = true, kind = ".tar.gz")!!.version)
        assertNull("up to date", UpdateChecker.pick(releases, "1.3.1", beta = false, kind = ".deb"))
        assertNull("a dev build doesn't nag", UpdateChecker.pick(releases, "dev", beta = false, kind = ".deb"))
    }

    @Test
    fun `the Up Next card shows near the end, or from the credits`() {
        val credits = listOf(Segment(SegmentType.OUTRO, 2_500.0, 2_640.0))
        assertNull(upNextCountdown(credits, position = 1_000.0, duration = 2_700.0, promptSeconds = 30))
        assertEquals(200, upNextCountdown(credits, position = 2_500.0, duration = 2_700.0, promptSeconds = 30))
        assertEquals(20, upNextCountdown(emptyList(), 2_680.0, 2_700.0, 30))
        assertNull("unknown length", upNextCountdown(emptyList(), 10.0, 0.0, 30))
    }
}
