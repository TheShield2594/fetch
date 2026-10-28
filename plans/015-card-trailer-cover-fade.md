# 015 — Stop the poster card dipping to black when its trailer starts

- **Status**: TODO
- **Commit**: ee8dd84
- **Severity**: MEDIUM
- **Category**: Cohesion & tokens + Performance (deferred state reads)
- **Estimated scope**: 2 files (ContentCard.kt ~15 lines, TrailerPlayer.kt ~8 lines)
- **Depends on**: 001 (`FetchMotion.exitTween()`, `FetchMotion.enterTween()`)

## Problem

On the classic poster card, when "expand focused poster to backdrop" and "play trailer in
card" are on, a focused card waits, widens to a backdrop, and plays a muted trailer. When
the first video frame arrives, the card briefly **dips to black**. It also recomposes on
every frame of that fade.

Inside the card's clipped box, the z-order (bottom to top) is: black plate → `TrailerPlayer`
→ backdrop cover image.

```kotlin
// app/src/main/java/com/fetch/tv/ui/components/ContentCard.kt:443-489 — current
                // Only allocate animation state when trailer is actually playing.
                val trailerCoverAlpha = if (shouldPlayTrailerPreview) {
                    val alpha by animateFloatAsState(
                        targetValue = if (!trailerFirstFrameRendered) 1f else 0f,
                        animationSpec = tween(durationMillis = FetchMotion.tokens.durations.fast),
                        label = "trailerCoverAlpha"
                    )
                    alpha
                } else {
                    0f
                }

                if (shouldPlayTrailerPreview) {
                    // Black plate under FIT-mode video so non-16:9 trailers letterbox
                    // to black instead of revealing the expanded backdrop (#2852).
                    // The backdrop cover above still hides load-in; it fades out after
                    // the first frame, leaving black + trailer only.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    )
                    TrailerPlayer(
                        trailerUrl = trailerPreviewUrl,
                        trailerAudioUrl = trailerPreviewAudioUrl,
                        isPlaying = true,
                        onEnded = {
                            trailerFirstFrameRendered = false
                            isBackdropExpanded = false
                        },
                        onFirstFrameRendered = {
                            trailerFirstFrameRendered = true
                        },
                        modifier = Modifier.fillMaxSize(),
                        muted = focusedPosterBackdropTrailerMuted
                    )
                }

                if (shouldPlayTrailerPreview && !imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = trailerCoverAlpha },
                        contentScale = ContentScale.Crop
                    )
                }
```

The player fades itself in on the same first-frame signal, over a longer duration:

```kotlin
// app/src/main/java/com/fetch/tv/ui/components/TrailerPlayer.kt:80-85 — current
    var hasRenderedFirstFrame by remember(trailerUrl) { mutableStateOf(false) }
    val playerAlphaState = animateFloatAsState(
        targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
        animationSpec = tween(durationMillis = FetchMotion.tokens.durations.medium),
        label = "trailerFirstFrameAlpha"
    )
```

Both start on the same frame. `onRenderedFirstFrame` sets `hasRenderedFirstFrame` and then
calls `onFirstFrameRendered` (TrailerPlayer.kt:219-222). The cover reaches 0 at 180ms
(`fast`). At that point the player is only at about 50% of its 350ms (`medium`) fade, so
the black plate shows through. For about 170ms the card goes visibly darker than both the
backdrop before and the video after.

There are two more problems:

1. **Composition read.** `val alpha by animateFloatAsState(...)` is read when the `if`
   expression returns `alpha`. So `trailerCoverAlpha` is a plain `Float` captured in
   composition, and all of `ContentCard` recomposes on each frame of the fade. The correct
   pattern keeps the delegate and reads it inside the lambda, as at
   `ui/components/CollectionRowSection.kt:331-341`:

   ```kotlin
   val gifAlpha by animateFloatAsState(targetValue = if (gifLoaded) 1f else 0f, ...)
   AsyncImage(..., modifier = Modifier.fillMaxSize().clip(shape).graphicsLayer { alpha = gifAlpha }, ...)
   ```

2. **Untokened enter/exit defaults on `TrailerPlayer`** (TrailerPlayer.kt:65-66):

   ```kotlin
       enter: EnterTransition = fadeIn(animationSpec = tween(FetchMotion.tokens.durations.long)),
       exit: ExitTransition = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.slow)),
   ```

   These are 600ms in and 450ms out, both with the default `FastOutSlowInEasing`, applied
   when `isPlaying` toggles while the player is composed. **No caller passes `enter` or
   `exit`**: `grep -rn "TrailerPlayer(" app/src/main` gives 7 callers, and none sets
   either. (There is no `HeroSection` caller.)

## Target

**Cover and player.** The cover (on top) owns the reveal. In this card, the player does
not fade itself. It snaps to alpha 1 under the still-opaque cover on the first frame. Then
the cover fades out with `FetchMotion.exitTween()` (150ms, ease-out
`CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`). At every moment the visible pixels are cover
over fully opaque video, so the black plate never shows during the reveal. The plate still
does its real job: letterboxing non-16:9 video once the cover is gone.

- `TrailerPlayer` gains `firstFrameFadeIn: Boolean = true`. When it is `false`,
  `playerAlphaState` uses `snap()` instead of `tween(medium)`. The default stays `true`, so
  every other caller is unchanged. ModernHomeRows.kt:1462 and ModernHomeHero.kt:198 draw
  the player *on top of* their image with no cover, so the player's own 350ms fade is their
  crossfade and must stay.
- ContentCard passes `firstFrameFadeIn = false`.
- The cover alpha is held as a `State<Float>?` and read only inside `graphicsLayer {}`.

**Enter/exit defaults:**

```kotlin
    enter: EnterTransition = fadeIn(animationSpec = FetchMotion.enterTween()),
    exit: ExitTransition = fadeOut(animationSpec = FetchMotion.exitTween()),
```

Visual impact per caller. `AnimatedVisibility(visible = isPlaying)` only animates when
`isPlaying` changes while the player is composed. With `isPlaying` constant at first
composition, it starts visible and plays no enter.

| Caller | `isPlaying` | Impact of new defaults |
|---|---|---|
| ContentCard.kt:465 | `true`, composed only while playing | None. The enter never plays, and the player is removed rather than exited. |
| SharedTrailerOverlay.kt:190 | `true` | None (same reason). |
| ModernHomeRows.kt:1462 | `true`, inside `key(...)` | None. |
| ModernHomeHero.kt:198 | `true`, inside `key(...)` | None. |
| YouTubePlayerOverlay.kt:234 | `true`, composed under a condition | None. |
| MetaDetailsScreen.kt:3404 | `isTrailerPlaying \|\| isBackgroundTrailerPlaying`, toggles | Enter: practically none, because `playerAlphaState` holds the view at alpha 0 until the first frame (TrailerPlayer.kt:123 resets it), which usually arrives after 200ms anyway. Exit: when the detail trailer stops, the video is gone in 150ms instead of fading over up to 450ms. Also check the caller's own `graphicsLayer` alpha at :3418-3421, which multiplies on top for the background-trailer case. |
| PostPlayRecommendationOverlay.kt:257 | `state.isTrailerPlaying`, toggles | Enter: as above. Exit: when the post-play trailer stops, it disappears in 150ms instead of up to 450ms, revealing the recommendation backdrop under it. |

## Repo conventions to follow

- Read animated values in `graphicsLayer {}` lambdas, never in composition. Exemplar:
  `ui/components/CollectionRowSection.kt:331-341`.
- Tokens only. New helpers `FetchMotion.enterTween()`, `exitTween()` and `moveTween()`
  come from plan 001, in `ui/theme/MotionFocusTokens.kt`.
- `snap` is already imported in ContentCard.kt (:38). TrailerPlayer.kt needs the import.

## Steps

All paths are under `app/src/main/java/com/fetch/tv/`.

1. **`ui/components/TrailerPlayer.kt`: new parameter.** After line 66
   (`exit: ExitTransition = ...`), insert:
   ```kotlin
       /**
        * Fade the video in over [FetchMotion] `medium` once its first frame renders. Callers that
        * keep an opaque cover image above the player pass false, so the video is fully opaque
        * underneath before their cover fades away.
        */
       firstFrameFadeIn: Boolean = true,
   ```

2. **`ui/components/TrailerPlayer.kt:81-85`.** Replace
   ```kotlin
       val playerAlphaState = animateFloatAsState(
           targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
           animationSpec = tween(durationMillis = FetchMotion.tokens.durations.medium),
           label = "trailerFirstFrameAlpha"
       )
   ```
   with
   ```kotlin
       val playerAlphaState = animateFloatAsState(
           targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
           animationSpec = if (firstFrameFadeIn) {
               tween(durationMillis = FetchMotion.tokens.durations.medium)
           } else {
               snap()
           },
           label = "trailerFirstFrameAlpha"
       )
   ```
   Add `import androidx.compose.animation.core.snap` next to the other
   `androidx.compose.animation.core` imports (lines 7-8).

3. **`ui/components/TrailerPlayer.kt:65-66`: enter/exit defaults.** Replace the two lines
   with:
   ```kotlin
       enter: EnterTransition = fadeIn(animationSpec = FetchMotion.enterTween()),
       exit: ExitTransition = fadeOut(animationSpec = FetchMotion.exitTween()),
   ```
   `tween` is still used at :83 and :90, so keep its import.

4. **`ui/components/ContentCard.kt:443-453`: cover alpha as state.** Replace
   ```kotlin
                   // Only allocate animation state when trailer is actually playing.
                   val trailerCoverAlpha = if (shouldPlayTrailerPreview) {
                       val alpha by animateFloatAsState(
                           targetValue = if (!trailerFirstFrameRendered) 1f else 0f,
                           animationSpec = tween(durationMillis = FetchMotion.tokens.durations.fast),
                           label = "trailerCoverAlpha"
                       )
                       alpha
                   } else {
                       0f
                   }
   ```
   with
   ```kotlin
                   // Only allocate animation state when trailer is actually playing. Held as a State
                   // and read in the cover's graphicsLayer, so the fade redraws without recomposing.
                   // The player snaps opaque under the cover (firstFrameFadeIn = false), so the cover
                   // fading out never reveals the black plate.
                   val trailerCoverAlphaState = if (shouldPlayTrailerPreview) {
                       animateFloatAsState(
                           targetValue = if (!trailerFirstFrameRendered) 1f else 0f,
                           animationSpec = FetchMotion.exitTween(),
                           label = "trailerCoverAlpha"
                       )
                   } else {
                       null
                   }
   ```

5. **`ui/components/ContentCard.kt:465-478`: the `TrailerPlayer(...)` call.** Add the
   argument `firstFrameFadeIn = false,` after `muted = focusedPosterBackdropTrailerMuted`.
   Add a comma after that argument first, so the call ends:
   ```kotlin
                           modifier = Modifier.fillMaxSize(),
                           muted = focusedPosterBackdropTrailerMuted,
                           firstFrameFadeIn = false
                       )
   ```

6. **`ui/components/ContentCard.kt:487`: the cover modifier.** Replace
   `                            .graphicsLayer { alpha = trailerCoverAlpha },` with
   `                            .graphicsLayer { alpha = trailerCoverAlphaState?.value ?: 0f },`

7. Leave the `tween` and `animateFloatAsState` imports in ContentCard.kt. Both are still
   used, at :641 and elsewhere.

## Boundaries

- Do NOT change `ModernHomeRows.kt`, `ModernHomeHero.kt`, `MetaDetailsScreen.kt`,
  `PostPlayRecommendationOverlay.kt`, `SharedTrailerOverlay.kt` or
  `YouTubePlayerOverlay.kt`. They keep the default `firstFrameFadeIn = true`.
- Do NOT change ContentCard's card shell (`Card(...)`, `CardDefaults.scale`). Plans 016 and
  017 own that.
- Do NOT change the black plate or the landscape logo overlay logic.
- Do NOT touch `letterboxZoomState` (TrailerPlayer.kt:88-92).
- If an excerpt does not match the file (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes. No
    literal tween is added or removed.
  - No existing unit test covers ContentCard or TrailerPlayer
    (`grep -rln "ContentCard\|TrailerPlayer" app/src/test` is empty).
- **Feel check** (TV or emulator, classic Home layout, or Library server rows / Search
  results, which use `CatalogRowSection` → `ContentCard`):
  - In Settings, turn on focused-poster backdrop expand and trailer-in-card. Focus a poster
    with a trailer and wait for it to expand and play.
  - Set Animator duration scale to **10x**. Watch the moment the video appears. The
    backdrop must cross straight into moving video, with no darker-than-both frame in
    between. Before the change you can see the card go dark for a beat at 10x.
  - Use a trailer that is not 16:9. Once the cover is gone, the black letterbox bars are
    still there.
  - At 1x, the reveal is short (150ms) and reads as "the poster came alive", not as a
    slow fade. If it feels abrupt, try `FetchMotion.enterTween()` (200ms) on the cover and
    compare A/B. Report the choice; do not invent another duration.
  - Open a title's detail page and start and stop the trailer (MetaDetailsScreen). It
    disappears quickly on stop, with no flash.
  - At the end of an episode, let the post-play recommendation trailer play, then stop it.
    It disappears quickly.
  - Home "Modern" layout trailer-in-card (ModernHomeRows) and the hero trailer still fade
    in over the image as before. They are unchanged.
  - **Performance**: in Layout Inspector with recomposition counts on, `ContentCard` must
    not gain about 10 recompositions during the cover fade. It should change by at most 1
    or 2 on the first-frame signal.
- **Done when**:
  - The cover alpha is read only inside `graphicsLayer {}`.
  - ContentCard passes `firstFrameFadeIn = false`.
  - The TrailerPlayer defaults are `enterTween`/`exitTween`.
  - There is no black dip at 10x.
