# 005 — Draw the Skip Intro countdown, tighten its entrance, and lift it with a transform

- **Status**: TODO
- **Commit**: ee8dd84
- **Severity**: HIGH
- **Category**: 5. Performance + 2. Easing & duration + 3. Physicality & origin
- **Estimated scope**: 2 files (`SkipIntroButton.kt` ~30 lines, `PlayerScreen.kt` ~20 lines)
- **Depends on**: 001 (uses `FetchMotion.enterTween()` and `FetchMotion.exitTween()`)

All paths are relative to `app/src/main/java/com/fetch/tv/`. The video decodes underneath
these Compose overlays, so any per-frame overlay work competes with decode on weak TV SoCs.

## Problem

### (a) The countdown bar recomposes and re-measures every frame for the whole segment

`ui/screens/player/SkipIntroButton.kt:99` holds the countdown in an `Animatable`:

```kotlin
    val progress = remember { Animatable(1f) }
```

`:120-128` animates it linearly over the rest of the auto-hide timeout. This logic is correct;
keep it as it is:

```kotlin
    LaunchedEffect(shouldShow, autoHidden, controlsVisible) {
        if (shouldShow && !autoHidden && !controlsVisible) {
            progress.animateTo(1f, animationSpec = tween(
                durationMillis = skipIntroAutoHideRemainingMs(progress.value, autoHideTimeoutMs),
                easing = LinearEasing
            ))
            autoHidden = true
        }
    }
```

The bar reads `progress.value` **in composition** (`:233-246`), inside a
`Column(Modifier.width(IntrinsicSize.Max))` (`:213-214`):

```kotlin
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(FetchTheme.spacing.xs)
                        .clip(RoundedCornerShape(bottomStart = FetchTheme.spacing.md, bottomEnd = FetchTheme.spacing.md))
                        .background(Color.White.copy(alpha = if (controlsVisible || autoHidden || dismissed) 0f else 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.value)
                            .height(FetchTheme.spacing.xs)
                            .background(FetchPrimitives.neutral875.copy(alpha = if (controlsVisible || autoHidden || dismissed) 0f else 0.85f))
                    )
                }
```

So the Card's content recomposes and the Column runs intrinsic measurement on every frame for
the whole countdown. That countdown is the segment length: at least 10 s and up to 120 s
(`ui/screens/player/SkipIntroVisibilityRules.kt:78-89`, `SKIP_INTRO_AUTO_HIDE_TIMEOUT_MS = 10_000`
and `MAX_SKIP_INTRO_AUTO_HIDE_TIMEOUT_MS = 120_000`). It all happens during playback.

### (b) The entrance scales from 0.8 at the centre with a 350 ms default curve

`SkipIntroButton.kt:156-160`:

```kotlin
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(FetchMotion.tokens.durations.medium)) + scaleIn(tween(FetchMotion.tokens.durations.medium), initialScale = 0.8f),
        exit = fadeOut(tween(FetchMotion.tokens.durations.fast)) + scaleOut(tween(FetchMotion.tokens.durations.fast), targetScale = 0.8f),
        modifier = modifier
    ) {
```

There are three problems:

- 350 ms with the default `FastOutSlowInEasing` is slow for a button that re-enters every time
  the controls toggle.
- 0.8 is too far; the target range is 0.9–0.97.
- The scale grows from the centre, but the button is anchored bottom-start on screen.

None of it is gated on reduced motion.

### (c) Lifting the button relayouts it and recomposes the whole PlayerScreen every frame

`ui/screens/player/PlayerScreen.kt:1378-1383`:

```kotlin
        val skipButtonBottomPadding by animateDpAsState(
            // Clears the scrubber with the transport and panel rows below it.
            targetValue = if (uiState.showControls) 174.dp else 30.dp,
            animationSpec = tween(durationMillis = FetchMotion.tokens.durations.fast),
            label = "skipButtonBottomPadding"
        )
```

`PlayerScreen.kt:1428-1432` (the `modifier` argument of `SkipIntroButton(...)`):

```kotlin
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = FetchTheme.spacing.xxl, bottom = skipButtonBottomPadding)
                .zIndex(2.1f)
                .reportSubtitleClearance(subtitleClearanceGapPx) { skipClearancePx = it }
```

`reportSubtitleClearance` (`PlayerScreen.kt:2314-2320`) is an `onGloballyPositioned`:

```kotlin
private fun Modifier.reportSubtitleClearance(gapPx: Float, onClearance: (Float) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        val rootHeight = coordinates.findRootCoordinates().size.height
        if (rootHeight > 0 && coordinates.size.height > 0) {
            onClearance((rootHeight - coordinates.boundsInRoot().top + gapPx).coerceAtLeast(0f))
        }
    }
```

The animated padding moves the button's layout position on each frame of the 180 ms lift, so
`skipClearancePx` gets a new value every frame. `skipClearancePx` is a `mutableFloatStateOf`
(`:1072`). It is read directly in the `PlayerScreen` function body, at `:1154-1165`. Every
`Box` in between is inline, so this read sits in PlayerScreen's own recompose scope:

```kotlin
                val subtitleLiftPx = computeSubtitleLiftPx(
                    ...
                    skipVisible = skipButtonActuallyVisible,
                    skipClearancePx = skipClearancePx,
                    ...
                )
```

The result feeds `subtitleLiftPx` of `MpvPlayerSurface` / `ExoPlayerSurface` (`:1173`, `:1189`).
Each frame therefore recomposes the whole ~4,400-line `PlayerScreen` and re-applies the
subtitle lift. This happens on every controls toggle.

`computeSubtitleLiftPx` (`ui/screens/player/SubtitleLiftRules.kt:9-32`) takes the max of
several clearances. The skip button's term is `if (skipVisible) skipClearancePx else 0f`.

## Target

### (a) Draw the fill instead of laying it out

Keep the track `Box` with its clip, height and track colour. Delete the inner `Box`. Draw the
fill in `drawBehind`, reading `progress.value` there (draw phase only). This is the same
pattern `ui/screens/player/PlayerProgressFill.kt` uses. Colours stay exactly the same. The
fill grows from the start edge (from the right in RTL), as the old `fillMaxWidth(fraction)`
child did.

### (b) Enter and exit

| | Full motion | Reduced motion (`FetchTheme.fullMotion == false`) |
|---|---|---|
| enter | `fadeIn(FetchMotion.enterTween()) + scaleIn(FetchMotion.enterTween(), initialScale = 0.95f, transformOrigin = <anchor>)` | `fadeIn(FetchMotion.enterTween())` |
| exit | `fadeOut(FetchMotion.exitTween()) + scaleOut(FetchMotion.exitTween(), targetScale = 0.95f, transformOrigin = <anchor>)` | `fadeOut(FetchMotion.exitTween())` |

- `enterTween()` is 200 ms with `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`.
- `exitTween()` is 150 ms with the same curve.
- `<anchor>` is the bottom-start corner: `TransformOrigin(0f, 1f)` in LTR and
  `TransformOrigin(1f, 1f)` in RTL.

### (c) Lift with a transform; report the clearance at the resting layout position plus the resting lift

- The button's layout position no longer animates. It always lays out with
  `bottom = SKIP_BUTTON_BOTTOM_PADDING` (30.dp).
- The lift is a `graphicsLayer { translationY = ... }`. Its value is read inside the layer
  lambda. The amount is a fraction 0→1 times `SKIP_BUTTON_CONTROLS_LIFT` (144.dp = 174 − 30, so
  the lifted resting position is pixel-identical to today's).
  - Duration and curve stay unchanged: `tween(FetchMotion.tokens.durations.fast)` (180 ms,
    default `FastOutSlowInEasing`, an ease-in-out for on-screen movement).
  - With reduced motion it is `snap()`.
- **How the subtitle clearance stays correct**, step by step:
  1. `reportSubtitleClearance` stays in the chain but sits **before** (outside) the new
     `graphicsLayer`. In Compose, `graphicsLayer` places its *wrapped* (inner) content with a
     layer, so modifiers to its left are measured in untransformed coordinates. The reported
     value is therefore the clearance of the button at its **un-lifted resting layout
     position**: `30.dp + buttonHeight + gap`. It does not change while the translation
     animates. If `onGloballyPositioned` fires during the animation, it writes the same float.
     `mutableFloatStateOf` ignores an equal write, so nothing recomposes.
  2. The lift itself is added analytically, from the **target** state rather than the
     animated value. At the `computeSubtitleLiftPx(...)` call, pass
     `skipClearancePx = if (uiState.showControls) skipClearancePx + skipButtonControlsLiftPx else skipClearancePx`.
     `skipButtonControlsLiftPx` is `SKIP_BUTTON_CONTROLS_LIFT.toPx()`. With controls visible this
     equals `174.dp + buttonHeight + gap`, exactly what today's code reports once the padding
     animation settles. With controls hidden it equals today's value at 30.dp.
  3. `uiState.showControls` is the same condition that drives the lift target, so the
     clearance always describes where the button is going to rest. Subtitles move once, to
     their final position, when controls toggle, the same way they already do for the controls'
     own clearance (`controlsVisible` gates that term). They no longer track each animated
     frame.
  4. Gating by `skipVisible = skipButtonActuallyVisible` is unchanged. When the button is
     hidden, the `AnimatedVisibility` measures to height 0, so `reportSubtitleClearance` does
     not report. That behaviour is unchanged too.

## Repo conventions to follow

- Draw-phase progress: `ui/screens/player/PlayerProgressFill.kt:15-37`
  (`drawBehind { ... size.width * played ... }`).
- Reduced motion branch: `ui/screens/player/PlaybackDetailsBar.kt:95-105`
  (`val fullMotion = FetchTheme.fullMotion`, then `if (fullMotion) ... else ...` for
  enter/exit).
- Motion tokens: `FetchMotion.enterTween()`, `FetchMotion.exitTween()`,
  `FetchMotion.tokens.durations.fast` (`ui/theme/MotionFocusTokens.kt`, after plan 001). Do not
  write a literal millisecond number in a `tween(...)`: `ThemeLiteralGuardTest` counts
  `tween(<digits>` per file, and neither file is in
  `app/src/test/resources/theme-literals-baseline.txt`, so their allowance is 0.
- `RoundedCornerShape(...)` with a literal `N.dp` inside also counts against the guard. Do not
  add one.
- `dp` constants used only by `PlayerScreen.kt` are `private val` at file level, next to
  `SUBTITLE_CONTROLS_LIFT` / `SUBTITLE_CONTROLS_GAP` (`PlayerScreen.kt:2311-2312`).

## Steps

`PlayerScreen.kt` is also edited by plans 008, 016 and 019. Find each excerpt by its text, not by
line number. If the text is not there, stop and report.

### SkipIntroButton.kt

1. Add imports (keep the existing ones):
   ```kotlin
   import androidx.compose.ui.draw.drawBehind
   import androidx.compose.ui.geometry.Offset
   import androidx.compose.ui.geometry.Size
   import androidx.compose.ui.graphics.TransformOrigin
   import androidx.compose.ui.platform.LocalLayoutDirection
   import androidx.compose.ui.unit.LayoutDirection
   ```
2. Replace the `AnimatedVisibility(` header (`:156-160`, excerpt (b) above) with:
   ```kotlin
       val fullMotion = FetchTheme.fullMotion
       // Grow from the corner the button is anchored to (bottom-start).
       val anchor = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
           TransformOrigin(1f, 1f)
       } else {
           TransformOrigin(0f, 1f)
       }
       AnimatedVisibility(
           visible = isVisible,
           enter = if (fullMotion) {
               fadeIn(FetchMotion.enterTween()) +
                   scaleIn(FetchMotion.enterTween(), initialScale = 0.95f, transformOrigin = anchor)
           } else {
               fadeIn(FetchMotion.enterTween())
           },
           exit = if (fullMotion) {
               fadeOut(FetchMotion.exitTween()) +
                   scaleOut(FetchMotion.exitTween(), targetScale = 0.95f, transformOrigin = anchor)
           } else {
               fadeOut(FetchMotion.exitTween())
           },
           modifier = modifier
       ) {
   ```
3. Replace the countdown bar (`:233-246`, excerpt (a) above, from `Box(` to its closing `}`)
   with:
   ```kotlin
                   val countdownHidden = controlsVisible || autoHidden || dismissed
                   val countdownFill = FetchPrimitives.neutral875.copy(alpha = if (countdownHidden) 0f else 0.85f)
                   Box(
                       modifier = Modifier
                           .fillMaxWidth()
                           .height(FetchTheme.spacing.xs)
                           .clip(RoundedCornerShape(bottomStart = FetchTheme.spacing.md, bottomEnd = FetchTheme.spacing.md))
                           .background(Color.White.copy(alpha = if (countdownHidden) 0f else 0.15f))
                           // Read while drawing only: the countdown runs for the whole segment.
                           .drawBehind {
                               val fraction = progress.value.coerceIn(0f, 1f)
                               if (fraction > 0f && countdownFill.alpha > 0f) {
                                   val fillWidth = size.width * fraction
                                   val left = if (layoutDirection == LayoutDirection.Rtl) size.width - fillWidth else 0f
                                   drawRect(
                                       color = countdownFill,
                                       topLeft = Offset(left, 0f),
                                       size = Size(fillWidth, size.height)
                                   )
                               }
                           }
                   )
   ```
   Keep `Column(Modifier.width(IntrinsicSize.Max))` and the `Row` above it unchanged.
4. Leave `:99-136` (the `Animatable`, the `LinearEasing` countdown and pause/resume logic)
   unchanged. `tween` and `LinearEasing` stay imported because the countdown still uses them.

### PlayerScreen.kt

5. Next to `private val SUBTITLE_CONTROLS_LIFT = 130.dp` (`:2311`), add:
   ```kotlin
   /** Where the Skip Intro button rests while the controls are hidden. */
   private val SKIP_BUTTON_BOTTOM_PADDING = 30.dp
   /** Extra lift while controls are visible: 174.dp in total clears the scrubber, transport and panel rows. */
   private val SKIP_BUTTON_CONTROLS_LIFT = 144.dp
   ```
6. Directly below `val subtitleClearanceGapPx = with(LocalDensity.current) { SUBTITLE_CONTROLS_GAP.toPx() }`
   (`:1075`), add:
   ```kotlin
           val skipButtonControlsLiftPx = with(LocalDensity.current) { SKIP_BUTTON_CONTROLS_LIFT.toPx() }
   ```
7. In the `computeSubtitleLiftPx(` call (`:1154-1165`), replace the line
   `skipClearancePx = skipClearancePx,` with:
   ```kotlin
                       // Reported at the un-lifted layout position; add the lift the button rests at.
                       skipClearancePx = if (uiState.showControls) {
                           skipClearancePx + skipButtonControlsLiftPx
                       } else {
                           skipClearancePx
                       },
   ```
8. Replace the `skipButtonBottomPadding` block (`:1378-1383`) with:
   ```kotlin
           // 1 = lifted above the controls, 0 = resting near the bottom edge. Read only in the
           // button's graphicsLayer so the lift neither relayouts nor recomposes this screen.
           val skipButtonLift = animateFloatAsState(
               targetValue = if (uiState.showControls) 1f else 0f,
               animationSpec = if (FetchTheme.fullMotion) {
                   tween(durationMillis = FetchMotion.tokens.durations.fast)
               } else {
                   snap()
               },
               label = "skipButtonLift"
           )
   ```
   Use `FetchTheme.fullMotion` inline. Do **not** declare a local `val fullMotion` here,
   because plan 008 may add calls in the same scope.
9. Replace the `SkipIntroButton(... modifier = ...)` chain (`:1428-1432`) with the following
   (the order matters; see **Target (c)**):
   ```kotlin
               modifier = Modifier
                   .align(Alignment.BottomStart)
                   .padding(start = FetchTheme.spacing.xxl, bottom = SKIP_BUTTON_BOTTOM_PADDING)
                   .zIndex(2.1f)
                   // Must stay outside (before) the graphicsLayer: it reports the resting layout position.
                   .reportSubtitleClearance(subtitleClearanceGapPx) { skipClearancePx = it }
                   .graphicsLayer {
                       translationY = -skipButtonLift.value * SKIP_BUTTON_CONTROLS_LIFT.toPx()
                   }
   ```
10. Imports in `PlayerScreen.kt` (add any that are not already present):
    `androidx.compose.animation.core.snap` and `androidx.compose.ui.graphics.graphicsLayer`.
    Afterwards run `grep -n "animateDpAsState" PlayerScreen.kt`. If the only hit is the import
    line, delete that import. Plan 008 also removes `animateDpAsState` uses, so whichever plan
    runs last removes it.

## Boundaries

- Do NOT touch `SkipIntroVisibilityRules.kt`, `SubtitleLiftRules.kt` or `reportSubtitleClearance`
  itself.
- Do NOT change the countdown logic (`SkipIntroButton.kt:99-154`), focus handling or colours.
- Do NOT touch the post-play surface block (`PlayerScreen.kt:1048-1136`, owned by plan 008) or
  the controls-focus `LaunchedEffect` (`:594-650`, owned by plan 019), apart from the
  single-argument change in step 7.
- Do NOT add new dependencies or literal-millisecond `tween(...)` calls.
- If any excerpt does not match the code (drift since ee8dd84), STOP and report instead of
  improvising.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes with no
    baseline change.
  - `./gradlew :app:testFullDebugUnitTest --tests '*SkipIntroVisibilityRulesTest*' --tests '*SubtitleLiftRulesTest*'`
    passes.
- **Feel check** (TV or emulator, an episode with an intro marker, subtitles on):
  - Countdown: let the button sit with controls hidden. The bar drains smoothly from the start
    edge, and colours and rounded bottom corners look as before. Open the controls: the bar
    hides and the countdown pauses. Close them: it resumes from where it was.
  - Layout Inspector with recomposition counts on: during the countdown, `SkipIntroButton` and
    its `Card` content counts stay flat (before this change they rose every frame). Toggle
    controls several times: `PlayerScreen`'s count rises about once per toggle, not ~11 times
    (180 ms of frames).
  - Developer options → Animator duration scale 10x:
    - The button grows from its bottom-start corner (it should not drift towards the centre)
      and from 95% scale, not 80%.
    - The exit is visibly quicker than the entrance.
    - The lift above the controls is a smooth slide with no jump at the end.
  - Subtitles: with a subtitle line on screen, toggle the controls. The subtitle jumps once to
    a position clear of the lifted button, never under it and never stepping each frame.
    Compare the final subtitle height with controls shown before and after this change; it
    must be identical.
  - Hold D-pad Down/Up to toggle controls rapidly: the lift retargets from where it is, with
    no restart from the bottom.
  - Animator duration scale → "Animation off": the button fades only (no scale) and the lift
    snaps.
  - `adb shell dumpsys gfxinfo com.fetch.tv.debug reset`, play through an intro for 10 s,
    then `adb shell dumpsys gfxinfo com.fetch.tv.debug`. Janky frames should be no higher than
    before the change.
- **Done when**:
  - `grep -n "fillMaxWidth(progress.value)" SkipIntroButton.kt` returns nothing.
  - `grep -n "skipButtonBottomPadding\|initialScale = 0.8f" -r app/src/main/java` returns
    nothing.
  - The build and tests above are green.
