# 011 — Make the subtitle rail fade an interruptible ease-out with an animated hide

- **Status**: TODO
- **Commit**: ee8dd84
- **Severity**: MEDIUM
- **Category**: 2. Easing & duration (with 4. Interruptibility, 5. Performance)
- **Estimated scope**: 1 file (`SubtitleSelectionOverlay.kt`, ~25 lines)
- **Depends on**: 001 (uses `FetchMotion.tokens.easings.out`)

All paths are relative to `app/src/main/java/com/fetch/tv/`.

## Problem

In the subtitle selection overlay, the option rail and the style rail fade in through
`RailFadeIn` (`ui/screens/player/SubtitleSelectionOverlay.kt:599-622`):

```kotlin
@Composable
private fun RailFadeIn(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    if (!visible) return

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.snapTo(0f)
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = RailFadeDurationMs,
                easing = FastOutLinearInEasing
            )
        )
    }

    Box(modifier = Modifier.graphicsLayer(alpha = alpha.value)) {
        content()
    }
}
```

with `private const val RailFadeDurationMs = 120` at `:81`.

This has four problems:

1. **Ease-in on an entrance.** `FastOutLinearInEasing` is the only ease-in in the app. The rail
   starts slowest exactly when the user is looking for it.
2. **No interruption.** `if (!visible) return` drops the content instantly. The next show
   starts again from alpha 0, even if the hide was a frame ago.
3. **The hide is not animated.** The rail vanishes in one frame.
4. **Composition read.** The non-lambda `graphicsLayer(alpha = alpha.value)` reads the value in
   composition, so the rail's wrapper recomposes on every frame of the fade.

The call sites are `:522` (`RailFadeIn(visible = optionRailVisible)`) and `:575`
(`RailFadeIn(visible = styleRailVisible)`). Both sit inside
`Row(horizontalArrangement = Arrangement.spacedBy(14.dp))` (`:466`). The flags are defined at
`:238-239`:

```kotlin
    val optionRailVisible = selectedLanguageKey != SubtitleOffLanguageKey
    val styleRailVisible = optionRailVisible && (revealStyleRail || selectedOptionId != null)
```

They flip when the user picks a language or "Off" in the language rail (`:490`, `:506`),
selects or long-press-disables an option (`:552`, `:561`, `:568`), or moves right into the
style rail (`moveFocusToStyleRail`, `:388`). A user browsing languages can toggle them several
times a second.

## Target

Replace the body of `RailFadeIn` with an `AnimatedVisibility`. Its transition retargets from
the current alpha when `visible` flips mid-fade, and it animates the hide as well:

- enter: `fadeIn(tween(FetchMotion.tokens.durations.quick, easing = FetchMotion.tokens.easings.out))`.
  That is 125 ms (the existing `quick` token, replacing the off-token 120) with
  `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`.
- exit: `fadeOut(tween(FetchMotion.tokens.durations.quick, easing = FetchMotion.tokens.easings.out))`.
  It matches the enter rather than using the 150 ms `exitTween()`, because an exit should never
  be longer than its entrance.
- Opacity only, so no reduced-motion branch is needed. Reduced motion keeps short opacity
  fades (see `ui/theme/Theme.kt:182-189`).
- While the rail is fading out it is still composed. Stop D-pad focus from entering it with
  `focusProperties { onEnter = { if (!visible) cancelFocusChange() } }` on a `focusGroup()`
  wrapper.
- Delete `RailFadeDurationMs`. The value now comes from the token.

Why the rails keep their layout slot during the 125 ms exit: they are the trailing items of
the `Row`, so nothing after them shifts. When the option rail hides, the style rail (to its
right) hides in the same composition (`styleRailVisible` requires `optionRailVisible`).

## Repo conventions to follow

- Tokens: `FetchMotion.tokens.durations.quick` and `FetchMotion.tokens.easings.out`
  (`ui/theme/MotionFocusTokens.kt`; `out` is added by plan 001).
- `ThemeLiteralGuardTest` counts `tween(<digits>`, and `tween(FetchMotion...` does not match.
  This file has no line in `app/src/test/resources/theme-literals-baseline.txt`, and nothing
  needs changing there.
- Blocking focus entry into a container that is visually going away:
  `ui/navigation/DetailChildHost.kt:115-127`
  (`.focusGroup().focusProperties { onEnter = { if (overlayVisible) cancelFocusChange() } }`).
  Here, put `focusProperties` **before** `focusGroup()` so the properties apply to that group.

## Steps

1. In `SubtitleSelectionOverlay.kt`, delete line `:81`:
   ```kotlin
   private const val RailFadeDurationMs = 120
   ```
2. Replace the whole `RailFadeIn` function (`:599-622`, excerpt above) with:
   ```kotlin
   @Composable
   private fun RailFadeIn(
       visible: Boolean,
       content: @Composable () -> Unit
   ) {
       // AnimatedVisibility retargets from the current alpha when a rail is toggled mid-fade.
       AnimatedVisibility(
           visible = visible,
           enter = fadeIn(tween(FetchMotion.tokens.durations.quick, easing = FetchMotion.tokens.easings.out)),
           exit = fadeOut(tween(FetchMotion.tokens.durations.quick, easing = FetchMotion.tokens.easings.out)),
           modifier = Modifier
               // A rail that is fading out must not take D-pad focus.
               .focusProperties { onEnter = { if (!visible) cancelFocusChange() } }
               .focusGroup()
       ) {
           content()
       }
   }
   ```
   `RailFadeIn` is a plain composable (not a `RowScope` extension), so `AnimatedVisibility`
   resolves to the top-level overload with exactly these transitions, not `RowScope`'s default
   expand.
3. Imports:
   - Remove `androidx.compose.animation.core.Animatable`,
     `androidx.compose.animation.core.FastOutLinearInEasing` and
     `androidx.compose.ui.graphics.graphicsLayer`. Before deleting each, grep the file to
     confirm `RailFadeIn` was its only user.
   - Keep `androidx.compose.animation.core.tween`.
   - Add:
     ```kotlin
     import com.fetch.tv.ui.theme.FetchMotion
     import androidx.compose.animation.AnimatedVisibility
     import androidx.compose.animation.fadeIn
     import androidx.compose.animation.fadeOut
     import androidx.compose.foundation.focusGroup
     ```
   - `androidx.compose.ui.focus.focusProperties` is already imported (`:47`).
4. Leave both call sites (`:522`, `:575`) unchanged.

## Boundaries

- Do NOT change how `optionRailVisible`, `styleRailVisible` or `revealStyleRail` are computed,
  or any focus-request logic (`requestOptionFocus`, `requestStyleFocus`, the `LaunchedEffect`s
  around `:400-455`).
- Do NOT touch other files.
- Do NOT add a reduced-motion branch (opacity-only motion is kept under reduced motion).
- If the code does not match the excerpts (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes.
  - `./gradlew :app:testFullDebugUnitTest --tests '*SubtitleRoutingTest*'` passes. It is the
    nearest existing coverage of the subtitle overlay; nothing tests `RailFadeIn` directly.
- **Feel check** (TV or emulator, a title with several subtitle languages):
  - Open the subtitle overlay. Move through languages with D-pad Up/Down and press OK on
    several in a row, including "Off". The option rail appears immediately and settles fast,
    with no slow start. Choosing "Off" fades the rails out instead of cutting them.
  - Animator duration scale 10x: pick a language, then "Off", then a language again while the
    rail is still fading. The rail's opacity reverses from where it is; it never jumps back to
    fully transparent.
  - While a rail is fading out (10x scale makes this easy), press D-pad Right from the language
    rail. Focus must not land in the fading rail.
  - Long-press the selected option to disable subtitles. The style rail fades out, and focus
    stays on the option rail.
  - Animator duration scale → "Animation off": rails appear and disappear instantly, and
    everything stays navigable.
- **Done when**:
  - `grep -n "FastOutLinearInEasing\|RailFadeDurationMs" -r app/src/main/java` is empty.
  - The build and tests are green.
