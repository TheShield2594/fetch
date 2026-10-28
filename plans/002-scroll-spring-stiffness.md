# 002 — Give the focus-scroll spring a real stiffness, and near-snap it under reduced motion

- **Status**: DONE (build not verified locally: no Android SDK in the session; A/B not run, shipped default 400)
- **Commit**: ee8dd84
- **Severity**: HIGH
- **Category**: 2. Easing & duration / 4. Interruptibility
- **Estimated scope**: 2 files (`FetchScrollDefaults.kt`, `MainActivity.kt`), ~25 lines
- **Depends on**: 001 (only for ordering; this plan uses the existing `FetchMotion.focusSpring()`, which is already in the codebase)

## Problem

Every D-pad move in every row and column of the app scrolls the focused item into view with
one spring. That spring's stiffness is a millisecond duration token reused as a physics value.

`app/src/main/java/com/fetch/tv/ui/components/FetchScrollDefaults.kt:12-17` (current):

```kotlin
    val smoothScrollSpec = object : BringIntoViewSpec {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = spring(
            dampingRatio = 0.95f,
            stiffness = FetchMotion.tokens.durations.fast.toFloat()
        )
```

`FetchMotion.tokens.durations.fast` is `180` (milliseconds, `ui/theme/MotionFocusTokens.kt:69`).
Used as a stiffness that is below `Spring.StiffnessLow` (200): a very soft spring with a long
tail. The rows trail behind focus, and holding the D-pad (key repeat is 72ms,
`FetchFocus.tokens.longPressRepeatMillis` at `MotionFocusTokens.kt:118`) makes the list lag further
and further behind the focus ring. This happens hundreds of times a day.

The spec is on by default:
- `app/src/main/java/com/fetch/tv/data/local/LayoutPreferenceDataStore.kt:406-408`:
  `prefs[smoothBringIntoViewEnabledKey] ?: true`
- It is provided app-wide in `app/src/main/java/com/fetch/tv/MainActivity.kt:612-617` (current):

```kotlin
                val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
                val bringIntoViewSpec = if (mainUiPrefs.smoothBringIntoViewEnabled) {
                    FetchScrollDefaults.smoothScrollSpec
                } else {
                    defaultBringIntoViewSpec
                }
```

  and installed at `MainActivity.kt:704` with `LocalBringIntoViewSpec provides bringIntoViewSpec,`.

Rows re-wrap it and pass the same `scrollAnimationSpec` through, so fixing it at the source
fixes them all. Examples: `ui/components/ContinueWatchingSection.kt:203-204`,
`ui/components/CollectionRowSection.kt:156-157`, `ui/components/CatalogRowSection.kt:323-324`,
`ui/screens/home/ModernHomeRows.kt:833-834`, `ui/screens/home/ModernHomeContent.kt:1069`.
These need no edits:

```kotlin
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec
```

There is no reduced-motion handling. On low-RAM devices (`FetchTheme.fullMotion == false`) the
same soft spring runs.

## Target

- Full motion: `FetchMotion.focusSpring()`, which already exists in `MotionFocusTokens.kt:104-107`:
  `spring(dampingRatio = Spring.DampingRatioNoBouncy /* 1f */, stiffness = Spring.StiffnessMediumLow /* 400f */)`.
  - A critically damped spring at 400 settles in about 290ms with no overshoot.
  - It keeps the spring's velocity when key repeat retargets it.
- Reduced motion (`!FetchTheme.fullMotion`): a near-instant spring,
  `spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh /* 10_000f */)`, which settles in about 60ms.
  - Do not use `snap()` here. The bring-into-view scroller keeps retargeting one running
    animation as focus moves, and a zero-duration spec has no velocity to carry. A very stiff
    spring is the supported way to get "basically instant" from this API.
  - When the system animator scale is 0, Compose already finishes any spring instantly, so
    that case snaps either way.
- The scroll-distance logic (`calculateScrollDistance`) is unchanged and shared by both specs.
- The right full-motion stiffness is a feel call: compare 400 and 1500 (A/B steps below). If the
  A/B is inconclusive, ship 400 (`focusSpring()`).

## Repo conventions to follow

- Motion values come from `FetchMotion` in `app/src/main/java/com/fetch/tv/ui/theme/MotionFocusTokens.kt`.
  The spring helper `focusSpring()` lives there; reuse it rather than writing a new literal.
- Reduced motion is read with `FetchTheme.fullMotion` (a `@Composable @ReadOnlyComposable` getter,
  `ui/theme/Theme.kt:186-189`). Exemplar of branching on it:
  `app/src/main/java/com/fetch/tv/ui/screens/player/PlaybackDetailsBar.kt:95-104`.
- `MainActivity.kt:612` runs inside the `FetchTheme(...) { ... }` content lambda (opened at
  `MainActivity.kt:604`), so `FetchTheme.fullMotion` is readable there.
- Both specs must be stable singletons (`val` on the `object`), not values built per composition.
  The rows `remember(...)` their wrappers keyed on `defaultBringIntoViewSpec`, so a new instance
  on every recomposition would rebuild those wrappers.

## Steps

1. Replace the whole body of `object FetchScrollDefaults` in
   `app/src/main/java/com/fetch/tv/ui/components/FetchScrollDefaults.kt` (lines 10-34) with:

   ```kotlin
   @OptIn(ExperimentalFoundationApi::class)
   object FetchScrollDefaults {
       /** Focus scrolling with full motion: a critically damped spring that keeps up with key repeat. */
       val smoothScrollSpec: BringIntoViewSpec = FetchBringIntoViewSpec(FetchMotion.focusSpring())

       /** Reduced motion: the same scroll targets, reached almost at once. */
       val reducedMotionScrollSpec: BringIntoViewSpec = FetchBringIntoViewSpec(
           spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
       )
   }

   @OptIn(ExperimentalFoundationApi::class)
   private class FetchBringIntoViewSpec(
       private val animationSpec: AnimationSpec<Float>
   ) : BringIntoViewSpec {
       @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
       override val scrollAnimationSpec: AnimationSpec<Float>
           get() = animationSpec

       override fun calculateScrollDistance(
           offset: Float,
           size: Float,
           containerSize: Float
       ): Float {
           if (containerSize <= 0f || size <= 0f) return 0f
           val itemCenter = offset + size / 2f
           val viewportTarget = containerSize * FetchFocus.tokens.scrollViewportTarget
           val distance = itemCenter - viewportTarget
           if (offset >= 0f && offset + size <= containerSize && distance > 0f && distance < 12f) {
               return 0f
           }
           return distance
       }
   }
   ```

   Add the import `import androidx.compose.animation.core.Spring` next to the existing
   `import androidx.compose.animation.core.spring`. Keep the other imports.
   - The `calculateScrollDistance` body is copied verbatim from the current lines 19-32. Do not
     change it.
   - If the compiler rejects the `@Suppress` placement on the getter, put the same
     `@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")` on the class instead. Do not change
     anything else.

2. In `app/src/main/java/com/fetch/tv/MainActivity.kt:612-617`, replace the block quoted in
   **Problem** with:

   ```kotlin
                   val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
                   val fullMotion = FetchTheme.fullMotion
                   val bringIntoViewSpec = when {
                       !mainUiPrefs.smoothBringIntoViewEnabled -> defaultBringIntoViewSpec
                       fullMotion -> FetchScrollDefaults.smoothScrollSpec
                       else -> FetchScrollDefaults.reducedMotionScrollSpec
                   }
   ```

   - Do not change the provider at line 704.
   - `FetchTheme` is already imported in MainActivity. Confirm with
     `grep -n "import com.fetch.tv.ui.theme.FetchTheme" app/src/main/java/com/fetch/tv/MainActivity.kt`.
   - If a local named `fullMotion` already exists in that scope, reuse it.

3. A/B feel check before committing. Run it on a TV or emulator:
   1. Build with Step 1 as written (stiffness 400). Open Home and focus the first card of a long
      catalog row (20+ items).
      - Hold D-pad Right for 3 seconds, then release.
      - Tap Right about 6 times, quickly.
      - Do the same holding Down through the Home rows.
   2. Temporarily change `FetchMotion.focusSpring()` in `smoothScrollSpec` to
      `spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)`
      (1500) and repeat exactly.
   3. Pick the one where:
      - while held, the focused card stays at the same screen position instead of drifting
        right of it and catching up after release;
      - on single taps the row's glide is still readable as motion, not a jump.
   4. Ship the winner:
      - If 400 wins, or you can't tell, restore `FetchMotion.focusSpring()`.
      - If 1500 clearly wins, keep the inline
        `spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)` and
        add the comment `// 1500: chosen over focusSpring() (400) in a held-D-pad A/B.` above it.

## Boundaries

- Do NOT edit the row wrappers that pass `defaultBringIntoViewSpec.scrollAnimationSpec` through
  (ContinueWatchingSection, CollectionRowSection, CatalogRowSection, ModernHomeRows,
  ModernHomeContent, ModernHomeRowsList, TmdbEntityBrowseScreen, MetaDetailsScreen, AboutScreen).
  They inherit the fix.
- Do NOT change `calculateScrollDistance`, `FetchFocus.tokens.scrollViewportTarget`, or the
  user setting `smoothBringIntoViewEnabled` and its default.
- Do NOT change `FetchMotion.focusSpring()` itself or anything else in `MotionFocusTokens.kt`.
  Plan 020 (token cleanup) runs later and must see `focusSpring` as used.
- Do NOT touch `MainActivity.kt` outside lines 612-617. Plans 004 and 020 edit other parts of it.
- Do NOT add new dependencies.
- If a step doesn't match the code you find (drift since ee8dd84), STOP and report instead of improvising.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes. Neither file
    is in `app/src/test/resources/theme-literals-baseline.txt` and no `tween(<digits>` is added.
  - `grep -rn "durations.fast.toFloat" app/src/main` returns nothing.
  - No unit test covers `FetchScrollDefaults`. `grep -rn "FetchScrollDefaults\|smoothScrollSpec" app/src/test` is empty.
- **Feel check** (TV or emulator):
  - Hold D-pad Right on a long Home row for 3s. The focused card stays near 42% of the row
    width (the viewport target) and does not drift ahead of the scroll. On release the row stops
    within about 0.3s with no overshoot or bounce back.
  - Hold D-pad Down through Home rows. Same: no growing lag, no bounce.
  - Set Developer options → Animator duration scale to 5x. A single Right press glides and
    settles with no overshoot. Before this change it had a long, slow tail.
  - Performance: `adb shell dumpsys gfxinfo com.fetch.tv.debug reset`, hold Right for 10s, then
    `adb shell dumpsys gfxinfo com.fetch.tv.debug`. Janky frames are not higher than before the
    change.
  - Reduced motion:
    - Set Animator duration scale to "Animation off". Rows jump to the target position with no glide.
    - On a low-RAM device (or one where `FetchTheme.fullMotion` is false), rows reach the target
      almost immediately (about 60ms) rather than gliding.
  - Settings → the "focus scroll" toggle (`advanced_fetch_focus_scroll`) off: rows use the
    platform default scroll exactly as before.
- **Done when**:
  - `FetchScrollDefaults.kt` has no `durations.fast` reference.
  - `MainActivity.kt` picks between `smoothScrollSpec`, `reducedMotionScrollSpec` and the default.
  - The A/B result is recorded in the commit message, for example "scroll spring: 400 (A/B vs 1500)".
  - The build is green.
