# 008 — Drive the post-play player shrink from one value read in layout and draw, not composition

- **Status**: DONE (build not verified locally: no Android SDK in the session). Executed with one correction: `placeable.place(` became `placeable.placeRelative(` so the docked inset mirrors in RTL, as the old `padding(end = ...)` did.
- **Commit**: ee8dd84
- **Severity**: HIGH
- **Category**: 5. Performance (with 2. Easing & duration, 6. Accessibility)
- **Estimated scope**: 2 files (`PlayerScreen.kt` ~70 lines replaced, `PostPlayRecommendationState.kt` 1 line)
- **Depends on**: 001 (uses `FetchMotion.moveTween()`)

All paths are relative to `app/src/main/java/com/fetch/tv/`.

## Problem

When the post-play recommendation appears, the live video surface shrinks into the top-end
corner. `ui/screens/player/PlayerScreen.kt:1048-1070` runs four separate animations, each
420 ms (`POST_PLAY_RECOMMENDATION_TRANSITION_MS = 420`,
`ui/screens/player/PostPlayRecommendationState.kt:95`) with the default curve:

```kotlin
        val postPlayRecommendationPlayerWidth by animateFloatAsState(
            targetValue = if (postPlayRecommendationState.isVisible) 0.32f else 1f,
            animationSpec = tween(durationMillis = POST_PLAY_RECOMMENDATION_TRANSITION_MS),
            label = "postPlayRecommendationPlayerWidth"
        )
        val postPlayRecommendationPlayerPadding by animateDpAsState(
            targetValue = if (postPlayRecommendationState.isVisible) FetchTheme.spacing.xxl else 0.dp,
            animationSpec = tween(durationMillis = POST_PLAY_RECOMMENDATION_TRANSITION_MS),
            label = "postPlayRecommendationPlayerPadding"
        )
        val postPlayRecommendationPlayerCornerRadius by animateDpAsState(
            targetValue = if (postPlayRecommendationState.isVisible) 12.dp else 0.dp,
            animationSpec = tween(durationMillis = POST_PLAY_RECOMMENDATION_TRANSITION_MS),
            label = "postPlayRecommendationPlayerCornerRadius"
        )
        val postPlayRecommendationPlayerBorderAlpha by animateFloatAsState(
            targetValue = if (postPlayRecommendationState.isVisible) 0.2f else 0f,
            animationSpec = tween(durationMillis = POST_PLAY_RECOMMENDATION_TRANSITION_MS),
            label = "postPlayRecommendationPlayerBorderAlpha"
        )
        val playerSurfaceShape = RoundedCornerShape(postPlayRecommendationPlayerCornerRadius)
        val playerSurfaceIsFullscreen = !postPlayRecommendationState.isVisible &&
            postPlayRecommendationPlayerWidth >= 0.999f
```

They are consumed in composition at `:1116-1136`:

```kotlin
        val playerSurfaceModifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = postPlayRecommendationPlayerPadding, top = postPlayRecommendationPlayerPadding)
            .fillMaxWidth(postPlayRecommendationPlayerWidth)
            .aspectRatio(16f / 9f)
            .clip(playerSurfaceShape)
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = postPlayRecommendationPlayerBorderAlpha)),
                playerSurfaceShape
            )
            .then(
                if (transparentLetterbox && playerSurfaceIsFullscreen) Modifier
                else Modifier.background(Color.Black)
            )
            .zIndex(
                if (postPlayRecommendationState.isVisible || postPlayRecommendationPlayerWidth < 0.999f) {
                    2.2f
                } else {
                    0f
                }
            )
```

The enclosing `Box(...)` blocks (`:695`, `:1047`) are inline, so these `by` delegates are read
in the `PlayerScreen` function's own recompose scope. For 420 ms the whole ~4,400-line
`PlayerScreen` recomposes every frame. The video surface (an `AndroidView` hosting a
`SurfaceView`) is also re-measured and re-laid out every frame, while the credits are still
decoding. None of it is gated on reduced motion. The duration is past the 300 ms UI budget, and
the default `FastOutSlowInEasing` is a weak ease-in-out for an on-screen move.

### Can the shrink be a pure `graphicsLayer` transform instead? No. The evidence:

- **Exo path**: `ExoPlayerSurface` builds `PlayerView(context)` programmatically
  (`PlayerScreen.kt:2136-2148`) with no `AttributeSet`. Media3 1.8.0's `PlayerView` then uses its
  default `surface_type`, which is `surface_view`. The repo's override layout
  `app/src/main/res/layout/exo_player_view.xml` sets no `surface_type`. Only the trailer layout
  (`res/layout/trailer_player_view.xml:8`) sets `app:surface_type="texture_view"`. Tunneled
  playback (`tunnelingEnabled`) also requires a `SurfaceView`.
  `core/player/TrailerVideoPolicy.kt:7-8` states it outright: "full-screen playback
  (SurfaceView)".
- **mpv path**: `MpvPlayerSurface` hosts `FetchMpvSurfaceView`
  (`ui/screens/player/FetchMpvSurfaceView.kt:18`). That class extends
  `is.xyz.mpv.BaseMPVView` (dependency `io.github.abdallahmehiz:mpv-android-lib:0.1.12`,
  `app/build.gradle.kts:453`) and implements `SurfaceHolder` callbacks (`surfaceCreated`,
  `:104`). It is a `SurfaceView` subclass.
- **minSdk is 24** (`app/build.gradle.kts:101`).
  - A `SurfaceView`'s buffer is composited by SurfaceFlinger/HWC, not drawn through the app's
    RenderNode. The platform only made it follow ancestor RenderNode transforms (scale and
    clip, in sync with the frame) reliably in later releases: API 29+, with further fixes in
    30–34.
  - On API 24–28 TV firmware, and with tunneled sideband video layers, a Compose ancestor
    `graphicsLayer { scaleX/scaleY }` can leave the video at full size, or a frame out of step,
    under a scaled hole.
  - This cannot be proven safe from code. The app does already scale the `SurfaceView` itself
    through `View.scaleX/scaleY` for aspect modes (`PlayerAspectScaleUtils.kt:166-174`), but
    that is the view's own transform, not a Compose ancestor layer.

So this plan **keeps a layout-based resize and cuts its cost**:

1. One animated fraction instead of four animations.
2. It is read only inside `Modifier.layout {}`, `graphicsLayer {}` and `drawWithContent {}`
   lambdas, so `PlayerScreen` stops recomposing per frame. Only the surface node re-measures.
3. It is shortened to `durations.move` (250 ms) with `easings.inOut`.
4. It snaps when `FetchTheme.fullMotion` is false.

A transform-based variant for API 29+ is a written follow-up at the end. Do not implement it
here.

## Target

- A single `State<Float>` called `postPlayShrink`:
  - `0f` = full screen, `1f` = docked thumbnail.
  - Target: `if (postPlayRecommendationState.isVisible) 1f else 0f`.
  - `animationSpec = if (FetchTheme.fullMotion) FetchMotion.moveTween() else snap()`.
    `moveTween()` is 250 ms with `CubicBezierEasing(0.77f, 0f, 0.175f, 1f)`.
- Everything else is derived from `t = postPlayShrink.value`, inside non-composition lambdas.
  Today's end states are reproduced exactly:

| Property | t = 0 | t = 1 | Read in |
|---|---|---|---|
| inset (end + top) | 0 | `FetchTheme.spacing.xxl` | `Modifier.layout` |
| width fraction of (maxWidth − inset) | 1f | 0.32f | `Modifier.layout` |
| zIndex | 0f (2.2f if `isVisible`) | 2.2f | `place(zIndex = …)` |
| corner radius | 0 | 12.dp | `graphicsLayer { shape }` |
| border alpha (1.dp white) | 0 | 0.2 | `drawWithContent` |
| black background | omitted if `transparentLetterbox && !isVisible && t < 0.001f` | drawn | `drawWithContent` |

- `POST_PLAY_RECOMMENDATION_TRANSITION_MS` must follow the new duration, because two delays
  wait for this transition:
  - `PostPlayRecommendationOverlay.kt:175` waits before focusing Play.
  - `PostPlayRecommendationController.kt:198` waits before clearing state after "return to
    player".

## Repo conventions to follow

- Animated values read only at placement: `ui/components/LightweightCard.kt:70-118`
  (`animateFloatAsState` kept as a `State`, read in `.layout { ... placeable.place(0, 0, zIndex = zIndex.value) }`).
- Reduced motion: `ui/screens/player/PlaybackDetailsBar.kt:95-105`. Here, use
  `FetchTheme.fullMotion` inline instead of a local `val fullMotion`, because plan 005 edits
  the same scope.
- Tokens: `FetchMotion.moveTween()`, `FetchMotion.tokens.durations.move`
  (`ui/theme/MotionFocusTokens.kt`, after plan 001). No literal-millisecond `tween(...)`
  (`ThemeLiteralGuardTest`; `PlayerScreen.kt` has no line in
  `app/src/test/resources/theme-literals-baseline.txt`, so its allowance is 0).
- No `RoundedCornerShape(<N>.dp)` literal (the same guard counts those). A shape built from a
  px `Float` is fine.

## Steps

`PlayerScreen.kt` is also edited by plans 005, 016 and 019. Find each excerpt by its text, not by
line number. If it is not there, stop and report.

1. In `ui/screens/player/PostPlayRecommendationState.kt:95`, replace
   ```kotlin
   internal const val POST_PLAY_RECOMMENDATION_TRANSITION_MS = 420
   ```
   with
   ```kotlin
   /** How long the player surface takes to dock or return; delays that wait for it use this. */
   internal val POST_PLAY_RECOMMENDATION_TRANSITION_MS: Int
       get() = FetchMotion.tokens.durations.move
   ```
   and add `import com.fetch.tv.ui.theme.FetchMotion` to that file. Both call sites use
   `POST_PLAY_RECOMMENDATION_TRANSITION_MS.toLong()`, which still compiles.
2. In `PlayerScreen.kt`, next to `private val SUBTITLE_CONTROLS_LIFT = 130.dp` (`:2311`), add:
   ```kotlin
   /** Width of the docked player while the post-play recommendation shows. */
   private const val POST_PLAY_PLAYER_WIDTH_FRACTION = 0.32f
   private val POST_PLAY_PLAYER_CORNER_RADIUS = 12.dp
   private val POST_PLAY_PLAYER_BORDER_WIDTH = 1.dp
   private const val POST_PLAY_PLAYER_BORDER_ALPHA = 0.2f
   ```
3. Replace the four animations and the two derived vals (`:1048-1070`, the first excerpt
   above, from `val postPlayRecommendationPlayerWidth` through
   `postPlayRecommendationPlayerWidth >= 0.999f`) with:
   ```kotlin
           // 0 = full screen, 1 = docked beside the recommendation. Read only while measuring,
           // placing and drawing the surface, so the shrink never recomposes PlayerScreen.
           val postPlayShrink = animateFloatAsState(
               targetValue = if (postPlayRecommendationState.isVisible) 1f else 0f,
               animationSpec = if (FetchTheme.fullMotion) FetchMotion.moveTween() else snap(),
               label = "postPlayShrink"
           )
           val postPlayVisible = postPlayRecommendationState.isVisible
           val postPlayInsetPx = with(LocalDensity.current) { FetchTheme.spacing.xxl.toPx() }
           val postPlayCornerRadiusPx = with(LocalDensity.current) { POST_PLAY_PLAYER_CORNER_RADIUS.toPx() }
           val postPlayBorderWidthPx = with(LocalDensity.current) { POST_PLAY_PLAYER_BORDER_WIDTH.toPx() }
   ```
4. Replace `playerSurfaceModifier` (`:1116-1136`, the second excerpt above) with:
   ```kotlin
           val playerSurfaceModifier = Modifier
               .align(Alignment.TopEnd)
               // Same geometry as padding(end, top) + fillMaxWidth(fraction), from one value.
               .layout { measurable, constraints ->
                   val t = postPlayShrink.value
                   val insetPx = (postPlayInsetPx * t).roundToInt()
                   val availableWidth = (constraints.maxWidth - insetPx).coerceAtLeast(0)
                   val widthFraction = 1f - (1f - POST_PLAY_PLAYER_WIDTH_FRACTION) * t
                   val width = (availableWidth * widthFraction).roundToInt()
                   val maxHeight = if (constraints.hasBoundedHeight) {
                       (constraints.maxHeight - insetPx).coerceAtLeast(0)
                   } else {
                       Constraints.Infinity
                   }
                   val placeable = measurable.measure(
                       Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = maxHeight)
                   )
                   layout(placeable.width + insetPx, placeable.height + insetPx) {
                       placeable.place(
                           x = 0,
                           y = insetPx,
                           zIndex = if (postPlayVisible || t >= 0.001f) 2.2f else 0f
                       )
                   }
               }
               .aspectRatio(16f / 9f)
               .graphicsLayer {
                   val t = postPlayShrink.value
                   shape = if (t > 0f) RoundedCornerShape(postPlayCornerRadiusPx * t) else RectangleShape
                   clip = true
               }
               .drawWithContent {
                   val t = postPlayShrink.value
                   val fullscreen = !postPlayVisible && t < 0.001f
                   if (!(transparentLetterbox && fullscreen)) drawRect(Color.Black)
                   drawContent()
                   val borderAlpha = POST_PLAY_PLAYER_BORDER_ALPHA * t
                   if (borderAlpha > 0f) {
                       val half = postPlayBorderWidthPx / 2f
                       val radius = (postPlayCornerRadiusPx * t - half).coerceAtLeast(0f)
                       drawRoundRect(
                           color = Color.White.copy(alpha = borderAlpha),
                           topLeft = Offset(half, half),
                           size = Size(size.width - postPlayBorderWidthPx, size.height - postPlayBorderWidthPx),
                           cornerRadius = CornerRadius(radius),
                           style = Stroke(width = postPlayBorderWidthPx)
                       )
                   }
               }
   ```
   Notes for the executor:
   - The black fill is drawn before `drawContent()`, which is what `background(Color.Black)`
     after `.border(...)` did. The stroke is drawn after it, which is what `.border` did.
   - The `.zIndex(...)` modifier is removed; the z-index is now set in `place(...)`.
5. Check that nothing else uses the removed names:
   `grep -n "postPlayRecommendationPlayerWidth\|postPlayRecommendationPlayerPadding\|postPlayRecommendationPlayerCornerRadius\|postPlayRecommendationPlayerBorderAlpha\|playerSurfaceShape\|playerSurfaceIsFullscreen" PlayerScreen.kt`
   must return nothing.
6. Imports in `PlayerScreen.kt`. Add any that are missing:
   - `androidx.compose.animation.core.snap`
   - `androidx.compose.ui.layout.layout`
   - `androidx.compose.ui.unit.Constraints`
   - `androidx.compose.ui.graphics.graphicsLayer`
   - `androidx.compose.ui.graphics.RectangleShape`
   - `androidx.compose.ui.draw.drawWithContent`
   - `androidx.compose.ui.graphics.drawscope.Stroke`
   - `androidx.compose.ui.geometry.CornerRadius`
   - `androidx.compose.ui.geometry.Offset`
   - `androidx.compose.ui.geometry.Size`
   - `kotlin.math.roundToInt`

   Then remove imports that no longer have any use. Grep each one:
   - `animateDpAsState`: plan 005 also removes uses, so whichever plan runs last deletes the
     import.
   - `androidx.compose.foundation.border`
   - `BorderStroke`
   - `fillMaxWidth`

   Keep an import if any other line still uses it.

## Boundaries

- Do NOT touch `PostPlayRecommendationOverlay.kt` or `PostPlayRecommendationController.kt`.
  They pick up the new duration through the constant in step 1.
- Do NOT change `ExoPlayerSurface`, `MpvPlayerSurface`, `FetchMpvSurfaceView`,
  `PlayerAspectScaleUtils.kt` or `exo_player_view.xml`. Do not switch the surface type.
- Do NOT change the content of `Box(modifier = playerSurfaceModifier) { ... }` (`:1153-1211`).
- Do NOT touch the Skip Intro block (plan 005) or the controls-focus `LaunchedEffect`
  (plan 019).
- Do NOT implement the API 29+ transform follow-up below.
- If any excerpt does not match (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes with no
    baseline change.
  - `./gradlew :app:testFullDebugUnitTest --tests '*PostPlayRecommendationStateTest*'` passes.
- **Feel check**. Run it on a real TV device on **both** engines:
  - **ExoPlayer (SurfaceView)**: default internal player. Run it with tunneling off, then with
    tunneling on if the device supports it.
  - **mpv**: set the internal player engine to MPV in settings.

  For each, play an episode or film to its end so the post-play recommendation appears, then
  use "return to player":
  - The video shrinks smoothly into the top-end corner and grows back with no black flash,
    no frame where the video is full size behind a small rounded frame, and no picture
    lagging the frame by a frame.
  - At rest the docked player looks identical to before: same width (32% of the width minus
    the inset), the same `xxl` inset from the top and end, 12 dp corners and a faint white
    border.
  - At Animator duration scale 10x, the move eases in and out as one motion. Corners, border
    and size all arrive together, with no part finishing early.
  - The Play button on the recommendation takes focus right after the shrink completes, not
    noticeably later.
  - Layout Inspector recomposition counts: during the shrink, `PlayerScreen` recomposes about
    once per state change (it used to recompose every frame for 420 ms).
  - `adb shell dumpsys gfxinfo com.fetch.tv.debug reset`, trigger the shrink, then dump.
    Compare janky frames and the 90th/95th percentile against a build without this change,
    on both engines.
  - Animator duration scale → "Animation off": the surface snaps straight to its docked and
    full-screen states.
  - With "transparent letterbox" on (Exo only), at full screen the letterbox stays
    transparent, as before.
- **Done when**:
  - The grep in step 5 is empty.
  - `grep -n "POST_PLAY_RECOMMENDATION_TRANSITION_MS = 420" -r app/src` is empty.
  - The build and tests are green.
  - Both engine feel checks pass.

## Follow-up (do not implement here)

On API 29+ the surface could stay laid out at full size and be shrunk with
`graphicsLayer { scaleX = scaleY = lerp(1f, 0.32f, t); transformOrigin = TransformOrigin(1f, 0f); translationX/Y = -inset }`.
That would avoid re-measuring the `SurfaceView` and avoid `surfaceChanged` on mpv every frame.
Gate it on `Build.VERSION.SDK_INT >= 29`. Ship it only after a device matrix check (Exo,
Exo-tunneled, mpv on at least one API 29–33 TV and one API 34+ TV) confirms the video layer
follows the scale and the rounded clip in sync.
