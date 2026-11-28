package com.fetch.tv.ui.screens.home

import com.fetch.tv.ui.components.hero.HeroPreview

import com.fetch.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HeroPresenterTest {

    private fun preview(title: String) = HeroPreview(
        title = title,
        logo = null,
        description = null,
        contentTypeText = null,
        yearText = null,
        imdbText = null,
        genres = StableList(emptyList()),
        poster = null,
        backdrop = "https://art.test/$title.jpg",
        imageUrl = null
    )

    /** The live scene for a card, as ModernHomeContent builds it (no preview while enriching). */
    private fun live(
        id: String,
        enriching: Boolean = false,
        backdrop: String? = "https://art.test/$id.jpg",
        trailerUrl: String? = null
    ) = ModernHeroSceneState(
        heroBackdrop = backdrop,
        preview = if (enriching) null else preview(id),
        enrichmentActive = enriching,
        shouldPlayTrailer = trailerUrl != null,
        trailerFirstFrameRendered = false,
        trailerUrl = trailerUrl,
        trailerAudioUrl = null,
        trailerPlaybackKey = trailerUrl,
        trailerMuted = true,
        fullScreenBackdrop = false
    )

    private fun art(id: String) = "https://art.test/$id.jpg"

    /** Feeds [inputs] in order, storing each state as HeroPresenter.run does. */
    private fun play(start: HeroPresenterState, vararg inputs: HeroSceneInput): List<HeroSceneResolution> {
        var state = start
        return inputs.map { input ->
            resolveHeroScene(state, input).also { state = it.state }
        }
    }

    @Test
    fun `settled focus shows the live scene and moves the backdrop to it`() {
        val result = resolveHeroScene(
            HeroPresenterState(backdropTarget = art("a")),
            HeroSceneInput(live = live("b"), title = preview("b"))
        )

        assertEquals(live("b"), result.scene)
        assertEquals(art("b"), result.state.backdropTarget)
        assertEquals(live("b"), result.state.held)
        assertEquals(preview("b"), result.title)
    }

    @Test
    fun `the backdrop holds while enrichment is pending, then moves when it lands (#804)`() {
        val (pending, landed) = play(
            HeroPresenterState(held = live("a"), backdropTarget = art("a")),
            HeroSceneInput(live = live("b", enriching = true), title = preview("b"), displayedBackdrop = art("a")),
            HeroSceneInput(live = live("b"), title = preview("b"), displayedBackdrop = art("a"))
        )

        assertEquals(art("a"), pending.scene.heroBackdrop)
        // The title block follows focus at once, from the catalog's data.
        assertEquals(preview("b"), pending.title)
        assertEquals(art("b"), landed.scene.heroBackdrop)
    }

    @Test
    fun `pending enrichment keeps the backdrop on screen in the held scene`() {
        val result = resolveHeroScene(
            HeroPresenterState(held = live("a"), backdropTarget = art("a")),
            HeroSceneInput(live = live("b", enriching = true), displayedBackdrop = art("a"))
        )

        assertEquals(live("b", enriching = true).copy(heroBackdrop = art("a")), result.state.held)
    }

    @Test
    fun `scrolling keeps a held scene with a title, whatever the rows pass`() {
        val held = live("a", trailerUrl = "https://video.test/a.mp4")
        val results = play(
            HeroPresenterState(held = held, backdropTarget = art("a")),
            HeroSceneInput(live = live("b"), scrolling = true, displayedBackdrop = art("a")),
            HeroSceneInput(live = live("c", enriching = true), scrolling = true, displayedBackdrop = art("a"))
        )

        results.forEach { result ->
            assertSame(held, result.state.held)
            assertEquals(held, result.scene)
        }
    }

    @Test
    fun `scrolling with nothing held holds the first live scene with a title, on the backdrop shown`() {
        val (first, second) = play(
            HeroPresenterState(held = live("a", enriching = true), backdropTarget = art("a")),
            HeroSceneInput(live = live("b"), scrolling = true, displayedBackdrop = art("a")),
            HeroSceneInput(live = live("c"), scrolling = true, displayedBackdrop = art("a"))
        )

        val expected = live("b").copy(heroBackdrop = art("a"))
        assertEquals(expected, first.state.held)
        assertEquals(expected, first.scene)
        assertEquals(expected, second.scene)
    }

    @Test
    fun `scrolling with no title anywhere shows the live scene`() {
        val result = resolveHeroScene(
            HeroPresenterState(backdropTarget = art("a")),
            HeroSceneInput(live = live("b", enriching = true), scrolling = true, displayedBackdrop = art("a"))
        )

        assertEquals(live("b", enriching = true).copy(heroBackdrop = art("a")), result.scene)
        assertEquals(art("a"), result.state.backdropTarget)
    }

    @Test
    fun `a held D-pad drops the title and keeps the backdrop on screen`() {
        val results = play(
            HeroPresenterState(held = live("a"), backdropTarget = art("a")),
            HeroSceneInput(live = live("b"), rapidNavigation = true, displayedBackdrop = art("a")),
            HeroSceneInput(live = live("c", enriching = true), rapidNavigation = true, displayedBackdrop = art("a"))
        )

        results.forEach { result ->
            assertNull(result.scene.preview)
            assertFalse(result.scene.enrichmentActive)
            assertEquals(art("a"), result.scene.heroBackdrop)
            assertEquals(art("a"), result.state.backdropTarget)
        }
    }

    @Test
    fun `a held D-pad keeps the live trailer fields`() {
        val result = resolveHeroScene(
            HeroPresenterState(held = live("a"), backdropTarget = art("a")),
            HeroSceneInput(
                live = live("b", trailerUrl = "https://video.test/b.mp4"),
                rapidNavigation = true,
                displayedBackdrop = art("a")
            )
        )

        assertEquals("https://video.test/b.mp4", result.scene.trailerUrl)
    }

    @Test
    fun `a held D-pad before anything is on screen follows the live backdrop`() {
        val result = resolveHeroScene(
            HeroPresenterState(),
            HeroSceneInput(live = live("b"), rapidNavigation = true, displayedBackdrop = null)
        )

        assertEquals(art("b"), result.scene.heroBackdrop)
    }

    @Test
    fun `letting go of the D-pad moves to the focused card`() {
        val (_, settled) = play(
            HeroPresenterState(held = live("a"), backdropTarget = art("a")),
            HeroSceneInput(live = live("c"), rapidNavigation = true, displayedBackdrop = art("a")),
            HeroSceneInput(live = live("c"), displayedBackdrop = art("a"))
        )

        assertEquals(live("c"), settled.scene)
        assertEquals(art("c"), settled.state.backdropTarget)
    }

    @Test
    fun `a blank title from a row transition is not held`() {
        val blank = live("b").copy(preview = preview(""), heroBackdrop = art("a"))
        val result = resolveHeroScene(
            HeroPresenterState(held = live("a"), backdropTarget = art("a")),
            HeroSceneInput(live = blank, scrolling = false)
        )

        assertEquals(live("a"), result.state.held)
    }

    @Test
    fun `a blank title is held when the art moved with it`() {
        val blank = live("b").copy(preview = preview(""))
        val result = resolveHeroScene(
            HeroPresenterState(held = live("a"), backdropTarget = art("a")),
            HeroSceneInput(live = blank)
        )

        assertEquals(blank, result.state.held)
    }

    @Test
    fun `a blank title is not held while scrolling`() {
        val blank = live("b").copy(preview = preview(""))
        val result = resolveHeroScene(
            HeroPresenterState(held = null),
            HeroSceneInput(live = blank, scrolling = true)
        )

        assertNull(result.state.held)
    }

    @Test
    fun `the title block keeps the last title over a gap`() {
        val (_, gap, next) = play(
            HeroPresenterState(),
            HeroSceneInput(live = live("a"), title = preview("a")),
            HeroSceneInput(live = live("gap", enriching = true), title = null),
            HeroSceneInput(live = live("c"), title = preview("c"))
        )

        assertEquals(preview("a"), gap.title)
        assertEquals(preview("c"), next.title)
    }

    @Test
    fun `resolving again with the stored state changes nothing`() {
        val inputs = listOf(
            HeroSceneInput(live = live("b"), title = preview("b"), displayedBackdrop = art("a")),
            HeroSceneInput(live = live("b", enriching = true), displayedBackdrop = art("a")),
            HeroSceneInput(live = live("b"), scrolling = true, displayedBackdrop = art("a")),
            HeroSceneInput(live = live("b", enriching = true), scrolling = true, rapidNavigation = true, displayedBackdrop = art("a")),
            HeroSceneInput(live = live("b"), rapidNavigation = true, displayedBackdrop = art("a")),
            HeroSceneInput(live = live("b").copy(preview = preview("")), displayedBackdrop = art("a"))
        )
        val starts = listOf(
            HeroPresenterState(),
            HeroPresenterState(held = live("a"), backdropTarget = art("a"), lastTitle = preview("a")),
            HeroPresenterState(held = live("a", enriching = true), backdropTarget = art("a"))
        )

        for (start in starts) for (input in inputs) {
            val once = resolveHeroScene(start, input)
            val twice = resolveHeroScene(once.state, input)
            assertEquals(once, twice)
        }
    }

    @Test
    fun `a rebound input drives the scene and title`() {
        val first = androidx.compose.runtime.mutableStateOf(
            HeroLiveInput(live = live("a"), title = preview("a"), scrolling = false, rapidNavigation = false)
        )
        val presenter = HeroPresenter(
            initialInput = first,
            initialDisplayedBackdrop = art("a"),
            initialBackdropTarget = art("a")
        )
        assertEquals(art("a"), presenter.scene.value.heroBackdrop)
        assertEquals("a", presenter.title.value?.title)

        val second = androidx.compose.runtime.mutableStateOf(
            HeroLiveInput(
                live = live("b", trailerUrl = "https://video.test/b.m3u8"),
                title = preview("b"),
                scrolling = false,
                rapidNavigation = false
            )
        )
        presenter.bind(second)

        assertEquals("https://video.test/b.m3u8", presenter.scene.value.trailerUrl)
        assertEquals(art("b"), presenter.scene.value.heroBackdrop)
        assertEquals("b", presenter.title.value?.title)
    }

    @Test
    fun `a scroll starting before the settled art loads keeps the art on screen`() {
        val results = play(
            HeroPresenterState(backdropTarget = art("a")),
            HeroSceneInput(live = live("b"), title = preview("b"), displayedBackdrop = art("a")),
            HeroSceneInput(live = live("c"), title = preview("c"), scrolling = true, displayedBackdrop = art("a")),
            HeroSceneInput(live = live("c"), title = preview("c"), scrolling = false, displayedBackdrop = art("a"))
        )

        assertEquals(art("b"), results[0].scene.heroBackdrop)
        assertEquals("b", results[1].scene.preview?.title)
        assertEquals(art("a"), results[1].scene.heroBackdrop)
        assertEquals(art("c"), results[2].scene.heroBackdrop)
    }
}
