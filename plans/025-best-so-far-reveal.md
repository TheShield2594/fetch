# 025 — Fade in "Best so far"; draw the addon dots in draw; tidy the stream list entrance

- **Status**: DONE (build not verified locally: no Android SDK in the session)
- **Commit**: ee8dd84
- **Severity**: LOW
- **Category**: Missed opportunity + 1. Performance + 6. Accessibility (reduced motion)
- **Estimated scope**: 2 files (`FetchingPanel.kt` ~35 lines, `StreamScreen.kt` ~12 lines)
- **Depends on**: 001 (uses `FetchMotion.enterTween()`, `exitTween()`). Do after 013 if both are
  pending (both edit `StreamScreen.kt`, different regions). Works with or without 006; see
  "Interaction with plan 006".

## Problem

**"Best so far" pops in.** `app/src/main/java/com/fetch/tv/ui/screens/stream/FetchingPanel.kt`
is the bottom-anchored panel shown while addons answer. When the first playable stream arrives,
`bestSoFar` turns non-null and, in one frame:
- the "Best so far" block appears (lines 114-139):
  ```kotlin
  // FetchingPanel.kt:114-115 — current
  if (bestSoFar != null) {
      Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
  ```
- the "Play now" button appears (lines 142-149):
  ```kotlin
  // FetchingPanel.kt:142-149 — current
  if (bestSoFar != null) {
      PanelButton(
          text = stringResource(R.string.fetching_play_now),
          primary = true,
          onClick = onPlayNow,
          modifier = Modifier.focusRequester(playNowFocus)
      )
  }
  ```
- focus jumps to it (`LaunchedEffect(hasBest)` at lines 80-82);
- the panel grows taller, and since it is bottom-anchored its top edge jumps up.

This happens once per fetch.

**Addon dots recompose to change colour** (lines 207-224):

```kotlin
// FetchingPanel.kt:207-224 — current
val dotColor by animateColorAsState(
    targetValue = when (chip.status) { ... },
    animationSpec = FetchMotion.focusTween(),
    label = "addonDot"
)
...
Box(
    modifier = Modifier
        .size(8.dp)
        .background(dotColor, CircleShape)
)
```

The colour is read in composition, so each answering addon recomposes its `AddonStatus` row
for 180ms.

**Stream screen entrance** (`app/src/main/java/com/fetch/tv/ui/screens/stream/StreamScreen.kt`):

```kotlin
// StreamScreen.kt:969-973 — current (addon chip row)
androidx.compose.animation.AnimatedVisibility(
    visible = sourceChips.isNotEmpty() || (!isLoading && availableAddons.isNotEmpty()),
    enter = fadeIn(animationSpec = tween(FetchMotion.tokens.durations.medium)),
    exit = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.medium))
) {
// StreamScreen.kt:994-1002 — current (stream list)
androidx.compose.animation.AnimatedVisibility(
    visible = enter,
    enter = fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast)) +
        slideInHorizontally(
            animationSpec = tween(FetchMotion.tokens.durations.fast),
            initialOffsetX = { fullWidth -> (fullWidth * 0.06f).toInt() }
        ),
    exit = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.quick))
) {
```

The chip row fades over 350ms both ways (too slow for UI). The list's 6% slide runs even
when `FetchTheme.fullMotion` is false.

### Interaction with plan 006 (LoadingOverlay bottom inset)

StreamScreen feeds this panel's measured height into the loader as `bottomInset`
(`StreamScreen.kt:455-459`, `.onSizeChanged { fetchingPanelHeight = ... }` at :481).
- If the panel's height were animated (`animateContentSize`/`expandVertically`),
  `onSizeChanged` would fire every frame, so `bottomInset` would change every frame. With
  plan 006 that re-lays out the overlay every frame and restarts its 250ms glide every frame,
  so the logo would lag behind the panel. Without 006, the current
  `animateDpAsState(medium)` would also be retargeted every frame.
- So the panel height **snaps** (one frame, once per fetch), and only the new content fades.
  With 006, the logo then makes room with a single 250ms glide, which reads as the panel
  growing and the logo stepping aside. The panel's top edge moves in one frame; that is
  acceptable for a once-per-fetch change and keeps the loader to one relayout.

## Target

- "Best so far" block and "Play now" button: each in an `AnimatedVisibility` with
  `enter = fadeIn(FetchMotion.enterTween())`, `exit = fadeOut(FetchMotion.exitTween())`,
  no expand/shrink. The last non-null `bestSoFar` is kept for the exit.
- Dot: `animateColorAsState` kept (the `State` object, not `by`), drawn with
  `drawBehind { drawCircle(dotColor.value) }`.
- Chip row: `fadeIn(FetchMotion.enterTween())` / `fadeOut(FetchMotion.exitTween())`.
- Stream list: when `fullMotion`, `fadeIn(enterTween()) + slideInHorizontally(enterTween(), 6%)`;
  otherwise `fadeIn(enterTween())` only. Exit unchanged.

## Repo conventions to follow

- Draw-phase reads: `app/src/main/java/com/fetch/tv/ui/screens/player/PlayerProgressFill.kt`.
- Reduced motion: `val fullMotion = FetchTheme.fullMotion` then branch
  (`app/src/main/java/com/fetch/tv/ui/screens/player/PlaybackDetailsBar.kt:95-104`), keeping the fade.
- Neither file has a `theme-literals-baseline.txt` line, and no literal is added.

## Steps

1. **`FetchingPanel.kt` imports.** Add:
   ```kotlin
   import androidx.compose.animation.AnimatedVisibility
   import androidx.compose.animation.fadeIn
   import androidx.compose.animation.fadeOut
   import androidx.compose.ui.draw.drawBehind
   ```
   Remove `import androidx.compose.foundation.shape.CircleShape` (unused after step 5).

2. **Keep the last best stream.** After `val hasBest = bestSoFar != null` (line 70), add:
   ```kotlin
   // Kept while "Best so far" fades out, if the best stream is ever withdrawn.
   val shownBest = remember { mutableStateOf(bestSoFar) }
   if (bestSoFar != null) shownBest.value = bestSoFar
   ```

3. **"Best so far" block.** Replace lines 114-139 (`if (bestSoFar != null) { Column(...) { ... } }`) with:
   ```kotlin
   AnimatedVisibility(
       visible = hasBest,
       enter = fadeIn(FetchMotion.enterTween()),
       exit = fadeOut(FetchMotion.exitTween())
   ) {
       shownBest.value?.let { best ->
           Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
               // ... the existing Text + FlowRow, unchanged except `bestSoFar.` → `best.`
           }
       }
   }
   ```
   Inside, replace every `bestSoFar.labels`, `bestSoFar.isCached`, `bestSoFar.addonName` with
   `best.labels`, `best.isCached`, `best.addonName`.

4. **"Play now".** Replace lines 142-149 with:
   ```kotlin
   AnimatedVisibility(
       visible = hasBest,
       enter = fadeIn(FetchMotion.enterTween()),
       exit = fadeOut(FetchMotion.exitTween())
   ) {
       PanelButton(
           text = stringResource(R.string.fetching_play_now),
           primary = true,
           onClick = onPlayNow,
           modifier = Modifier.focusRequester(playNowFocus)
       )
   }
   ```
   `AnimatedVisibility` composes its content in the same frame `hasBest` turns true, so
   `playNowFocus` is attached before `LaunchedEffect(hasBest)` (lines 80-82) runs. Leave that
   effect unchanged.

5. **Dot.** In `AddonStatus` (lines 207-224), change `val dotColor by animateColorAsState(` to
   `val dotColor = animateColorAsState(` (arguments unchanged), and the dot `Box` to:
   ```kotlin
   Box(
       modifier = Modifier
           .size(8.dp)
           // Colour read while drawing, so a status change only redraws the dot.
           .drawBehind { drawCircle(dotColor.value) }
   )
   ```

6. **`StreamScreen.kt` chip row** (lines 971-972):
   ```kotlin
   enter = fadeIn(animationSpec = FetchMotion.enterTween()),
   exit = fadeOut(animationSpec = FetchMotion.exitTween())
   ```

7. **`StreamScreen.kt` stream list.** In `RightStreamSection` (starts line 842), after
   `var enter by remember { mutableStateOf(false) }` (line 873), add
   `val fullMotion = FetchTheme.fullMotion`. Then replace the `enter =` argument at lines 996-1000 with:
   ```kotlin
   enter = if (fullMotion) {
       fadeIn(animationSpec = FetchMotion.enterTween()) +
           slideInHorizontally(
               animationSpec = FetchMotion.enterTween(),
               initialOffsetX = { fullWidth -> (fullWidth * 0.06f).toInt() }
           )
   } else {
       fadeIn(animationSpec = FetchMotion.enterTween())
   },
   ```
   Keep `exit = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.quick))` as is.

## Boundaries

- Edit only `FetchingPanel.kt` and `StreamScreen.kt:842-1002` as above.
- Do NOT animate the panel's size (no `animateContentSize`, no `expandVertically`); see
  "Interaction with plan 006".
- Do NOT touch `StreamScreen.kt:408-502` (plan 013 owns the morph; plan 006 owns
  `LoadingOverlay.kt` and needs no StreamScreen change), `FetchLaunchMorph.kt`, `SpecChip`,
  `PanelButton`, or the focus `LaunchedEffect`.
- If the code doesn't match the excerpts (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes.
  - `./gradlew :app:testFullDebugUnitTest --tests '*FetchLaunchMorphTest*'` passes (same
    package; covers `addonsInAnswerOrder`, which this plan must not change).
  - Optional: `FETCH_SCREENSHOTS=1 ./gradlew :app:testFullDebugUnitTest --tests '*ReadmeScreenshots.fetchingPanel*'`
    renders the panel with a best stream; it should look identical to before.
- **Feel check** (TV or emulator, auto-play on, a title with several addons):
  - Animator duration scale 10x: when the first stream arrives, "Best so far" and "Play now"
    fade in over 200ms; the panel grows in one step; focus is on "Play now". With 006 applied,
    the logo above glides up once, without stutter.
  - Before any stream arrives, the panel's spacing matches the old build (a hidden
    `AnimatedVisibility` emits no layout, so `spacedBy` adds no extra gap).
  - Addon dots change colour smoothly as each addon answers; Layout Inspector shows no
    `AddonStatus` recompositions during the colour change.
  - Open "See all sources": the chip row fades in within 200ms; the list fades and slides in
    from the right.
  - Animator duration scale "Animation off": the list appears with no slide; everything
    still shows and focus still lands on "Play now".
- **Done when**: the build and tests are green, "Best so far" fades in, the panel height
  never animates, and the list slide is skipped in reduced motion.
