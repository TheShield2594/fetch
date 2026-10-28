# 012 — Move the playback-start overlays (parental guide, display mode) to layer reads and tighter timing

- **Status**: TODO
- **Commit**: ee8dd84
- **Severity**: MEDIUM
- **Category**: 5. Performance + 2. Easing & duration (with 6. Accessibility, 7. Cohesion: stagger)
- **Estimated scope**: 2 files (`ParentalGuideOverlay.kt`, `DisplayModeOverlay.kt`), ~60 lines each rewritten
- **Depends on**: 001 (uses `FetchMotion.enterTween()`, `FetchMotion.exitTween()`, `FetchMotion.tokens.durations.move`, `FetchMotion.tokens.easings.out`)

All paths are relative to `app/src/main/java/com/fetch/tv/`.

## Problem

Two overlays run the same choreography when playback starts:

- `ui/screens/player/ParentalGuideOverlay.kt` shows content warnings.
- `ui/screens/player/DisplayModeOverlay.kt` shows refresh rate and resolution after a
  frame-rate (AFR) switch.

They appear exactly while the first frames decode and the display may be switching mode.

`ParentalGuideOverlay.kt:61-112` (`DisplayModeOverlay.kt:62-103` is the same, without the
comments):

```kotlin
    val containerAlpha = remember { Animatable(0f) }
    val lineHeightFraction = remember { Animatable(0f) }
    val itemAlphas = remember(count) { List(count) { Animatable(0f) } }
    var animating by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible) {
        if (isVisible && !animating) {
            animating = true
            containerAlpha.animateTo(1f, tween(FetchMotion.tokens.durations.medium))
            lineHeightFraction.animateTo(1f, tween(FetchMotion.tokens.durations.slow, easing = FastOutSlowInEasing))
            for (i in 0 until count) {
                delay(80)
                itemAlphas[i].animateTo(1f, tween(FetchMotion.tokens.durations.fast))
            }
            delay(5000)
            for (i in (count - 1) downTo 0) {
                delay(60)
                itemAlphas[i].animateTo(0f, tween(FetchMotion.tokens.durations.quick))
            }
            delay(100)
            lineHeightFraction.animateTo(0f, tween(FetchMotion.tokens.durations.medium, easing = FastOutSlowInEasing))
            delay(200)
            containerAlpha.animateTo(0f, tween(FetchMotion.tokens.durations.fast))
            animating = false
            onAnimationComplete()
        } else if (!isVisible && animating) {
            // Quick hide if something forces it off (e.g. controls shown during animation)
            for (i in (count - 1) downTo 0) {
                itemAlphas[i].snapTo(0f)
            }
            lineHeightFraction.snapTo(0f)
            containerAlpha.snapTo(0f)
            animating = false
            onAnimationComplete()
        }
    }

    if (containerAlpha.value <= 0f) return
```

They are applied in composition:

- **Parental guide**:
  - `:118` `.alpha(containerAlpha.value)`
  - `:126` `.height((totalLineHeight * lineHeightFraction.value).dp)`
  - `:140` `.alpha(itemAlphas.getOrNull(index)?.value ?: 0f)`
- **Display mode**:
  - `:109` `.alpha(containerAlpha.value)`
  - `:139` `.alpha(itemAlphas.getOrNull(index)?.value ?: 0f)`
  - `:167` `.height((totalLineHeight * lineHeightFraction.value).dp)`

The costs:

- **Per-frame recomposition plus relayout.** Every frame of the sequence recomposes the
  overlay, and the line's animated `height` relayouts it. The entrance alone is
  350 + 450 + count × (80 + 180) ms, which is about 1.3 s for two items and longer for the
  parental guide.
- **Serial items, not a stagger.** Each `animateTo` is awaited before the next item's
  `delay(80)`, so the items enter one after another.
- **Slow and weak curves.** The durations are 350/450 ms, past the 300 ms UI budget, with
  `FastOutSlowInEasing` or the default curve.
- **No reduced-motion gate.** `FetchTheme.fullMotion` is ignored, so low-RAM devices, where it
  is false, pay the full cost.

## Target

Applies identically to both files.

- **Rendering**:
  - All alphas use lambda `graphicsLayer { alpha = x.value }`.
  - The line is a fixed-height `Box` (`totalLineHeight.dp`) with
    `graphicsLayer { scaleY = lineHeightFraction.value; transformOrigin = TransformOrigin(0.5f, 0f) }`,
    so it grows top-down exactly as before. The Row/Column height does not change, because the
    item column is always full height.
  - The early return reads a `derivedStateOf { containerAlpha.value > 0f }`, so composition
    only reruns when the overlay appears or disappears.
- **Full-motion timing** (`FetchTheme.fullMotion == true`):

| Phase | Today | Target |
|---|---|---|
| container fade in | `tween(medium)` 350 ms | `FetchMotion.enterTween()` (200 ms, ease-out) |
| line grow | `tween(slow, FastOutSlowIn)` 450 ms | `tween(FetchMotion.tokens.durations.move, easing = FetchMotion.tokens.easings.out)` (250 ms) |
| items in | serial: `delay(80)` + `tween(fast)` each | concurrent stagger: item *i* starts at `i × 50 ms`, `FetchMotion.enterTween()` each |
| hold | `delay(5000)` | unchanged, 5000 ms |
| items out | serial, reverse: `delay(60)` + `tween(quick)` each | concurrent stagger in reverse (last item first), `50 ms` apart, `FetchMotion.exitTween()` (150 ms) |
| line shrink + container fade | `delay(100)`, line `tween(medium)`, `delay(200)`, container `tween(fast)` | together, no gaps: line `FetchMotion.exitTween()` and container `FetchMotion.exitTween()` |
| forced hide (`!isVisible && animating`) | snapTo 0 | unchanged |

- **Reduced motion** (`FetchTheme.fullMotion == false`):
  - Show: snap the line to 1 and the items to 1, then fade the container in with
    `enterTween()`.
  - Hold for 5000 ms.
  - Hide: fade the container out with `exitTween()`, then snap the line and the items to 0.
  - There is no line growth and no stagger.
- The 50 ms stagger is a named constant (within the 30–80 ms band). It is not caught by
  `ThemeLiteralGuardTest`, because only `tween(<digits>` is counted. The 5000 ms hold also
  becomes a named constant.

## Repo conventions to follow

- Reduced motion: `ui/screens/player/PlaybackDetailsBar.kt:95-105` (`val fullMotion = FetchTheme.fullMotion`).
- Draw-phase/layer reads: `ui/components/PlaceholderShimmer.kt`. Animated values are read only
  inside drawing or layer lambdas.
- Tokens from `ui/theme/MotionFocusTokens.kt` (plan 001). No literal-millisecond `tween(...)`.
  Neither file is listed in `app/src/test/resources/theme-literals-baseline.txt`, and that
  stays true.
- Do not add `RoundedCornerShape(<N>.dp)` literals. Keep the existing
  `RoundedCornerShape(FetchTheme.spacing.hairline)` as is.

## Steps

### ParentalGuideOverlay.kt

1. Below `private val ROW_GAP = FetchTheme.spacing.xxs` (`:39`), add:
   ```kotlin
   /** Gap between consecutive items starting to fade, on the way in and out. */
   internal const val START_OVERLAY_ITEM_STAGGER_MS = 50L
   /** How long the playback-start overlays stay up before hiding themselves. */
   internal const val START_OVERLAY_HOLD_MS = 5_000L
   ```
   `DisplayModeOverlay.kt` is in the same package and reuses both.
2. Replace the whole `LaunchedEffect(isVisible) { ... }` block and the line
   `if (containerAlpha.value <= 0f) return` (`:66-114`) with the following. The four
   `remember` lines above it (`:61-64`) stay as they are.
   ```kotlin
       val fullMotion = FetchTheme.fullMotion

       LaunchedEffect(isVisible) {
           if (isVisible && !animating) {
               animating = true
               playStartOverlayIn(fullMotion, containerAlpha, lineHeightFraction, itemAlphas)
               delay(START_OVERLAY_HOLD_MS)
               playStartOverlayOut(fullMotion, containerAlpha, lineHeightFraction, itemAlphas)
               animating = false
               onAnimationComplete()
           } else if (!isVisible && animating) {
               // Quick hide if something forces it off (e.g. controls shown during animation)
               for (i in (count - 1) downTo 0) {
                   itemAlphas[i].snapTo(0f)
               }
               lineHeightFraction.snapTo(0f)
               containerAlpha.snapTo(0f)
               animating = false
               onAnimationComplete()
           }
       }

       // Recompose only when the overlay appears or disappears, not on every fade frame.
       val shown by remember { derivedStateOf { containerAlpha.value > 0f } }
       if (!shown) return
   ```
3. At the end of the file, add the two shared sequence helpers. They are `internal`, so
   `DisplayModeOverlay.kt` uses them too:
   ```kotlin
   /** Container fades in, the accent line grows top-down, then the items stagger in. */
   internal suspend fun playStartOverlayIn(
       fullMotion: Boolean,
       containerAlpha: Animatable<Float, AnimationVector1D>,
       lineHeightFraction: Animatable<Float, AnimationVector1D>,
       itemAlphas: List<Animatable<Float, AnimationVector1D>>
   ) {
       if (!fullMotion) {
           lineHeightFraction.snapTo(1f)
           itemAlphas.forEach { it.snapTo(1f) }
           containerAlpha.animateTo(1f, FetchMotion.enterTween())
           return
       }
       containerAlpha.animateTo(1f, FetchMotion.enterTween())
       lineHeightFraction.animateTo(
           1f,
           tween(FetchMotion.tokens.durations.move, easing = FetchMotion.tokens.easings.out)
       )
       coroutineScope {
           itemAlphas.forEachIndexed { index, alpha ->
               launch {
                   delay(index * START_OVERLAY_ITEM_STAGGER_MS)
                   alpha.animateTo(1f, FetchMotion.enterTween())
               }
           }
       }
   }

   /** Items stagger out bottom-up, then the line and the container leave together. */
   internal suspend fun playStartOverlayOut(
       fullMotion: Boolean,
       containerAlpha: Animatable<Float, AnimationVector1D>,
       lineHeightFraction: Animatable<Float, AnimationVector1D>,
       itemAlphas: List<Animatable<Float, AnimationVector1D>>
   ) {
       if (!fullMotion) {
           containerAlpha.animateTo(0f, FetchMotion.exitTween())
           lineHeightFraction.snapTo(0f)
           itemAlphas.forEach { it.snapTo(0f) }
           return
       }
       coroutineScope {
           itemAlphas.asReversed().forEachIndexed { order, alpha ->
               launch {
                   delay(order * START_OVERLAY_ITEM_STAGGER_MS)
                   alpha.animateTo(0f, FetchMotion.exitTween())
               }
           }
       }
       coroutineScope {
           launch { lineHeightFraction.animateTo(0f, FetchMotion.exitTween()) }
           launch { containerAlpha.animateTo(0f, FetchMotion.exitTween()) }
       }
   }
   ```
   `coroutineScope` children are cancelled along with the `LaunchedEffect`, so the
   forced-hide path (the effect restarting with `isVisible == false`) still interrupts
   cleanly.
4. In the layout (`:116-140`):
   - Replace `.alpha(containerAlpha.value)` with
     `.graphicsLayer { alpha = containerAlpha.value }`.
   - Replace the line `Box`:
     ```kotlin
             Box(
                 modifier = Modifier
                     .width(3.dp)
                     .height((totalLineHeight * lineHeightFraction.value).dp)
                     .clip(RoundedCornerShape(FetchTheme.spacing.hairline))
                     .background(accentBrush)
             )
     ```
     with:
     ```kotlin
             Box(
                 modifier = Modifier
                     .width(3.dp)
                     .height(totalLineHeight.dp)
                     // Grows top-down as a transform, so the line never relayouts.
                     .graphicsLayer {
                         scaleY = lineHeightFraction.value
                         transformOrigin = TransformOrigin(0.5f, 0f)
                     }
                     .clip(RoundedCornerShape(FetchTheme.spacing.hairline))
                     .background(accentBrush)
             )
     ```
   - Replace `.alpha(itemAlphas.getOrNull(index)?.value ?: 0f)` with
     `.graphicsLayer { alpha = itemAlphas.getOrNull(index)?.value ?: 0f }`.
5. Imports in `ParentalGuideOverlay.kt`:
   - Remove `androidx.compose.animation.core.FastOutSlowInEasing` and
     `androidx.compose.ui.draw.alpha`.
   - Add:
     - `androidx.compose.animation.core.AnimationVector1D`
     - `androidx.compose.runtime.derivedStateOf`
     - `androidx.compose.ui.graphics.graphicsLayer`
     - `androidx.compose.ui.graphics.TransformOrigin`
     - `kotlinx.coroutines.coroutineScope`
     - `kotlinx.coroutines.launch`
   - Keep `Animatable`, `tween` and `delay`.

### DisplayModeOverlay.kt

6. Replace `LaunchedEffect(isVisible, info) { ... }` and `if (containerAlpha.value <= 0f) return`
   (`:67-105`) with the same block as step 2. Keep the keys `LaunchedEffect(isVisible, info)`
   (not just `isVisible`) and add the same `val fullMotion = FetchTheme.fullMotion` line above
   it. The forced-hide branch is identical to the one in step 2.
7. In the layout:
   - `:109`: replace `.alpha(containerAlpha.value)` with
     `.graphicsLayer { alpha = containerAlpha.value }`.
   - `:139`: replace `.alpha(itemAlphas.getOrNull(index)?.value ?: 0f)` with
     `.graphicsLayer { alpha = itemAlphas.getOrNull(index)?.value ?: 0f }`.
   - `:164-170`: the line `Box` becomes the following. Same pattern as step 4, but keep its
     colour `FetchTheme.colors.Secondary`:
     ```kotlin
                 Box(
                     modifier = Modifier
                         .width(3.dp)
                         .height(totalLineHeight.dp)
                         .graphicsLayer {
                             scaleY = lineHeightFraction.value
                             transformOrigin = TransformOrigin(0.5f, 0f)
                         }
                         .clip(RoundedCornerShape(FetchTheme.spacing.hairline))
                         .background(FetchTheme.colors.Secondary)
                 )
     ```
8. Imports in `DisplayModeOverlay.kt`:
   - Remove `FastOutSlowInEasing`, `androidx.compose.ui.draw.alpha` and `tween` (it is no
     longer used here; grep to confirm).
   - Add `derivedStateOf`, `graphicsLayer` and `TransformOrigin`.
   - Keep `Animatable` and `delay` (still used by `delay(START_OVERLAY_HOLD_MS)`).
   - `com.fetch.tv.ui.theme.FetchMotion` is no longer referenced in this file (grep to
     confirm), so remove that import too.

## Boundaries

- Do NOT change the call sites in `PlayerScreen.kt` (`:1477`, `:1514`), `onAnimationComplete`
  semantics, the 5 s hold, the warning/label text, colours, sizes or `formatHz`.
- Do NOT change the `remember` keys of `itemAlphas` (`count` in one file, `info` in the
  other).
- Keep the `animating` flag and the forced-hide branch exactly as they are.
- If the code does not match the excerpts (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes with no
    baseline change.
  - No unit test covers these composables. There is no `formatHz` test (confirmed with
    `grep -rn "formatHz\|ParentalGuide" app/src/test`).
- **Feel check** (TV or emulator):
  - Start a title that has parental-guide data. With a display that switches mode, enable
    frame-rate matching for the display-mode overlay. Each overlay:
    - fades in quickly;
    - its accent line draws downward from the top (not from the centre, and not by pushing
      text);
    - its items ripple in 50 ms apart rather than one by one.
  - After about 5 s the items leave bottom-up, then the line and the panel go together.
  - Animator duration scale 10x: no layout jumps; the text column never moves while the line
    grows.
  - Show the controls (D-pad Down) while an overlay is animating in: it disappears at once, as
    before.
  - Animator duration scale → "Animation off" (or a low-RAM device): the overlays simply fade
    in, hold and fade out, with the line already full length and no stagger.
  - Layout Inspector recomposition counts: during the sequence, `ParentalGuideOverlay` and
    `DisplayModeOverlay` recompose only when they appear and disappear, not every frame.
  - `adb shell dumpsys gfxinfo com.fetch.tv.debug reset` before pressing play, then dump 5 s
    in. Janky frames at playback start should not exceed a build without this change.
- **Done when**:
  - `grep -n "\.alpha(\|lineHeightFraction.value).dp\|FastOutSlowInEasing" ParentalGuideOverlay.kt DisplayModeOverlay.kt`
    is empty.
  - The build is green.
