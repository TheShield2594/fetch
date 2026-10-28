# 007 — Detail hero ⇄ trailer: snap the layout, morph the logo in graphicsLayer

- **Status**: DONE (build not verified locally: no Android SDK in the session; shipped trailer logo scale 0.5, A/B not run)
- **Commit**: ee8dd84
- **Severity**: HIGH
- **Category**: 1. Performance + 2. Easing & duration + 6. Accessibility (reduced motion)
- **Estimated scope**: 2 files (`HeroSection.kt` ~90 lines, `MetaDetailsScreen.kt` 1 line)
- **Depends on**: 001 (uses `FetchMotion.enterTween()`, `exitTween()`, `moveTween()`)

## Problem

On the detail page the trailer autoplays after 7s idle on Play (`MetaDetailsViewModel.kt:182`,
`trailerDelayMs = 7000L`), and Back or any key ends it. Both directions happen tens of times a
day, at the same moment the trailer decoder starts or stops.

`app/src/main/java/com/fetch/tv/ui/screens/detail/HeroSection.kt:177-215` animates four layout
values over `durations.long` (600ms) and the inner column's size, all read in composition:

```kotlin
// HeroSection.kt:177-215 — current
val logoHeight by animateDpAsState(
    targetValue = if (isTrailerPlaying) 60.dp else 100.dp,
    animationSpec = tween(FetchMotion.tokens.durations.long),
    label = "logoHeight"
)
val logoBottomPadding by animateDpAsState(
    targetValue = if (isTrailerPlaying) FetchTheme.spacing.xl else FetchTheme.spacing.lg,
    animationSpec = tween(FetchMotion.tokens.durations.long),
    label = "logoPadding"
)
val logoMaxWidth by animateFloatAsState(
    targetValue = if (isTrailerPlaying) 0.25f else 0.4f,
    animationSpec = tween(FetchMotion.tokens.durations.long),
    label = "logoWidth"
)
val screenHeight = LocalConfiguration.current.screenHeightDp.dp
val heroHeight by animateDpAsState(
    targetValue = if (isTrailerPlaying) maxOf(540.dp, screenHeight) else 540.dp,
    animationSpec = tween(FetchMotion.tokens.durations.long),
    label = "heroHeight"
)
Column(
    modifier = Modifier
        .fillMaxWidth()
        .height(heroHeight),
    verticalArrangement = Arrangement.Bottom
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(FetchMotion.tokens.durations.long)
            )
            .padding(start = FetchTheme.spacing.xxxl, end = FetchTheme.spacing.xxxl, bottom = FetchTheme.spacing.lg),
        verticalArrangement = Arrangement.Bottom
    ) {
```

and the logo (lines 218-228) applies them as layout:

```kotlin
// HeroSection.kt:222-225 — current
modifier = Modifier
    .height(logoHeight)
    .fillMaxWidth(logoMaxWidth)
    .padding(bottom = logoBottomPadding),
```

The fades are slow too: title text (lines 231-234) and the details block with Play, ratings
and synopsis (lines 262-266) use `durations.overlay` (400ms) both ways; the "Press Back" hint
(lines 248-251) fades in over `durations.long` (600ms); the Up Next row (lines 406-410) uses
`overlay` (400ms).

What the user sees today when the trailer starts: the details fade for 400ms, then are removed,
and `animateContentSize` slides the logo ~200dp down over another 600ms while it shrinks. That
is ~1s of relayout of the whole hero every frame. The rows below are faded by
`fadeBelowHero` (`MetaDetailsScreen.kt:3302-3325`), which reads the hero's bottom from
`listState.layoutInfo` in draw, so they redraw every frame too, with
`belowHeroAlpha` at `MetaDetailsScreen.kt:1841-1845`:

```kotlin
// MetaDetailsScreen.kt:1841-1845 — current
val belowHeroAlpha = animateFloatAsState(
    targetValue = if (isTrailerPlaying) 0f else 1f,
    animationSpec = tween(FetchMotion.tokens.durations.overlay),
    label = "belowHeroFade"
)
```

Nothing checks `FetchTheme.fullMotion`.

### Why snapping the layout does not jump

- On a standard 960×540dp TV, `maxOf(540.dp, screenHeight)` is `540.dp`: the hero height does
  not change at all. It only grows on taller (lower-density) screens.
- The trailer itself is the full-screen `TrailerPlayer` in `BackdropLayer`
  (`MetaDetailsScreen.kt:3404-3422`), behind the list, so the hero's height never moves the video.
- The only things that would visibly jump are (1) the details while they are still fading, (2)
  the logo, and (3) on tall screens, the rows below the hero. The target handles each:
  1. The trailer layout is applied only **after** the details and Up Next have finished fading
     out (their `MutableTransitionState.currentState` turns false). Nothing visible is in the
     part of the layout that snaps.
  2. The logo keeps a fixed layout size and is morphed with `graphicsLayer` scale. A
     placement-phase glide draws it at its old position and moves it to the new one, so the
     snap is invisible.
  3. The rows below fade out with `exitTween` (150ms), the same time as the details, so they
     are invisible when the taller hero pushes them off screen. On the way back the hero
     returns to 540dp at once and the rows fade in where they will stay.

## Target

Enter (trailer starts), full motion:
- t=0: the details, title text and Up Next fade out with `FetchMotion.exitTween()` (150ms).
  The rows below fade out with `exitTween()`. Nothing moves.
- t≈150ms: the hero takes its trailer layout in one frame: height `maxOf(540.dp, screenHeight)`,
  logo bottom padding `spacing.xl`, "Press Back" hint present (fading in with `enterTween()`).
  The logo scales 1 → 0.5 around its bottom-start corner and glides from its old position to
  its new one, both with `FetchMotion.moveTween()` (250ms, inOut).

Exit (Back or a key ends the trailer), full motion:
- t=0: the hero returns to its detail layout (540dp, padding `spacing.lg`), the hint is removed
  at once (`ExitTransition.None`, so the logo moves only once), the details and Up Next fade
  in with `enterTween()`, the rows below fade in with `enterTween()`. The logo scales 0.5 → 1
  and glides up with `moveTween()`.

Reduced motion (`!FetchTheme.fullMotion`): the same fades; the logo scale and position snap.

Logo geometry: the layout slot is fixed at `padding(bottom) + height(84.dp)` ×
`fillMaxWidth(0.4f)`. That is exactly today's resting size (100dp height with 16dp inner
padding = 84dp image area). Scale `0.5f` with `TransformOrigin(0f, 1f)` (bottom-start;
`(1f, 1f)` in RTL, because the logo is aligned `CenterStart`). Today's trailer logo is a 36dp
image area × 0.25 width; 0.5 gives 42dp × 0.2 width. A uniform scale can't match both the old
height and width ratios (0.43 and 0.625); 0.5 sits between them. See the A/B feel check.

## Repo conventions to follow

- Deferred reads: read animated values only in `graphicsLayer {}` and `offset {}` lambdas
  (exemplar: `app/src/main/java/com/fetch/tv/ui/components/LightweightCard.kt:52-118`, a value
  read only at placement).
- Reduced motion: `val fullMotion = FetchTheme.fullMotion` and branch
  (`app/src/main/java/com/fetch/tv/ui/screens/player/PlaybackDetailsBar.kt:95-104`). Keep the
  fades in reduced motion.
- No literal `tween(<number>` in screens (ThemeLiteralGuardTest). Neither file has a baseline
  line today and none is added.

## Steps

1. **Imports in `HeroSection.kt`.**
   - Remove `import androidx.compose.animation.core.animateDpAsState`.
   - Keep `import androidx.compose.animation.animateContentSize` (still used by `PlayButton`
     at line ~515).
   - Add:
     ```kotlin
     import androidx.compose.animation.ExitTransition
     import androidx.compose.animation.core.Animatable
     import androidx.compose.animation.core.AnimationVector1D
     import androidx.compose.animation.core.MutableTransitionState
     import androidx.compose.animation.core.snap
     import androidx.compose.foundation.layout.offset
     import androidx.compose.runtime.SideEffect
     import androidx.compose.runtime.mutableFloatStateOf
     import androidx.compose.runtime.rememberCoroutineScope
     import androidx.compose.ui.geometry.Offset
     import androidx.compose.ui.graphics.TransformOrigin
     import androidx.compose.ui.graphics.graphicsLayer
     import androidx.compose.ui.layout.LayoutCoordinates
     import androidx.compose.ui.layout.onPlaced
     import androidx.compose.ui.platform.LocalLayoutDirection
     import androidx.compose.ui.unit.IntOffset
     import androidx.compose.ui.unit.LayoutDirection
     import kotlin.math.roundToInt
     import kotlinx.coroutines.launch
     ```
     (Skip any that already exist; `graphicsLayer` may not be imported yet.)

2. **Constant.** Next to `private const val MAX_VISIBLE_HERO_GENRES = 6` (line 99), add:
   ```kotlin
   /** How small the logo draws while a trailer plays, from its bottom-start corner. */
   private const val TRAILER_LOGO_SCALE = 0.5f
   ```

3. **State.** Replace lines 177-200 (from `// Animate logo properties for trailer mode` through
   the `heroHeight` declaration) with:
   ```kotlin
   val fullMotion = FetchTheme.fullMotion
   // The details fade out first, and only then does the hero take its trailer layout, so
   // nothing still visible jumps. Coming back, the layout returns at once and they fade in.
   val heroDetailsVisibility = remember { MutableTransitionState(!isTrailerPlaying) }
   val upNextVisibility = remember { MutableTransitionState(!isTrailerPlaying) }
   SideEffect {
       heroDetailsVisibility.targetState = !isTrailerPlaying
       upNextVisibility.targetState = !isTrailerPlaying
   }
   val trailerLayout = isTrailerPlaying &&
       !heroDetailsVisibility.currentState &&
       (upNext == null || !upNextVisibility.currentState)
   // During a trailer the hero fills the screen so the logo sits at the bottom
   // on screens taller than the hero (lower-density TV boxes).
   val screenHeight = LocalConfiguration.current.screenHeightDp.dp
   val heroHeight = if (trailerLayout) maxOf(540.dp, screenHeight) else 540.dp
   val logoScale = animateFloatAsState(
       targetValue = if (trailerLayout) TRAILER_LOGO_SCALE else 1f,
       animationSpec = if (fullMotion) FetchMotion.moveTween() else snap(),
       label = "logoScale"
   )
   val logoOrigin = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
       TransformOrigin(1f, 1f)
   } else {
       TransformOrigin(0f, 1f)
   }
   // The logo's layout top within the hero, and the glide that draws it from where it was.
   var heroCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
   var logoLayoutTop by remember { mutableFloatStateOf(Float.NaN) }
   var logoGlide by remember { mutableStateOf<Animatable<Float, AnimationVector1D>?>(null) }
   val logoGlideScope = rememberCoroutineScope()
   ```

4. **Outer and inner columns.** Change the outer `Column` modifier (lines 202-204) to:
   ```kotlin
   modifier = Modifier
       .fillMaxWidth()
       .height(heroHeight)
       .onPlaced { heroCoordinates = it },
   ```
   and delete the `.animateContentSize(animationSpec = tween(FetchMotion.tokens.durations.long))`
   call from the inner `Column` (lines 210-212), leaving
   `.fillMaxWidth().padding(start = ..., end = ..., bottom = FetchTheme.spacing.lg)`.

5. **Logo.** Replace the `AsyncImage` modifier (lines 222-225) with:
   ```kotlin
   modifier = Modifier
       // Positions are measured from the hero, so scrolling the page never moves the glide.
       .onPlaced { coordinates ->
           val hero = heroCoordinates ?: return@onPlaced
           logoLayoutTop = hero.localPositionOf(coordinates, Offset.Zero).y
       }
       .offset {
           val target = logoLayoutTop
           if (target.isNaN() || !fullMotion) {
               logoGlide = null
               return@offset IntOffset.Zero
           }
           val glide = logoGlide ?: Animatable(target).also { logoGlide = it }
           if (glide.targetValue != target) {
               logoGlideScope.launch { glide.animateTo(target, FetchMotion.moveTween()) }
           }
           // Drawn where the glide is, so a snapped layout never shows a jump.
           IntOffset(0, (glide.value - target).roundToInt())
       }
       .padding(bottom = if (trailerLayout) FetchTheme.spacing.xl else FetchTheme.spacing.lg)
       // 100dp slot minus the 16dp resting padding: today's resting image area.
       .height(84.dp)
       .fillMaxWidth(0.4f)
       .graphicsLayer {
           scaleX = logoScale.value
           scaleY = logoScale.value
           transformOrigin = logoOrigin
       },
   ```
   Keep `contentScale = ContentScale.Fit` and `alignment = Alignment.CenterStart`.

6. **Title text** (lines 231-235): change `enter`/`exit` to
   `enter = fadeIn(FetchMotion.enterTween())` and `exit = fadeOut(FetchMotion.exitTween())`.
   Keep `visible = !isTrailerPlaying`.

7. **"Press Back" hint** (lines 248-252): change to
   ```kotlin
   AnimatedVisibility(
       visible = trailerLayout && !hideLogoDuringTrailer,
       enter = fadeIn(FetchMotion.enterTween()),
       // Leaves at once, so the returning logo moves in one glide, not two.
       exit = ExitTransition.None
   ) {
   ```

8. **Details block** (lines 262-266): change to
   ```kotlin
   AnimatedVisibility(
       visibleState = heroDetailsVisibility,
       enter = fadeIn(FetchMotion.enterTween()),
       exit = fadeOut(FetchMotion.exitTween())
   ) {
   ```
   The content lambda is unchanged.

9. **Up Next** (lines 406-412): change to
   ```kotlin
   AnimatedVisibility(
       visibleState = upNextVisibility,
       enter = fadeIn(FetchMotion.enterTween()),
       exit = fadeOut(FetchMotion.exitTween())
   ) {
       upNext()
   }
   ```

10. **Rows below the hero.** In `app/src/main/java/com/fetch/tv/ui/screens/detail/MetaDetailsScreen.kt:1843`,
    change `animationSpec = tween(FetchMotion.tokens.durations.overlay),` to:
    ```kotlin
    // Out with the hero details, so they are gone before a taller hero pushes them down.
    animationSpec = if (isTrailerPlaying) FetchMotion.exitTween() else FetchMotion.enterTween(),
    ```
    Do not change anything else in `MetaDetailsScreen.kt`. `tween` is still imported and used
    there (line ~2786).

## Boundaries

- Edit only `HeroSection.kt` (the `HeroContentSection` function plus imports and one constant)
  and line 1843 of `MetaDetailsScreen.kt`.
- Do NOT touch `PlayButton`, `ActionIconButton` or anything below line ~413 of `HeroSection.kt`.
- Do NOT touch `fadeBelowHero`, `BackdropLayer` or the scroll functions in `MetaDetailsScreen.kt`;
  plan 014 edits `MetaDetailsScreen.kt:1503-1540`, `:1669`, `:2253` and `:3355-3378`. The two
  plans don't overlap; match by the excerpts, not line numbers, if 014 lands first.
- Do NOT change `MetaDetailsViewModel` trailer timing or the `isTrailerPlaying`/`hideLogoDuringTrailer` logic.
- If the code doesn't match the excerpts (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes.
  - `./gradlew :app:testFullDebugUnitTest --tests '*MetaDetailsSessionStateTest*'` passes
    (the only unit test in the detail package touching MetaDetailsScreen state; it doesn't
    cover this composable, so the feel check is what matters).
  - `grep -n "animateDpAsState\|animateContentSize" app/src/main/java/com/fetch/tv/ui/screens/detail/HeroSection.kt`
    shows only the `PlayButton` use (~line 515).
- **Feel check** (TV or emulator, a title with a trailer, trailer autoplay on):
  - Animator duration scale 10x. Rest on Play for 7s. The details fade out with nothing
    moving; then, in one motion, the logo shrinks toward its bottom-left corner and glides to
    the bottom while "Press Back to …" fades in. The logo never shows a single frame at its
    new position before gliding (if it does, STOP and report: the onPlaced → offset ordering
    assumption failed).
  - **Back during trailer**: the details and Play fade in where they belong, the logo grows
    and glides up in one motion, no second hop. Play has focus (the existing
    `restorePlayFocusAfterTrailerBackToken` path).
  - **Key press ends trailer** (D-pad Left/Right/Down while the trailer plays): the same as
    Back, with focus where the existing code puts it.
  - Press Back immediately after the trailer starts (during the 150ms fade): the details come
    back without a hop and the logo never shrinks.
  - Scroll the detail page up and down: the logo moves rigidly with the page (no lag).
  - On a 1280×720 mdpi emulator (screenHeight > 540dp): the rows below the hero fade out
    fully before they are pushed down; on exit they fade in at the bottom without sliding.
  - RTL locale: the logo shrinks toward its bottom-right corner.
  - **A/B the scale**: compare `TRAILER_LOGO_SCALE` 0.5 against 0.43 with a wide logo and a
    tall (stacked) logo. Keep 0.5 unless the trailer logo is clearly too big over the video.
  - Animator duration scale "Animation off": the details and hint still fade; the logo
    changes size and position without motion.
  - Performance: Layout Inspector shows `HeroContentSection` recomposing a handful of times
    per change, not every frame; Profile GPU rendering shows no tall bars when the trailer
    starts.
- **Done when**: the build is green, the hero never relayouts per frame for the trailer
  change, and the four feel checks above (start, Back, key, scroll) pass.
