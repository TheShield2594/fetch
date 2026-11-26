package com.fetch.tv.ui.screens.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/** What the hero is fed on each focus move: the live scene, the title, and how focus is moving. */
internal data class HeroLiveInput(
    /** The scene for the focused card as it is right now (enrichment, trailer, backdrop). */
    val live: ModernHeroSceneState,
    /** The title block's hero: the focused card's data, or its enrichment once that lands. */
    val title: HeroPreview?,
    /** The rows are scrolling vertically. */
    val scrolling: Boolean,
    /** The D-pad is held left or right within a row. */
    val rapidNavigation: Boolean
)

/** [HeroLiveInput] plus the backdrop the hero is showing, which the hold rules freeze on. */
internal data class HeroSceneInput(
    val live: ModernHeroSceneState,
    val title: HeroPreview? = null,
    val scrolling: Boolean = false,
    val rapidNavigation: Boolean = false,
    /** The backdrop on screen now: loaded and crossfaded in by the media layer (#804). */
    val displayedBackdrop: String? = null
)

/** What the hero remembers between focus moves. */
internal data class HeroPresenterState(
    /** The scene kept while the rows scroll or the D-pad is held. */
    val held: ModernHeroSceneState? = null,
    /** The backdrop the hero is moving to. It only moves while enrichment is not pending. */
    val backdropTarget: String? = null,
    /** The last title shown, kept while focus passes over a card with nothing to show. */
    val lastTitle: HeroPreview? = null
)

internal data class HeroSceneResolution(
    val state: HeroPresenterState,
    /** The scene to draw. Its [ModernHeroSceneState.heroBackdrop] is the backdrop target. */
    val scene: ModernHeroSceneState,
    val title: HeroPreview?
)

private val ModernHeroSceneState?.hasTitle: Boolean
    get() = this?.preview?.title?.isNotBlank() == true

private val ModernHeroSceneState.hasBlankTitle: Boolean
    get() = preview != null && preview.title.isBlank()

/** Keeps the backdrop that is on screen, so a held scene doesn't swap the art under it. */
private fun ModernHeroSceneState.onDisplayedBackdrop(displayed: String?): ModernHeroSceneState =
    if (!displayed.isNullOrBlank() && displayed != heroBackdrop) copy(heroBackdrop = displayed) else this

/** The scene without a title or pending enrichment, for while the D-pad is held. */
private fun ModernHeroSceneState.withoutPreview(): ModernHeroSceneState =
    copy(preview = null, enrichmentActive = false)

/**
 * Every rule for what the hero shows, in one place.
 *
 * - Settled focus: the live scene. It is also kept as the held scene (with the backdrop on screen
 *   while enrichment is pending), unless a transient blank title comes through.
 * - Vertical scroll: a held scene with a title stays as it is, so the hero doesn't flash the
 *   catalog's data for every row passed. Without one, the first live scene with a title is held.
 * - Held D-pad: the live scene without its title and with the backdrop on screen, so the art does
 *   not chase every card passed.
 * - The backdrop target moves to the shown scene's backdrop only once that scene's enrichment is
 *   done; otherwise it holds. The media layer then holds the old art until the new has loaded.
 * - The title block shows the live title, or the last one while focus is on a gap.
 *
 * Pure and idempotent: resolving again with the returned state and the same input gives the same
 * result, so a derived read and the stored state never disagree.
 */
internal fun resolveHeroScene(previous: HeroPresenterState, input: HeroSceneInput): HeroSceneResolution {
    val live = input.live
    val held = previous.held
    val displayed = input.displayedBackdrop

    val nextHeld: ModernHeroSceneState? = when {
        input.scrolling -> when {
            // On the backdrop shown: a settled card whose art was still loading when the scroll
            // began must not crossfade in mid-scroll (#804). Settling again re-targets its art.
            held.hasTitle -> held!!.onDisplayedBackdrop(displayed)
            else -> {
                val candidate = when {
                    live.hasTitle -> live
                    input.rapidNavigation -> live.withoutPreview()
                    else -> live
                }
                if (candidate.hasBlankTitle) held else candidate.onDisplayedBackdrop(displayed)
            }
        }
        input.rapidNavigation -> live.withoutPreview().onDisplayedBackdrop(displayed)
        live.hasBlankTitle ->
            // A row transition's empty title is not held, unless the art itself moved.
            if (!live.enrichmentActive && live.heroBackdrop != null && held?.heroBackdrop != live.heroBackdrop) live
            else held
        !live.enrichmentActive && live.heroBackdrop != null -> live
        else -> live.onDisplayedBackdrop(displayed)
    }

    val shown = when {
        input.scrolling && nextHeld.hasTitle -> nextHeld!!
        input.rapidNavigation && nextHeld != null -> nextHeld
        else -> live
    }

    val backdropTarget = if (!shown.enrichmentActive && shown.heroBackdrop != null) {
        shown.heroBackdrop
    } else {
        previous.backdropTarget
    }
    val title = input.title ?: previous.lastTitle

    return HeroSceneResolution(
        state = HeroPresenterState(held = nextHeld, backdropTarget = backdropTarget, lastTitle = title),
        scene = if (shown.heroBackdrop == backdropTarget) shown else shown.copy(heroBackdrop = backdropTarget),
        title = title
    )
}

/**
 * Owns what the Home hero shows. [scene] and [title] are derived from the live input through
 * [resolveHeroScene]; [run] is the only writer of the remembered state.
 */
@Stable
internal class HeroPresenter(
    initialInput: State<HeroLiveInput>,
    initialDisplayedBackdrop: String?,
    initialBackdropTarget: String?
) {
    // Not named like the constructor parameter: initializers below would read the parameter,
    // and miss the state bind() swaps in.
    private var input by mutableStateOf(initialInput)
    private var state by mutableStateOf(HeroPresenterState(backdropTarget = initialBackdropTarget))
    private var displayedBackdrop by mutableStateOf(initialDisplayedBackdrop)

    private val resolution = derivedStateOf {
        val current = input.value
        resolveHeroScene(
            state,
            HeroSceneInput(
                live = current.live,
                title = current.title,
                scrolling = current.scrolling,
                rapidNavigation = current.rapidNavigation,
                displayedBackdrop = displayedBackdrop
            )
        )
    }

    val scene: State<ModernHeroSceneState> = derivedStateOf { resolution.value.scene }
    val title: State<HeroPreview?> = derivedStateOf { resolution.value.title }

    /** Points the presenter at a new input state when the caller's remember keys change. */
    fun bind(input: State<HeroLiveInput>) {
        if (this.input !== input) this.input = input
    }

    /** The media layer has crossfaded to [url]. */
    fun onBackdropDisplayed(url: String?) {
        if (!url.isNullOrBlank()) displayedBackdrop = url
    }

    /** Stores each resolution's state, so the holds survive the next focus move. */
    suspend fun run() {
        snapshotFlow { resolution.value.state }.collect { state = it }
    }
}
