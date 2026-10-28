# 013 — Play → Fetching morph: 250ms, earlier content reveal, resume by remaining distance

- **Status**: TODO
- **Commit**: ee8dd84
- **Severity**: MEDIUM
- **Category**: 2. Easing & duration + 4. Interruptibility
- **Estimated scope**: 3 files (`StreamScreen.kt` ~10 lines, `FetchLaunchMorph.kt` 3 lines, `FetchLaunchMorphTest.kt` 4 lines)
- **Depends on**: 001 (uses `FetchMotion.tokens.durations.move`)

## Problem

Pressing Play on the detail page or home hero opens StreamScreen, where the pressed accent
button grows into the bottom "Fetching" panel. The morph is driven by
`app/src/main/java/com/fetch/tv/ui/screens/stream/StreamScreen.kt:485-501`:

```kotlin
// StreamScreen.kt:485-501 — current
LaunchedEffect(panelBounds != null) {
    if (panelBounds == null || launchProgress.value >= 1f) return@LaunchedEffect
    // A panel that shows up well after the press no longer reads as its result.
    val pressedAtMs = launchOrigin?.atMs ?: 0L
    if (android.os.SystemClock.elapsedRealtime() - pressedAtMs > PlayLaunchOrigin.MAX_AGE_MS) {
        launchProgress.snapTo(1f)
    } else {
        launchProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = FetchMotion.tokens.durations.hero,
                // Linear time; FetchLaunchMorph eases the growth itself.
                easing = LinearEasing
            )
        )
    }
}
```

`durations.hero` is 450ms, on the path of every Play press. The panel's content opacity comes
from `app/src/main/java/com/fetch/tv/ui/screens/stream/FetchLaunchMorph.kt:17` and `:54-55`:

```kotlin
// FetchLaunchMorph.kt:16-17 — current
/** The share of the morph after which the Fetching panel's own content starts to show. */
private const val CONTENT_REVEAL_START = 0.55f
// FetchLaunchMorph.kt:53-55 — current
/** How visible the panel's content is at [progress] through the morph: it fades in at the end. */
internal fun launchContentAlpha(progress: Float): Float =
    ((progress - CONTENT_REVEAL_START) / (1f - CONTENT_REVEAL_START)).coerceIn(0f, 1f)
```

So the panel's text and buttons are fully transparent for the first 0.55 × 450 ≈ 248ms. Yet
`FetchingPanel` requests focus on its first composition
(`FetchingPanel.kt:80-82`: `playNowFocus` if a best stream already exists, else `seeAllFocus`).
A quick OK in that window activates an invisible "Play now" or "See all sources". FetchingPanel
handles clicks with plain `androidx.tv.material3.Button(onClick = ...)` (`FetchingPanel.kt:246-248`);
there is no gating.

Interruption: the effect is keyed on `panelBounds != null`, and `fetchingPanelBounds`
(`StreamScreen.kt:413`) lives outside the `if (waitingForPick)` block. If `waitingForPick` flips
true → false → true mid-morph, the effect leaves and re-enters composition with a stale non-null
bounds and restarts a full 450ms tween from the partial value. The last stretch then crawls.

Already correct, keep as is: the Canvas reads progress in draw (`FetchLaunchMorph.kt:38-50`),
the panel alpha is set in `graphicsLayer` (`StreamScreen.kt:483`), `launchDone` is a
`derivedStateOf` (`:416`), reduced motion skips the morph (`:411`, `takeIf { fullMotion }`), the
`MAX_AGE_MS` snap (`:489-490`), and Back pops at once. The emphasized curve on the geometry is
applied at `FetchLaunchMorph.kt:41`.

## Target

- Duration `FetchMotion.tokens.durations.move` (250ms), still `LinearEasing` (time), with the
  emphasized curve still applied to geometry inside `FetchLaunchMorph`.
- On a (re)start from partial progress `p`, the duration is `(move * (1f - p)).toInt()`.
  At p = 0 that is the full 250ms.
- `CONTENT_REVEAL_START = 0.3f`: content starts to show at 75ms, is 50% visible at 162ms
  (progress 0.65), and fully visible at 250ms. By the emphasized curve the shape is already
  ~3/4 grown at progress 0.3, so the content appears into a nearly formed panel.
- **"Play now" activation: no click gating.** With the earlier reveal, content is visible from
  75ms. A deliberate OK needs the user to see the panel and react (~200ms+), by which time the
  content is ≥ 50% opaque. The tv `Button` should fire `onClick` only on a key-up whose key-down
  it saw itself (tv-material's Surface tracks the D-pad press), so the key-up of the original
  Play press should not click it; the fast-press feel check below confirms this. Gating
  (`enabled = launchDone`) would restyle the buttons (disabled colours) for the first 250ms,
  which reads as a flicker. Revisit only if the feel check below fails.
- If, and only if, the A/B feel check finds 250ms unreadable: 300ms via a new token (optional
  step 5).

## Repo conventions to follow

- Durations come from `FetchMotion.tokens.durations` (`app/src/main/java/com/fetch/tv/ui/theme/MotionFocusTokens.kt`).
  A new value belongs there, never as a literal in a screen (ThemeLiteralGuardTest).
- The `tween(` here takes a computed `durationMillis = (...)`, not a digit, so the guard regex
  `\btween(<[^>()]+>)?\(\s*(durationMillis\s*=\s*)?\d+` does not match. Neither
  `StreamScreen.kt` nor `FetchLaunchMorph.kt` has a baseline line; none is added.

## Steps

1. **`StreamScreen.kt:491-499`.** Replace the `else` branch body with:
   ```kotlin
   } else {
       // A morph cut short (the panel left and came back) runs only the distance it has left.
       val remaining = 1f - launchProgress.value
       launchProgress.animateTo(
           targetValue = 1f,
           animationSpec = tween(
               durationMillis = (FetchMotion.tokens.durations.move * remaining).toInt(),
               // Linear time; FetchLaunchMorph eases the growth itself.
               easing = LinearEasing
           )
       )
   }
   ```

2. **`FetchLaunchMorph.kt:16-17`.** Change to:
   ```kotlin
   /** The share of the morph after which the Fetching panel's own content starts to show. */
   private const val CONTENT_REVEAL_START = 0.3f
   ```
   and the KDoc at line 53 to:
   ```kotlin
   /** How visible the panel's content is at [progress] through the morph: it fades in over the last 70%. */
   ```

3. **`app/src/test/java/com/fetch/tv/ui/screens/stream/FetchLaunchMorphTest.kt:36-42`.** Replace
   the test with:
   ```kotlin
   @Test
   fun `panel content fades in over the last 70 percent of the morph`() {
       assertEquals(0f, launchContentAlpha(0f), 0f)
       assertEquals(0f, launchContentAlpha(0.3f), 0f)
       assertEquals(1f, launchContentAlpha(1f), 0f)
       assertEquals(0.5f, launchContentAlpha(0.65f), 0.001f)
   }
   ```

4. Leave `ACCENT_HOLD = 0.4f` (`FetchLaunchMorph.kt:20`) as is: the shape keeps the accent for
   the first 100ms.

5. **Optional, only if the A/B in Verification picks 300ms.** In
   `app/src/main/java/com/fetch/tv/ui/theme/MotionFocusTokens.kt`:
   - In `FetchMotionDurations`, after the `move` field added by plan 001, add:
     ```kotlin
     /** The Play button growing into the Fetching panel. */
     val launch: Int,
     ```
   - In `FetchMotion.tokens`' `FetchMotionDurations(...)`, after `move = 250,`, add `launch = 300,`.
   - In step 1's code, use `FetchMotion.tokens.durations.launch` instead of `.move`.

## Boundaries

- Do NOT touch `FetchingPanel.kt` or `StreamScreen.kt` outside lines 485-501; plan 025 edits
  `FetchingPanel.kt` and `StreamScreen.kt:969-1001`, plan 006 owns `LoadingOverlay.kt`.
- Do NOT change the `LaunchedEffect` key, the `MAX_AGE_MS` snap, `launchDone`, the
  `graphicsLayer` alpha at `StreamScreen.kt:483`, or the emphasized geometry curve.
- Do NOT add click gating to `FetchingPanel`.
- If the code doesn't match the excerpts (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes.
  - `./gradlew :app:testFullDebugUnitTest --tests '*FetchLaunchMorphTest*'` passes.
- **Feel check** (TV or emulator, auto-play on so the Fetching panel shows):
  - Animator duration scale 10x: press Play on a detail page. The accent button grows into
    the panel; the panel text appears while the shape is still settling, not after it.
  - At 1x, press Play then OK as fast as you can: you can see the button you hit. Repeat 10
    times; if an invisible button is ever activated, report it (that is the condition to
    revisit gating).
  - **A/B duration**: build once with `durations.move` (250ms) and once with step 5 (300ms).
    Press Play 10 times each from the detail page and from the home hero. Keep 250ms unless the
    button-to-panel growth can't be followed by eye at 1x.
  - Interruption: on a title where the auto-pick resolves very fast and then falls back to the
    panel, the morph finishes in the remaining time instead of crawling. (Hard to trigger; at
    10x, watch that a resumed morph never takes longer than the first.)
  - Animator duration scale "Animation off": no morph, the panel is shown at once with its
    content visible (existing `takeIf { fullMotion }` path).
- **Done when**: the build and both tests are green and the morph takes 250ms (or 300ms if
  the A/B chose step 5) with content visible from ~75ms.
