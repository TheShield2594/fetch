# 006 — Stop the loading overlay recomposing every frame; glide its inset in the draw phase

- **Status**: TODO
- **Commit**: ee8dd84
- **Severity**: HIGH
- **Category**: 1. Performance (deferred state reads) + 2. Easing & duration
- **Estimated scope**: 1 file (`LoadingOverlay.kt`), ~60 lines changed
- **Depends on**: 001 (uses `FetchMotion.tokens.easings.inOut` and `FetchMotion.moveTween()`)

## Problem

`app/src/main/java/com/fetch/tv/ui/screens/player/LoadingOverlay.kt` is shown on every fetch
(StreamScreen), every player buffer (PlayerScreen) and every external auto-next
(MainActivity). It runs for the whole load, often several seconds, on low-end TV boxes.

**(a) The logo pulse recomposes the whole overlay every frame.** Lines 90-103:

```kotlin
// LoadingOverlay.kt:90-103 — current
val logoScale = if (FetchTheme.fullMotion) {
    val infiniteTransition = rememberInfiniteTransition(label = "loadingLogoPulse")
    infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loadingLogoScale"
    ).value
} else {
    1f
}
```

`.value` is read at the top of the `AnimatedVisibility` content, so the content
(two `AsyncImage`s, `BoxWithConstraints`, the message `Crossfade`) recomposes on every frame.
The pulse also keeps running while the logo-fill mode is active (`progress != null`), even
though the scale is not applied then (lines 191-197):

```kotlin
// LoadingOverlay.kt:191-197 — current
.graphicsLayer {
    alpha = if (isLogoFillActive) 0.25f else logoAlpha
    if (!isLogoFillActive) {
        scaleX = logoScale
        scaleY = logoScale
    }
},
```

`LinearEasing` on a reversing pulse gives a hard turnaround at each end.

**(b) The logo "fade-in" is dead code.** Lines 85-89:

```kotlin
// LoadingOverlay.kt:85-89 — current
val logoAlpha by animateFloatAsState(
    targetValue = 1f,
    animationSpec = tween(durationMillis = FetchMotion.tokens.durations.long, delayMillis = 400, easing = LinearEasing),
    label = "loadingLogoAlpha"
)
```

The target is the constant `1f`. `animateFloatAsState` starts at its first target, so this
value is `1f` from the first frame and never animates; the 600ms + 400ms delay never runs.
The logo actually appears via the overlay's own `fadeIn` (line 80, 180ms) plus Coil's
`crossfade(true)` (lines 115-116). Remove it rather than "fix" it: adding a real delayed fade
would make the logo appear later than it does today.

**(c) The bottom inset animates layout.** Lines 72-76 and 155:

```kotlin
// LoadingOverlay.kt:72-76 — current
val animatedBottomInset by animateDpAsState(
    targetValue = bottomInset,
    animationSpec = tween(FetchMotion.tokens.durations.medium, easing = FetchMotion.tokens.easings.standard),
    label = "loadingBottomInset"
)
// LoadingOverlay.kt:152-156 — current
BoxWithConstraints(
    modifier = Modifier
        .fillMaxSize()
        .padding(bottom = animatedBottomInset),
    contentAlignment = Alignment.Center
) {
```

`.padding(bottom = animated)` re-measures `BoxWithConstraints` (a subcomposition) and its
whole subtree every frame for 350ms. Only StreamScreen passes a non-zero inset
(`StreamScreen.kt:455-459`): `fetchingPanelHeight + 48.dp` while the Fetching panel is shown,
else `0.dp`. It changes:
1. once, 0 → measured, one or two frames after the overlay appears (the panel's
   `onSizeChanged` at `StreamScreen.kt:481`). The overlay is still fading in and nothing has
   settled, so this change should **snap**.
2. when "Best so far" arrives and the panel grows (plan 025), and back to 0 when a stream is
   picked. These are visible and should **glide**.

The logo height depends on the real layout height (line 159:
`val logoHeight = (maxHeight - FetchTheme.spacing.xl * 2).coerceIn(48.dp, 180.dp)`), so the
padding must stay a real layout value; it just must not animate. On a 960×540dp TV the panel
is about 160–230dp tall, so the inset is about 210–280dp, `maxHeight` is 260–330dp and the
logo stays at its 180dp cap. It only shrinks when the panel is over ~264dp tall; in that case
it now snaps once instead of shrinking over 350ms.

**(d) The progress bar animates layout.** Lines 285-308: the fill is a child `Box` sized with
`.fillMaxWidth(animatedProgress)`, re-measured every frame of a `tween(overlay)` (400ms,
default FastOutSlowIn easing). Progress is a constant-rate indicator and should be linear.

**Literal baseline.** `app/src/test/resources/theme-literals-baseline.txt` line 7 is
`com/fetch/tv/ui/screens/player/LoadingOverlay.kt|DURATION|1`. The one match of the guard regex
in this file is `tween(durationMillis = 2000, easing = LinearEasing)` (line 96). The 160/400 fill
at lines 171-174 does not match (`durationMillis = if (...)`). This plan keeps the
`tween(durationMillis = 2000, ...)` literal (only its easing changes), so the count stays 1 and
the baseline is NOT edited.

## Target

- Pulse: same 1 → 1.04 over 2000ms, reversing, with easing `FetchMotion.tokens.easings.inOut`.
  The `InfiniteTransition` exists only while `FetchTheme.fullMotion && !isLogoFillActive`. Its
  `State` is read only inside `graphicsLayer {}`.
- No `logoAlpha` animation. The logo and title draw at alpha 1 (0.25 for the fill-mode base
  layer, as today).
- Inset: the layout padding uses `bottomInset` directly (snaps). A `Dp` `Animatable` tracks the
  drawn inset. It **snaps** when it is at `0.dp` (first measurement) or when `!fullMotion`;
  otherwise it animates with `FetchMotion.moveTween()` (250ms, inOut). The content is moved
  back to where it was drawn with
  `graphicsLayer { translationY = (bottomInset - drawnInset).toPx() / 2f }`. The content is
  centred, so a change of Δ in bottom padding moves its centre by Δ/2:
  centre = (H − inset)/2. While the drawn inset is behind the target, the content is drawn
  (target − drawn)/2 lower, which is exactly the old centre.
- Progress bar: one `Box` whose fill is drawn in `drawBehind` from a
  `State<Float>` animated with `tween(FetchMotion.tokens.durations.overlay, easing = LinearEasing)`.

## Repo conventions to follow

- Draw-phase reads: `app/src/main/java/com/fetch/tv/ui/screens/player/PlayerProgressFill.kt`
  (progress drawn in `drawBehind`, value read inside the lambda).
- Reduced motion: `val fullMotion = FetchTheme.fullMotion`, then branch
  (`app/src/main/java/com/fetch/tv/ui/screens/player/PlaybackDetailsBar.kt:95-104`).
- Never add a new literal `tween(<number>` in this file (ThemeLiteralGuardTest).

## Steps

1. **Imports.** In `LoadingOverlay.kt`:
   - Remove `import androidx.compose.animation.core.animateDpAsState`.
   - Add:
     ```kotlin
     import androidx.compose.animation.core.Animatable
     import androidx.compose.animation.core.VectorConverter
     import androidx.compose.runtime.LaunchedEffect
     import androidx.compose.runtime.State
     import androidx.compose.ui.draw.drawBehind
     import androidx.compose.ui.geometry.CornerRadius
     import androidx.compose.ui.geometry.Size
     ```
   - Keep `LinearEasing` (still used by the fill and the progress bar) and `tween`.

2. **Inset (c).** Replace lines 72-76 (`val animatedBottomInset by animateDpAsState(...)`) with:
   ```kotlin
   val fullMotion = FetchTheme.fullMotion
   // Layout takes the new inset at once; the drawn content glides there (see the BoxWithConstraints).
   val drawnBottomInset = remember { Animatable(bottomInset, Dp.VectorConverter) }
   LaunchedEffect(bottomInset, fullMotion) {
       // The first inset arrives a frame after the panel is measured, while the overlay is
       // still fading in: snap it so the logo never drifts on open.
       if (!fullMotion || drawnBottomInset.value == 0.dp) {
           drawnBottomInset.snapTo(bottomInset)
       } else {
           drawnBottomInset.animateTo(bottomInset, FetchMotion.moveTween())
       }
   }
   ```

3. **Logo alpha (b) and pulse (a).** Delete lines 85-103 (both `val logoAlpha by ...` and
   `val logoScale = ...`). In their place put:
   ```kotlin
   // Only the idle logo or title breathes; the fill mode holds still.
   val logoPulse = rememberLogoPulse(enabled = fullMotion && !(showLogo && progress != null))
   ```
   Add this private function at the end of the file:
   ```kotlin
   /**
    * The idle logo's slow breathing scale, or null when it should hold still. Read the value
    * only in a graphicsLayer, so the pulse redraws without recomposing the overlay.
    */
   @Composable
   private fun rememberLogoPulse(enabled: Boolean): State<Float>? {
       if (!enabled) return null
       val transition = rememberInfiniteTransition(label = "loadingLogoPulse")
       return transition.animateFloat(
           initialValue = 1f,
           targetValue = 1.04f,
           animationSpec = infiniteRepeatable(
               animation = tween(durationMillis = 2000, easing = FetchMotion.tokens.easings.inOut),
               repeatMode = RepeatMode.Reverse
           ),
           label = "loadingLogoScale"
       )
   }
   ```
   Note `isLogoFillActive` is defined later inside the logo branch as `progress != null`; the
   `enabled` expression above is the same condition, computed earlier.

4. **Inset glide on the content (c).** Change the `BoxWithConstraints` modifier (lines 153-155) to:
   ```kotlin
   modifier = Modifier
       .fillMaxSize()
       .padding(bottom = bottomInset)
       .graphicsLayer {
           // Centred content moves half as far as its bottom padding changes.
           translationY = (bottomInset - drawnBottomInset.value).toPx() / 2f
       },
   ```

5. **Logo base layer.** Replace the `graphicsLayer` block at lines 191-197 with:
   ```kotlin
   .graphicsLayer {
       alpha = if (isLogoFillActive) 0.25f else 1f
       val scale = logoPulse?.value ?: 1f
       scaleX = scale
       scaleY = scale
   },
   ```

6. **Title text.** Replace the `graphicsLayer` block at lines 230-234 with:
   ```kotlin
   .graphicsLayer {
       val scale = logoPulse?.value ?: 1f
       scaleX = scale
       scaleY = scale
   }
   ```

7. **Progress bar (d).** Replace lines 285-308 (from `val animatedProgress by animateFloatAsState(`
   through the closing `}` of the outer track `Box`) with:
   ```kotlin
   val animatedProgress = animateFloatAsState(
       targetValue = progress.coerceIn(0f, 1f),
       // Constant-rate indicator: linear, and read only while drawing.
       animationSpec = tween(durationMillis = FetchMotion.tokens.durations.overlay, easing = LinearEasing),
       label = "loadingProgress"
   )
   val fillColor = Color.White.copy(alpha = 0.85f)
   val fillRadius = FetchTheme.radii.xxs
   Box(
       modifier = Modifier
           .width(240.dp)
           .height(FetchTheme.spacing.xs)
           .background(
               color = Color.White.copy(alpha = 0.2f),
               shape = RoundedCornerShape(FetchTheme.radii.xxs)
           )
           .drawBehind {
               val fillWidth = size.width * animatedProgress.value
               if (fillWidth > 0f) {
                   drawRoundRect(
                       color = fillColor,
                       size = Size(fillWidth, size.height),
                       cornerRadius = CornerRadius(fillRadius.toPx())
                   )
               }
           }
   )
   ```
   (`progress` is smart-cast non-null here exactly as in the current code, because
   `showHorizontalBar` requires `progress != null`.)

8. Remove any import the compiler now reports unused (expected: none besides
   `animateDpAsState`). Do NOT touch `theme-literals-baseline.txt`: the file still has exactly
   one `tween(durationMillis = 2000` match.

## Boundaries

- Only edit `app/src/main/java/com/fetch/tv/ui/screens/player/LoadingOverlay.kt`.
- Do NOT touch `StreamScreen.kt` (plans 013 and 025 edit it), `FetchingPanel.kt` (plan 025),
  `PlayerScreen.kt`, `MainActivity.kt`, or the logo-fill animation at lines 164-176 (its
  160/400 values are out of scope).
- Do NOT change the overlay's own `fadeIn`/`fadeOut` (lines 80-81) or the message `Crossfade`.
- Do NOT edit `app/src/test/resources/theme-literals-baseline.txt`.
- If the code doesn't match the excerpts (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes with the
    LoadingOverlay count still 1.
  - `grep -n "\.value" app/src/main/java/com/fetch/tv/ui/screens/player/LoadingOverlay.kt`:
    every hit is inside a `graphicsLayer {}` or `drawBehind {}` lambda, or is
    `drawnBottomInset.value` in the `LaunchedEffect`.
  - No other test covers this composable (`ReadmeScreenshots.fetchingPanel` renders it only
    when `FETCH_SCREENSHOTS=1`; optionally run it and check the logo sits centred above the panel).
- **Feel check** (TV or emulator):
  - Developer options → Animator duration scale 10x. Play a title with auto-play on, so the
    Fetching panel shows. The logo is centred above the panel from its first visible frame;
    it does not slide up on open.
  - When "Best so far" appears and the panel grows, the logo glides up (250ms at 1x) and
    eases in and out. No size pop unless the panel is very tall.
  - The pulse eases at both ends (no hard turnaround). With a buffering percentage showing
    (logo fill), the logo holds still.
  - Performance: Layout Inspector recomposition counts for `LoadingOverlay` stay flat while the
    overlay sits on screen (before: one recomposition per frame). Or compare
    `adb shell dumpsys gfxinfo com.fetch.tv.debug` jank stats over a 10s load before/after.
  - A load without a logo (title text) still pulses; a torrent load with a horizontal progress
    bar fills smoothly and linearly.
  - Animator duration scale "Animation off": no pulse, the logo snaps above the panel, the
    overlay still appears and disappears.
- **Done when**: the build and guard test are green, the overlay no longer recomposes per
  frame, and the logo never moves on open.
