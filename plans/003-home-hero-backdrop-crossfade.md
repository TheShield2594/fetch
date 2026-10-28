# 003 — Shorten the Home hero backdrop crossfade to 200ms ease-out, and snap it under reduced motion

- **Status**: DONE (build not verified locally: no Android SDK in the session)
- **Commit**: ee8dd84
- **Severity**: HIGH
- **Category**: 1. Purpose & frequency / 5. Performance
- **Estimated scope**: 1 file (`ModernHomeHero.kt`), ~8 lines
- **Depends on**: 001 (`FetchMotion.enterTween()`)

## Problem

On Home, every time focus rests on a card for 200ms the hero backdrop behind the rows changes.
The new image crossfades in over 400ms with the default (FastOutSlowIn) easing.

`app/src/main/java/com/fetch/tv/ui/screens/home/ModernHomeHero.kt:171-191` (current):

```kotlin
    Box(modifier = modifier) {
        androidx.compose.animation.Crossfade(
            targetState = imageModel,
            animationSpec = tween(durationMillis = FetchMotion.tokens.durations.overlay),
            label = "heroBackdropCrossfade"
        ) { model ->
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    // A single image draw, so modulating alpha looks the same as an offscreen
                    // layer without allocating a full-screen buffer for each crossfading backdrop.
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = 1f - transitionProgressState.value
                    },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }
```

- `durations.overlay` is 400ms (`ui/theme/MotionFocusTokens.kt:73`).
- The settle debounce is `MODERN_HERO_FOCUS_DEBOUNCE_MS = 200L` (`ModernHomeModels.kt:37`), so
  stepping card to card at a normal pace starts a new crossfade almost every step. That is
  hundreds a day.
- For 400ms per step, two screen-sized bitmaps are drawn and blended. On low-end TV GPUs this
  competes with the row scroll that runs at the same moment.
- Nothing is gated on `FetchTheme.fullMotion`, so low-RAM devices pay the full cost.
- The slow fade also makes the hero feel like it lags behind focus.

Already in place, and must stay as they are:
- Neighbour backdrops are preloaded at the hero's request size
  (`ModernHomeContent.kt:1094-1117`), so a step left or right usually reads from the memory cache.
- `CompositingStrategy.ModulateAlpha` (`ModernHomeHero.kt:184-187`) avoids an offscreen buffer
  per image.

Old image fading over an empty slot (checked):
- The `AsyncImage` has no `placeholder`. While a new backdrop is not yet in memory, its slot
  draws nothing, so the old image fades out over the background colour and the new one pops in
  when decoded.
- When the URL was preloaded (the common case), Coil 3 resolves a memory-cache hit on
  `Dispatchers.Main.immediate`, before the first draw, and there is no gap.
- A cheap fix for the cache-miss case does not exist inside this file. Showing the old image
  as the new request's placeholder (`placeholderMemoryCacheKey`) would need the exact
  memory-cache key, which includes the request size and is built in `ModernHomeContent.kt`'s
  preload as well. That is out of scope here, so the case is left as a feel-check item below.

## Target

- Full motion: `animationSpec = FetchMotion.enterTween()`, which is 200ms with
  `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)` (strong ease-out).
  - The new backdrop is mostly in within the first ~80ms, so the hero tracks focus.
  - The double-bitmap window is halved.
- Reduced motion (`!FetchTheme.fullMotion`): `animationSpec = snap()`. The backdrop swaps in one
  frame, with no blended frames.
- Everything else in this function is unchanged: the `ModulateAlpha` layer, the
  trailer-crossfade `transitionProgressState`, and the preload.

## Repo conventions to follow

- Durations and easings come from `FetchMotion`. `enterTween()` is added by plan 001 in
  `app/src/main/java/com/fetch/tv/ui/theme/MotionFocusTokens.kt`.
- Reduced motion is `FetchTheme.fullMotion`, read once at the top of the composable and then
  branched on. Exemplar: `app/src/main/java/com/fetch/tv/ui/screens/player/PlaybackDetailsBar.kt:95-104`.
- `FetchTheme` and `FetchMotion` are already imported in `ModernHomeHero.kt` (lines 7 and 9).

## Steps

1. In `ModernHomeHero.kt`, add these imports beside the existing `import androidx.compose.animation.core.tween` (line 12):
   ```kotlin
   import androidx.compose.animation.core.FiniteAnimationSpec
   import androidx.compose.animation.core.snap
   ```
2. In `ModernHeroMediaLayer`, directly above `Box(modifier = modifier) {` (line 171), add:
   ```kotlin
       // Focus settles on a new card hundreds of times a day: keep the backdrop swap short, and
       // skip the blend entirely when motion is reduced (also the low-RAM path).
       val backdropCrossfadeSpec: FiniteAnimationSpec<Float> =
           if (FetchTheme.fullMotion) FetchMotion.enterTween() else snap()
   ```
3. In the `Crossfade` call, replace
   `animationSpec = tween(durationMillis = FetchMotion.tokens.durations.overlay),`
   with
   `animationSpec = backdropCrossfadeSpec,`.
   Change nothing else in the `Crossfade` block.

## Boundaries

- Do NOT touch `transitionProgressState` (`ModernHomeHero.kt:138-142`, the backdrop↔trailer
  crossfade). It runs once per trailer start, not per focus move.
- Do NOT touch the unused imports `coil3.request.transitionFactory` (line 68) or
  `coil3.request.crossfade` (line 64). Plan 020 removes the dead one last.
- Do NOT edit `ModernHomeContent.kt` (the preload) or `ModernHomeModels.kt` (the debounce).
- Do NOT add a placeholder or change the `ImageRequest` built at lines 153-165.
- Do NOT add new dependencies.
- If a step doesn't match the code you find (drift since ee8dd84), STOP and report instead of improvising.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes.
    `ModernHomeHero.kt` is not in `theme-literals-baseline.txt` and no literal `tween(<digits>` is
    added.
  - `grep -n "durations.overlay" app/src/main/java/com/fetch/tv/ui/screens/home/ModernHomeHero.kt`
    returns nothing.
  - No unit test covers `ModernHeroMediaLayer`. `grep -rn "ModernHeroMediaLayer\|heroBackdropCrossfade" app/src/test` is empty.
- **Feel check** (TV or emulator, Modern Home layout):
  - Step Right one card at a time at a normal pace. Each backdrop change reads as "the art
    follows focus", not as a slow dissolve, and is fully settled before the next press.
  - Set Animator duration scale to 5x and step once. The new image arrives fast and eases in
    at the end, with no long middle where both images are half visible.
  - Cache miss: scroll down to a row not seen yet and step to a card whose backdrop has never
    loaded. Watch whether the old image fades to the plain background before the new one pops in.
    - If that gap is visible and bothers you, note it in the PR. Do not fix it in this plan; it
      needs a shared memory-cache key with the preload in `ModernHomeContent.kt`.
  - Performance:
    - Run `adb shell dumpsys gfxinfo com.fetch.tv.debug reset`, step Right 20 times at about 2
      per second, then run `adb shell dumpsys gfxinfo com.fetch.tv.debug`.
    - The janky-frame percentage is lower than or equal to before the change.
  - Reduced motion: set Animator duration scale to "Animation off". The backdrop swaps in one
    frame with no blend.
- **Done when**:
  - The `Crossfade` uses `backdropCrossfadeSpec`, which is `FetchMotion.enterTween()` under full
    motion and `snap()` otherwise.
  - The build and guard test are green.
