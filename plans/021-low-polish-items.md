# 021 — Low-severity motion polish: party, ambient exit, cast page, PIN cursor, addon error

- **Status**: DONE (build not verified locally: no Android SDK in the session; all six sub-sections applied)
- **Commit**: ee8dd84
- **Severity**: LOW
- **Category**: Mixed (scale-from-zero, bouncy springs on typed input, exit speed, dead motion, untokened transitions, composition reads, exit content)
- **Estimated scope**: 7 files, six independent sub-sections (a-f), about 10-40 lines each
- **Depends on**: 001 (`enterTween`, `exitTween`, `moveTween`). Run after **016**, which also edits `ProfileSelectionScreen.kt` (the PIN shake) and gates other ambient files. The two plans touch different lines.

Each sub-section stands alone. Execute, verify and commit them one at a time. If one
sub-section's excerpt doesn't match, skip only that one and report it.

All paths are under `app/src/main/java/com/fetch/tv/`.

Shared conventions for every sub-section:

- Motion specs come from `FetchMotion` (`ui/theme/MotionFocusTokens.kt`).
  `enterTween()` is 200ms ease-out `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`;
  `exitTween()` is 150ms with the same curve; `moveTween()` is 250ms ease-in-out
  `CubicBezierEasing(0.77f, 0f, 0.175f, 1f)`.
- Reduced motion is `FetchTheme.fullMotion`. When false, drop scale and movement and keep
  short fades. Exemplar: `ui/screens/player/PlaybackDetailsBar.kt:95-104`.
- Read animated values only inside `graphicsLayer {}` / draw lambdas. Exemplar:
  `ui/components/CollectionRowSection.kt:331-341`.
- `ThemeLiteralGuardTest` baseline (`app/src/test/resources/theme-literals-baseline.txt`)
  must stay unchanged. No sub-section adds or removes a `tween(<digits>` literal. The
  `PartyUi.kt|DURATION|1` line is the `tween(1100)` in (e), which moves but stays. The
  `ProfileSelectionScreen.kt|DURATION|1` line is the PIN shake's `tween(42)` at :2655, **not**
  the cursor blink in (d).

---

## (a) Party avatars and code characters: no scale from 0, no bouncy typing

### Problem

```kotlin
// ui/screens/party/PartyUi.kt:207-232 — current
@Composable
private fun StackSlot(
    index: Int,
    slots: Int,
    step: Dp,
    ringColor: Color,
    animateIn: Boolean,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(if (animateIn) 0f else 1f) }
    if (animateIn) {
        LaunchedEffect(Unit) {
            delay(index * 90L)
            scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
        }
    }
    Box(
        modifier = Modifier
            .offset(x = step * index)
            .zIndex((slots - index).toFloat())
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .border(FetchTheme.strokes.medium, ringColor, CircleShape),
    ) {
        content()
    }
}
```

- The joined-party avatars (`animateIn = true` only at `PartyScreen.kt:463`) grow from
  **scale 0**. Nothing in the real world appears from nothing.
- The bouncy spring is fine for this rare, celebratory moment. Keep it.
- The scale runs even under reduced motion.

```kotlin
// ui/screens/party/PartyCode.kt:119-126 — current (inside CodeSlot)
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    (fadeIn(tween(FetchMotion.tokens.durations.quick)) + scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), initialScale = 0.5f)) togetherWith
                        fadeOut(tween(FetchMotion.tokens.durations.quick))
                },
                label = "codeSlotChar",
            ) { value ->
```

Each D-pad-typed code character pops in from 0.5 with a bounce. That is repeated input
feedback. It should be a quick, non-bouncy settle from 0.9.

### Target

- Avatars use one progress value `p` in [0, ~1.16 overshoot]:
  - scale = `lerp(0.9f, 1f, p)`, only when `fullMotion`;
  - alpha = `p.coerceIn(0f, 1f)`;
  - full motion keeps `spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)`, so
    the overshoot is about 1.6% above 1.0;
  - reduced motion uses `FetchMotion.enterTween()` with no scale.
- Code characters:
  - `fadeIn(FetchMotion.enterTween()) + scaleIn(FetchMotion.enterTween(), initialScale = 0.9f)`
    in, and `fadeOut(FetchMotion.exitTween())` out;
  - no scale when `!fullMotion`.

### Steps

1. In `ui/screens/party/PartyUi.kt`, replace the body of `StackSlot` from
   `    val scale = remember { Animatable(if (animateIn) 0f else 1f) }` through the
   `.graphicsLayer { ... }` block with:
   ```kotlin
       val fullMotion = FetchTheme.fullMotion
       // 0 → 1 drives both opacity and a 0.9 → 1 scale; the bouncy spring overshoots 1 slightly.
       val progress = remember { Animatable(if (animateIn) 0f else 1f) }
       if (animateIn) {
           LaunchedEffect(Unit) {
               delay(index * 90L)
               progress.animateTo(
                   1f,
                   if (fullMotion) spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow) else FetchMotion.enterTween<Float>()
               )
           }
       }
       Box(
           modifier = Modifier
               .offset(x = step * index)
               .zIndex((slots - index).toFloat())
               .graphicsLayer {
                   val p = progress.value
                   val s = if (fullMotion) lerp(0.9f, 1f, p) else 1f
                   scaleX = s
                   scaleY = s
                   alpha = p.coerceIn(0f, 1f)
               }
   ```
   Keep `.border(...)` and the rest unchanged. Add `import androidx.compose.ui.util.lerp`.
2. In `ui/screens/party/PartyCode.kt` `CodeSlot`, insert `    val fullMotion = FetchTheme.fullMotion`
   as the first line of the function body (before `val lineColor by ...`, :99). Replace
   the `transitionSpec` body at :122-123 with:
   ```kotlin
                       val enter = if (fullMotion) {
                           fadeIn(FetchMotion.enterTween()) + scaleIn(FetchMotion.enterTween(), initialScale = 0.9f)
                       } else {
                           fadeIn(FetchMotion.enterTween())
                       }
                       enter togetherWith fadeOut(FetchMotion.exitTween())
   ```
   Then remove the now-unused imports `androidx.compose.animation.core.Spring` and
   `androidx.compose.animation.core.spring` from PartyCode.kt.
   - Check with `grep -n "Spring\|spring(" PartyCode.kt` first. It must show nothing else.
   - Keep `tween`, which is still used at :105 and :108.

### Verification

- Join a party. The avatar stack fades up from 0.9 with a slight bounce, one by one (90ms
  stagger).
  - At 5x, no avatar ever starts as a dot.
  - With "Animation off", the avatars appear without scaling.
- On the party join screen, type a code with the D-pad. Each character settles in about
  200ms with no bounce, and holding a key on the keypad feels crisp.

---

## (b) Ambient mode leaves quickly when woken

### Problem

```kotlin
// ui/screens/ambient/AmbientModeHost.kt:59-63 — current
    AnimatedVisibility(
        visible = isShowing,
        enter = fadeIn(tween(FetchMotion.tokens.durations.long)),
        exit = fadeOut(tween(FetchMotion.tokens.durations.slow))
    ) {
```

When the user presses a key to wake the TV from ambient mode, the slideshow takes 450ms to
get out of the way. The user's intent is immediate. The 600ms enter is fine for an idle
screensaver.

### Target and steps

1. Replace `        exit = fadeOut(tween(FetchMotion.tokens.durations.slow))` with
   `        exit = fadeOut(FetchMotion.exitTween())`. Leave the `enter` line and the
   `tween` import, which is still used by `enter`.

### Verification

- Let ambient mode start (shorten the timeout in Settings), then press any D-pad key. The
  app is back in about 150ms. The fade-in on start is unchanged.

---

## (c) Cast page: remove dead motion; make the filmography toggle symmetric and tokenized

### Problem

```kotlin
// ui/screens/cast/CastDetailScreen.kt:252-279 — current
        // Main content
        AnimatedVisibility(
            visible = true,
            enter = fadeIn()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeroSection(
                    ...
                )

                AnimatedVisibility(
                    visible = allCredits.isNotEmpty() && !isBiographyExpanded,
                    enter = fadeIn() +
                        expandVertically(expandFrom = Alignment.Top) +
                        slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() +
                        shrinkVertically(shrinkTowards = Alignment.Top) +
                        slideOutVertically(targetOffsetY = { it })
                ) {
```

1. `AnimatedVisibility(visible = true, enter = fadeIn())` never animates. Its visibility
   starts `true`, and `AnimatedVisibility` plays no enter on first composition. It only
   adds a layer of wrapping.
2. The filmography toggle (when the biography expands or collapses) uses default springs,
   enters from `it / 2` but leaves to `it`, and slides even under reduced motion.
   - The expand/shrink is a relayout per frame, but it is justified here. The hero takes
     `Modifier.weight(1f)` when the biography expands, so snapping the row's height would
     make the hero jump.
   - Keep it, tokenized with `moveTween()`.

### Target

- No outer wrapper.
- Filmography:
  - enter = `fadeIn(enterTween()) + expandVertically(moveTween(), expandFrom = Top) + slideInVertically(enterTween()) { it / 4 }`
  - exit = `fadeOut(exitTween()) + shrinkVertically(moveTween(), shrinkTowards = Top) + slideOutVertically(exitTween()) { it / 4 }`
  - The offset is the same in both directions.
  - When `!fullMotion`, drop the slides and keep the fade and expand/shrink. The size
    change is layout, not decorative movement.

### Steps

1. Delete the wrapper lines `        AnimatedVisibility(`, `            visible = true,`,
   `            enter = fadeIn()` and `        ) {` (:253-256), plus the matching closing
   `        }` of that wrapper. That closing brace is the one directly after the `Column`'s
   closing brace. Find it by matching braces, not by line number. Then re-indent the
   `Column(...)` block one level left.
   - `HeroSection`'s `Modifier.weight(1f)` and the inner `AnimatedVisibility` resolve
     against the `Column`'s `ColumnScope` as before.
   - Confirm that `grep -n "animateEnterExit\|this@AnimatedVisibility" CastDetailScreen.kt`
     is empty, which was true at ee8dd84.
2. Immediately before the remaining filmography `AnimatedVisibility(`, insert
   `                val fullMotion = FetchTheme.fullMotion`. Replace its `enter`/`exit`
   with:
   ```kotlin
                       enter = fadeIn(FetchMotion.enterTween()) +
                           expandVertically(FetchMotion.moveTween(), expandFrom = Alignment.Top) +
                           (if (fullMotion) slideInVertically(FetchMotion.enterTween()) { it / 4 } else EnterTransition.None),
                       exit = fadeOut(FetchMotion.exitTween()) +
                           shrinkVertically(FetchMotion.moveTween(), shrinkTowards = Alignment.Top) +
                           (if (fullMotion) slideOutVertically(FetchMotion.exitTween()) { it / 4 } else ExitTransition.None)
   ```
3. Add the imports `com.fetch.tv.ui.theme.FetchMotion`,
   `androidx.compose.animation.EnterTransition` and
   `androidx.compose.animation.ExitTransition`. `fadeIn` and `AnimatedVisibility` are still
   used.
4. This file is also edited by plan 009 (the skeleton at :658). The regions don't overlap.

### Verification

- Open a person's page. The content appears without any change from before.
- Toggle the biography with select on the portrait.
  - The filmography row collapses and returns with the same short offset in both
    directions, and the hero grows and shrinks smoothly.
  - At 5x, the size change (250ms) runs slightly longer than the fade and slide (200ms in,
    150ms out), and nothing overshoots.
- A/B the offset `it / 4` against `it / 2`, once each, at 1x. Keep `it / 4` unless it reads
  as no movement at all. Record which.
- With "Animation off", the row appears and disappears in place.

---

## (d) PIN cursor blink: stop recomposing the whole PIN overlay every frame

### Problem

```kotlin
// ui/screens/profile/ProfileSelectionScreen.kt:2583-2594 — current (top of ProfilePinOverlay)
    val cursorAlpha = if (FetchTheme.fullMotion) {
        rememberInfiniteTransition(label = "pinCursor").animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(FetchMotion.tokens.durations.slow),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pinCursorAlpha"
        ).value
    } else {
        1f
    }
```

- `.value` is read at the top of `ProfilePinOverlay`, so the whole PIN overlay (header,
  boxes, support text and buttons) recomposes on every frame while the overlay is open.
- The value is passed down as `cursorAlpha = cursorAlpha` (:2834) into `ProfilePinBoxes`
  (param :2922). It is only used at :3015:
  `.background(Color.White.copy(alpha = cursorAlpha))`.

### Target

- The blink clock lives in a new `PinCursor` composable inside the active box, and is
  read in `graphicsLayer { alpha }`.
- The blink now starts **bright**: `initialValue = 1f`, `targetValue = 0.25f`, with
  `Reverse`. The clock restarts when the cursor moves to the next box after each digit, so
  the caret appears solid right after typing. That is standard caret behaviour. Before,
  it would start dim.

### Steps

1. Delete the `val cursorAlpha = ...` block (:2583-2594).
   - Plan 016 inserts `val fullMotion = FetchTheme.fullMotion` right after the
     `shakeOffset` line. Leave that line in place.
2. Delete the argument line `                cursorAlpha = cursorAlpha,` (:2834) and the
   parameter `    cursorAlpha: Float,` (:2922).
3. Replace the active-cursor `Box` in `ProfilePinBoxes` (:3011-3016):
   ```kotlin
                       isActive -> {
                           Box(
                               modifier = Modifier
                                   .width(FetchTheme.spacing.xxs)
                                   .height(activeCursorHeight)
                                   .background(Color.White.copy(alpha = cursorAlpha))
                           )
                       }
   ```
   with
   ```kotlin
                       isActive -> PinCursor(height = activeCursorHeight)
   ```
4. Add after `ProfilePinBoxes`:
   ```kotlin
   /** The caret in the active PIN box. Its blink lives here and is read in the layer, so only the caret redraws. */
   @Composable
   private fun PinCursor(height: Dp) {
       val blink = if (FetchTheme.fullMotion) {
           rememberInfiniteTransition(label = "pinCursor").animateFloat(
               initialValue = 1f,
               targetValue = 0.25f,
               animationSpec = infiniteRepeatable(
                   animation = tween(FetchMotion.tokens.durations.slow),
                   repeatMode = RepeatMode.Reverse
               ),
               label = "pinCursorAlpha"
           )
       } else {
           null
       }
       Box(
           modifier = Modifier
               .width(FetchTheme.spacing.xxs)
               .height(height)
               .graphicsLayer { alpha = blink?.value ?: 1f }
               .background(Color.White)
       )
   }
   ```
   All the imports already exist in the file: `Dp` :96, `graphicsLayer` :81,
   `rememberInfiniteTransition`, `animateFloat`, `infiniteRepeatable`, `RepeatMode` and
   `tween`.

### Verification

- Open a locked profile. The caret blinks in the first box.
  - Type digits. The caret moves to the next box and shows solid first, then blinks.
  - With "Animation off", the caret is solid.
- In Layout Inspector with recomposition counts on and the PIN overlay idle,
  `ProfilePinOverlay` and `ProfilePinBoxes` counts stay flat. Before, they climbed at about
  60 per second.

---

## (e) Party pulses: read in the layer, not in composition

### Problem

All three read an infinite transition's `.value` in composition, so each recomposes its
composable every frame.

- `ui/screens/party/PartyUi.kt:121-131`, the `PresenceDot` Busy halo. It feeds
  `pulse` into `graphicsLayer { scaleX = 1f + pulse; ... }`, but `pulse` is a `Float` read
  in composition.
- `ui/screens/party/PartyUi.kt:291-306`, `PulsingDot`, with `.alpha(alpha)`.
- `ui/screens/party/PartyCode.kt:150-166`, `Caret`, with `.alpha(alpha)`.

### Steps

1. **PresenceDot (`PartyUi.kt:123-138`).** Replace
   ```kotlin
               val pulse = if (FetchTheme.fullMotion) {
                   rememberInfiniteTransition(label = "presence").animateFloat(
                       initialValue = 0f,
                       targetValue = 1f,
                       animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
                       label = "presencePulse",
                   ).value
               } else {
                   0.35f
               }
               Box(
                   modifier = Modifier
                       .fillMaxSize()
                       .graphicsLayer {
                           scaleX = 1f + pulse
                           scaleY = 1f + pulse
                           alpha = 1f - pulse
                       }
   ```
   with
   ```kotlin
               val pulseState = if (FetchTheme.fullMotion) {
                   rememberInfiniteTransition(label = "presence").animateFloat(
                       initialValue = 0f,
                       targetValue = 1f,
                       animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
                       label = "presencePulse",
                   )
               } else {
                   null
               }
               Box(
                   modifier = Modifier
                       .fillMaxSize()
                       .graphicsLayer {
                           val pulse = pulseState?.value ?: 0.35f
                           scaleX = 1f + pulse
                           scaleY = 1f + pulse
                           alpha = 1f - pulse
                       }
   ```
   Keep `tween(1100)` exactly. It is the baselined literal.
2. **PulsingDot (`PartyUi.kt:292-306`).**
   - Change `val alpha = if (FetchTheme.fullMotion) {` to `val alphaState = if (FetchTheme.fullMotion) {`.
   - Remove the trailing `.value` after the `animateFloat(...)` call.
   - Change the `else` branch `1f` to `null`.
   - Replace `            .alpha(alpha)` with `            .graphicsLayer { alpha = alphaState?.value ?: 1f }`.
   - Then remove the import `androidx.compose.ui.draw.alpha` (:30) if
     `grep -n "\.alpha(" PartyUi.kt` is now empty. `graphicsLayer` is already imported
     (:36).
3. **Caret (`PartyCode.kt:150-166`).** Apply the same transformation:
   - `val alphaState = ...`, drop the `.value`, and use `null` in the `else` branch.
   - Replace `.alpha(alpha)` with `.graphicsLayer { alpha = alphaState?.value ?: 1f }`.
   - Add `import androidx.compose.ui.graphics.graphicsLayer`.
   - Remove `import androidx.compose.ui.draw.alpha` if no `.alpha(` remains.

### Verification

- In a party, a Busy member's halo pulses outward as before, and the "live" dot fades as
  before.
- On the code entry screen, the caret blinks as before.
- With "Animation off", the halo is held partway out, and the dot and caret are solid.
- In Layout Inspector, `PresenceDot`, `PulsingDot` and `Caret` stop accumulating
  recompositions while their animations run.

---

## (f) Essential addon setup: keep the error text while it collapses

### Problem

```kotlin
// ui/screens/addon/EssentialAddonSetupScreen.kt:165-171 — current
                        AnimatedVisibility(visible = uiState.error != null) {
                            Text(
                                text = uiState.error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = FetchTheme.colors.Error
                            )
                        }
```

- When the error clears, `uiState.error` is already `null` during the exit, so the text
  goes blank first and then the empty line collapses.
- The specs are the `ColumnScope` defaults: `fadeIn() + expandVertically()` with springs.

### Target

- Remember the last non-null error and show it for the whole exit.
- Tokenized: enter `fadeIn(enterTween()) + expandVertically(moveTween())`, exit
  `fadeOut(exitTween()) + shrinkVertically(moveTween())`.
- Expand/shrink stays because the button column below has to make room, and this is a
  rare event.

### Steps

1. Replace the excerpt above with:
   ```kotlin
                           // Keep the last message during the exit, so the text doesn't blank before it collapses.
                           var lastError by remember { mutableStateOf(uiState.error) }
                           if (uiState.error != null && uiState.error != lastError) lastError = uiState.error
                           AnimatedVisibility(
                               visible = uiState.error != null,
                               enter = fadeIn(FetchMotion.enterTween()) + expandVertically(FetchMotion.moveTween()),
                               exit = fadeOut(FetchMotion.exitTween()) + shrinkVertically(FetchMotion.moveTween())
                           ) {
                               Text(
                                   text = lastError.orEmpty(),
                                   style = MaterialTheme.typography.bodySmall,
                                   color = FetchTheme.colors.Error
                               )
                           }
   ```
   The state is written before it is read in this composition, which is safe and
   idempotent.
2. Add these imports:
   - `androidx.compose.runtime.remember`
   - `androidx.compose.runtime.mutableStateOf`
   - `androidx.compose.runtime.setValue`
   - `androidx.compose.animation.fadeIn`
   - `androidx.compose.animation.fadeOut`
   - `androidx.compose.animation.expandVertically`
   - `androidx.compose.animation.shrinkVertically`
   - `com.fetch.tv.ui.theme.FetchMotion`

   `getValue` is already imported (:25).

### Verification

- On first-run essential addon setup, enter an invalid URL and install. The error fades and
  expands in.
- Fix the URL. As the error clears, the **message stays readable while it fades and
  collapses**. At 10x you can read it until it is gone.

---

## Boundaries (all sub-sections)

- Do NOT touch any file not named in a sub-section.
- `ProfileSelectionScreen.kt`: do NOT touch the PIN shake (`playErrorAnimation`, `tween(42)`).
  Plan 016 owns its gating.
- `CastDetailScreen.kt`: do NOT touch `CastDetailSkeleton`. Plan 009 owns it.
- `ambient/AmbientSlideshow.kt`: do NOT touch it. Plan 016 owns it.
- Do NOT add dependencies. Do NOT change the baseline file.
- If an excerpt does not match (drift since ee8dd84), STOP that sub-section and report.

## Verification (whole plan)

- **Mechanical**, after each sub-section:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes with an
    unchanged baseline.
  - After (a) and (e), also run `./gradlew :app:testFullDebugUnitTest --tests '*PartySyncTest*'`
    as a regression check on the party package.
- **Feel check**: as listed per sub-section, at 1x, at Animator duration scale 5x or 10x,
  and with "Animation off".
- **Done when**:
  - Avatars no longer scale from 0, and code characters don't bounce.
  - Ambient mode exits in 150ms.
  - `CastDetailScreen` has no `visible = true` wrapper.
  - No party or PIN infinite transition is read in composition.
  - The essential addon error text stays visible through its exit.
  - The build is green.
