# 014 — Detail page: token backdrop fades, instant scroll restore, quicker nudges

- **Status**: DONE (build not verified locally: no Android SDK in the session)
- **Commit**: ee8dd84
- **Severity**: MEDIUM
- **Category**: 2. Easing & duration + 7. Cohesion & tokens
- **Estimated scope**: 1 file (`MetaDetailsScreen.kt`), ~20 lines
- **Depends on**: 001 (uses `FetchMotion.enterTween()`, `exitTween()`, `moveTween()`, `tokens.durations.enter`)

## Problem

All in `app/src/main/java/com/fetch/tv/ui/screens/detail/MetaDetailsScreen.kt`.

**Backdrop fades (`BackdropLayer`, lines 3355-3378).** Three alphas share a hard-coded spec:

```kotlin
// MetaDetailsScreen.kt:3355-3378 — current (three times)
animationSpec = tween(durationMillis = if (isScrolledPastHero) 300 else 800),
```

for `backdropAlphaState` (backdrop image: 0 during a trailer, 0.15 scrolled past the hero,
else 1), `backgroundTrailerAlphaState` (muted background trailer) and `gradientAlphaState`
(left gradient). Scrolling back up to the hero, done tens of times a day, takes 800ms to
restore the backdrop, with default FastOutSlowIn easing. The reads are already deferred
(`graphicsLayer { alpha = backdropAlphaState.value }` at :3389 and :3399, the trailer layer at
:3419-3421, `drawWithCache` at :3426-3437); keep that. These `tween(durationMillis = if ...)`
calls don't match the literal guard regex (no digit right after `=`), so there is no baseline
line for this file.

**Backdrop crossfade (line 2253).**

```kotlin
// MetaDetailsScreen.kt:2253 — current
.apply { if (shouldShowSeedBackdropUnderlay) crossfade(400) else if (hasHeroBackdrop) crossfade(false) else crossfade(400) }
```

`400` is a hard-coded duration that the guard can't see (it only matches `tween(`).
Coil 3's `ImageRequest.Builder.crossfade(durationMillis: Int)` takes an `Int`, so a token fits.

**Scroll animations (lines 1503-1540).**

```kotlin
// MetaDetailsScreen.kt:1503-1515 — current
suspend fun animateDetailScrollTo(index: Int, offset: Int) {
    if (index < 0) return
    val currentIndex = listState.firstVisibleItemIndex
    val currentOffset = listState.firstVisibleItemScrollOffset
    if (currentIndex == index) {
        val delta = (offset - currentOffset).toFloat()
        if (kotlin.math.abs(delta) > 1f) {
            listState.animateScrollBy(delta, FetchMotion.slowTween())
        }
        return
    }
    listState.animateScrollToItem(index, offset)
}
// MetaDetailsScreen.kt:1537-1539 — current
coroutineScope.launch {
    listState.animateScrollBy(distance, FetchMotion.slowTween())
}
```

`slowTween()` is 450ms with LinearOutSlowIn. Callers:
- `:1560`, `restorePinnedDetailPageIfNudge()`: puts the page back by 1–200px when focus
  leaves a company row it had nudged. Called from D-pad focus changes on four rows
  (`:2808`, `:2843`, `:2898`, `:2963`). This is on-screen movement → `moveTween()`.
- `:1669`, `launchPendingFocusRestore()`: restores the page to where the user was after a
  child page (cast, episode, collection, company) closes. The user expects to land where they
  left, not to watch the page scroll for 450ms → instant `scrollToItem`.
- `:1538`, `onCompanyRowFocused()`: the nudge that reveals a company row's overflow on focus
  → `moveTween()`.

## Target

- One shared spec for the three backdrop alphas:
  - scrolled past the hero (dimming) → `FetchMotion.exitTween()` (150ms, strong ease-out);
  - a foreground or background trailer is involved → `FetchMotion.mediumTween()` (350ms,
    `durations.medium`). Justification: the trailer player only fades its video in after its
    first frame (`TrailerPlayer.kt:80-84`), so the backdrop fading out over a slightly longer
    350ms covers more of the decoder start-up instead of dipping to the page background.
  - otherwise (back at the hero) → `FetchMotion.enterTween()` (200ms, strong ease-out).
- Coil crossfade `400` → `FetchMotion.tokens.durations.enter` (200).
- Restore after a child page: `listState.scrollToItem(index, offset)` (instant).
- Nudges: `FetchMotion.moveTween()` (250ms, inOut).

## Repo conventions to follow

- Keep the deferred alpha reads exactly as they are (`graphicsLayer`/`drawWithCache`).
- Durations and easings come from `FetchMotion` (`app/src/main/java/com/fetch/tv/ui/theme/MotionFocusTokens.kt`);
  no new literals in this file.

## Steps

1. **Import.** Add `import androidx.compose.animation.core.FiniteAnimationSpec` to `MetaDetailsScreen.kt`.

2. **Backdrop spec.** In `BackdropLayer`, directly after
   `var isBackgroundTrailerRendered by remember(isBackgroundTrailerPlaying) { mutableStateOf(false) }`
   (line 3354), add:
   ```kotlin
   // Dimming as the page scrolls past the hero is an exit; coming back is an entrance. A
   // trailer starting or stopping takes a little longer, to cover the player's first frames.
   val backdropFadeSpec: FiniteAnimationSpec<Float> = when {
       isScrolledPastHero -> FetchMotion.exitTween()
       isTrailerPlaying || isBackgroundTrailerVisible -> FetchMotion.mediumTween()
       else -> FetchMotion.enterTween()
   }
   ```
   Then replace each of the three lines
   `animationSpec = tween(durationMillis = if (isScrolledPastHero) 300 else 800),`
   (lines 3362, 3371, 3376) with `animationSpec = backdropFadeSpec,`.

3. **Crossfade.** Replace line 2253 with:
   ```kotlin
   .apply { if (shouldShowSeedBackdropUnderlay) crossfade(FetchMotion.tokens.durations.enter) else if (hasHeroBackdrop) crossfade(false) else crossfade(FetchMotion.tokens.durations.enter) }
   ```

4. **Nudge inside `animateDetailScrollTo`.** Line 1510: `FetchMotion.slowTween()` → `FetchMotion.moveTween()`.
   Its only remaining caller after step 6 is the nudge restore at `:1560`.

5. **Company-row nudge.** Line 1538: `FetchMotion.slowTween()` → `FetchMotion.moveTween()`.

6. **Restore after a child page.** In `launchPendingFocusRestore()` (lines 1667-1670), change
   ```kotlin
   if (index >= 0) {
       animateDetailScrollTo(index, offset)
   }
   ```
   to
   ```kotlin
   if (index >= 0) {
       // Land where the user left off; watching the page scroll back reads as lag.
       listState.scrollToItem(index, offset)
   }
   ```

7. Keep the `tween` import: it is still used at line ~2786.

## Boundaries

- Edit only `MetaDetailsScreen.kt`, only the lines above.
- Do NOT touch `belowHeroAlpha` (line 1841-1845) or `HeroSection.kt`: plan 007 owns them.
- Do NOT touch `fadeBelowHero`, `TrailerPlayer.kt`, or `animateScrollToItem` calls at :2160,
  :2169, :2569, :2711 (user-initiated jumps, out of scope).
- If the code doesn't match the excerpts (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes (no
    baseline change: this file had and has no matching literal).
  - `./gradlew :app:testFullDebugUnitTest --tests '*MetaDetailsSessionStateTest*'` passes.
  - `grep -n "300 else 800\|crossfade(400)\|slowTween" app/src/main/java/com/fetch/tv/ui/screens/detail/MetaDetailsScreen.kt` returns nothing.
- **Feel check** (TV or emulator, detail page of a title with a backdrop):
  - Scroll down past the hero: the backdrop dims quickly (150ms). Scroll back up: it returns
    in 200ms, ease-out, with no lingering grey.
  - Let the trailer start: the backdrop fades over ~350ms, then the video fades in. Compare
    against 200ms (temporarily swap `mediumTween()` for `enterTween()`): keep 350ms if 200ms
    shows a longer dark gap before the video.
  - Open a cast member from a lower row, then Back: the page is already at your row when it
    appears; focus lands on the same card.
  - Focus a company row near the bottom, then move up: the 1–200px nudge back is quick and
    eases in and out.
  - Animator duration scale 10x for the above, to check easing direction (ease-out on fades).
  - Animator duration scale "Animation off": fades and nudges snap; restore is instant either way.
- **Done when**: the build and tests are green, no hard-coded 300/800/400 remain, and
  returning from a child page lands instantly.
