# 004 — Stop sliding the whole NavHost on navigation, and make screen transitions a short ease-out crossfade

- **Status**: DONE (build not verified locally: no Android SDK in the session; shipped variant A, no enter delay)
- **Commit**: ee8dd84
- **Severity**: HIGH
- **Category**: 5. Performance / 2. Easing & duration
- **Estimated scope**: 2 files (`MainActivity.kt`, `ui/navigation/FetchNavHost.kt`), ~30 lines
- **Depends on**: 001 (`FetchMotion.enterTween()`, `FetchMotion.exitTween()`)

## Problem

### (a) The content padding animates the whole NavHost's layout

`app/src/main/java/com/fetch/tv/MainActivity.kt:1664-1676` (current):

```kotlin
        val contentStartPadding by animateDpAsState(
            targetValue = if (showSidebar && !sidebarCollapsed) {
                FetchLayout.tokens.sidebarContentOffset
            } else {
                FetchTheme.spacing.none
            },
            animationSpec = tween(FetchMotion.tokens.durations.medium),
            label = "contentStartPadding"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = contentStartPadding)
```

- `showSidebar = currentRoute in rootRoutes` (`MainActivity.kt:1494`).
- `sidebarContentOffset` is `54.dp` (`ui/theme/LayoutMediaTokens.kt:37`).
- Every navigation between a root route (Home, Search, Library, ...) and a non-root route
  (Detail, Stream, Player, settings pages) changes the target. That is tens of times a day,
  for example Home → Detail and Back.
- For the next 350ms (`durations.medium`), `contentStartPadding` is read in composition and
  applied as layout padding around the `FetchNavHost`. Both the outgoing and the incoming screen
  (the NavHost draws both during its crossfade) are re-measured and re-laid-out every frame.
- Both screens also visibly slide 54dp sideways while they crossfade.
- This is the most expensive kind of animation in Compose, run on the heaviest subtree in the
  app, at the moment the new screen is also loading.

### (b) The screen transition is a slow, symmetric crossfade

`app/src/main/java/com/fetch/tv/ui/navigation/FetchNavHost.kt:107-157`. All four transitions
fall through to the same spec (current, the `else` branches at lines 119, 131, 143, 155):

```kotlin
                fadeIn(animationSpec = tween(FetchMotion.tokens.durations.medium))
...
                fadeOut(animationSpec = tween(FetchMotion.tokens.durations.medium))
```

- That is 350ms in and 350ms out with the same default FastOutSlowIn easing.
- A symmetric crossfade sums to about 50% coverage at the midpoint, so every navigation dims
  through the background.
- Two full screens are drawn for the whole 350ms.
- The UI budget is under 300ms. Entering and exiting UI should use a strong ease-out, with the
  exit shorter than the enter.
- There is no reduced-motion branch. Low-RAM devices draw two screens for 350ms on every
  navigation.

The `autoPlayNav` stream↔player branches (`EnterTransition.None` / `ExitTransition.None`) are
correct and stay unchanged.

## Target

(a) Padding:
- Snap. `contentStartPadding` becomes a plain `Dp` value with no animation.
- Layout changes once, on the frame the route changes. The NavHost crossfade from (b) hides the
  change.
- A layout-phase offset (`Modifier.offset { }`) is not used, and is not needed:
  - The padding changes the content's width (root screens are 54dp narrower to sit beside
    the sidebar), so it cannot be replaced by a translation.
  - Once it snaps, it costs one re-layout per navigation instead of one per frame for 350ms.
    Nothing is left to defer.

(b) NavHost transitions, for the non-autoPlay branches:
- `enterTransition` and `popEnterTransition`:
  - full motion: `fadeIn(animationSpec = FetchMotion.enterTween())`, which is 200ms with
    `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`
  - reduced motion: `EnterTransition.None`
- `exitTransition` and `popExitTransition`:
  - full motion: `fadeOut(animationSpec = FetchMotion.exitTween())`, which is 150ms with the same curve
  - reduced motion: `ExitTransition.None`
- No enter delay.
  - With the strong ease-out, the outgoing screen is about 70% gone within the first ~30ms
    while the incoming one is already about 60% in. Coverage stays near 90% throughout, so the
    background dip is mostly gone. The double exposure lasts only a few frames.
  - A delay would add latency to every navigation and would bring the dip back (the outgoing
    screen leaves before the incoming one starts).
  - The A/B step below checks this on device.
- Reduced motion: `None` is a hard cut. That is acceptable here because it is also the
  low-RAM path, where not drawing two full screens is the point. The new screen's own content
  still provides the change of state.

## Repo conventions to follow

- Durations and easings come from `FetchMotion` (`app/src/main/java/com/fetch/tv/ui/theme/MotionFocusTokens.kt`).
  Plan 001 adds `enterTween()` and `exitTween()` there.
- Reduced motion is `FetchTheme.fullMotion`, a `@Composable` getter. NavHost transition lambdas
  are not `@Composable`, so read it once in the enclosing composable and capture the value.
  Exemplar: `app/src/main/java/com/fetch/tv/ui/screens/player/PlaybackDetailsBar.kt:95-104`
  (`val fullMotion = FetchTheme.fullMotion`, then `if (fullMotion) fadeIn(...) else EnterTransition.None`).
- `FetchTheme` is not yet imported in `FetchNavHost.kt`. It lives at `com.fetch.tv.ui.theme.FetchTheme`.

## Steps

1. In `app/src/main/java/com/fetch/tv/MainActivity.kt`, replace lines 1664-1672 (the
   `animateDpAsState` block quoted in **Problem (a)**) with:

   ```kotlin
           // Snaps on purpose: animating this padding re-laid-out both screens of the NavHost
           // crossfade every frame, and the crossfade already covers the change.
           val contentStartPadding = if (showSidebar && !sidebarCollapsed) {
               FetchLayout.tokens.sidebarContentOffset
           } else {
               FetchTheme.spacing.none
           }
   ```

   Leave `.padding(start = contentStartPadding)` at line 1676 as it is.

2. In `MainActivity.kt`, check whether `animateDpAsState` is still used:
   `grep -n "animateDpAsState" app/src/main/java/com/fetch/tv/MainActivity.kt`.
   If the only hit is the import at line 20, delete that import line.
   - Keep `import androidx.compose.animation.core.tween`. The splash fade at line 1284 still uses it.

3. In `app/src/main/java/com/fetch/tv/ui/navigation/FetchNavHost.kt`, inside `PlaybackNavHost`,
   directly above `NavHost(` (line 107), add:

   ```kotlin
       // Transition lambdas aren't composable, so read reduced motion here and capture it.
       val fullMotion = FetchTheme.fullMotion
   ```

   Add the import `import com.fetch.tv.ui.theme.FetchTheme` beside `import com.fetch.tv.ui.theme.FetchMotion` (line 3).

4. In the same `NavHost(...)` call, replace only the four `else` branches:
   - line 119, in `enterTransition`, and line 143, in `popEnterTransition`. Replace
     `fadeIn(animationSpec = tween(FetchMotion.tokens.durations.medium))` with:
     ```kotlin
                     if (fullMotion) fadeIn(animationSpec = FetchMotion.enterTween()) else EnterTransition.None
     ```
   - line 131, in `exitTransition`, and line 155, in `popExitTransition`. Replace
     `fadeOut(animationSpec = tween(FetchMotion.tokens.durations.medium))` with:
     ```kotlin
                     if (fullMotion) fadeOut(animationSpec = FetchMotion.exitTween()) else ExitTransition.None
     ```

   Keep every `isStreamToPlayer` / `isPlayerToStream` / `isAutoPlayNav` check and its
   `EnterTransition.None` / `ExitTransition.None` result exactly as it is.

5. Run `grep -n "tween" app/src/main/java/com/fetch/tv/ui/navigation/FetchNavHost.kt`. If the
   only hit is `import androidx.compose.animation.core.tween` (line 11), delete that import.

6. A/B feel check for the enter delay. Do this before committing, then revert variant B.
   - A: as written.
   - B: temporarily change both full-motion enter specs to
     `fadeIn(animationSpec = tween(durationMillis = FetchMotion.tokens.durations.enter, delayMillis = FetchMotion.tokens.durations.exit / 3, easing = FetchMotion.tokens.easings.out))`.
     This is a 50ms delay. `tween` must be imported for B.
   - Compare them on Home → Detail → Back, five times each, with Animator duration scale at 1x and
     at 5x.
   - Keep A unless B clearly removes a visible double exposure, meaning the Home rows ghosting
     over the Detail hero, without making navigation feel slower.
   - If B wins, keep B and keep the `tween` import. `ThemeLiteralGuardTest` does not flag it
     because the first argument is a token.

## Boundaries

- Do NOT change the autoPlay stream↔player `None` branches, the route predicates, or any
  `composable(...)` destination in `FetchNavHost.kt`.
- Do NOT touch `MainActivity.kt` outside lines 1664-1672 and the one import in Step 2.
  - Plan 002 edits lines 612-617.
  - Plan 020 edits the splash fade at lines 1282-1303.
- Do NOT change `FetchLayout.tokens.sidebarContentOffset`, `rootRoutes`, or the sidebar
  drawer itself.
- Do NOT add new dependencies.
- If a step doesn't match the code you find (drift since ee8dd84), STOP and report instead of improvising.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes. Neither file
    is in `theme-literals-baseline.txt` and no literal `tween(<digits>` is added.
  - `grep -n "durations.medium" app/src/main/java/com/fetch/tv/ui/navigation/FetchNavHost.kt` returns nothing.
  - `grep -n "contentStartPadding" app/src/main/java/com/fetch/tv/MainActivity.kt` shows a plain
    `val`, not `by animateDpAsState`.
  - No unit test covers `FetchNavHost` or this padding:
    `grep -rn "FetchNavHost\|contentStartPadding" app/src/test` is empty.
- **Feel check** (TV or emulator):
  - Home → open a title (Detail) → Back, ten times.
    - The Detail screen appears quickly, with no grey or black dip mid-transition.
    - Neither screen slides sideways.
    - Back to Home lands with the rows exactly where they were, at the sidebar offset, with no
      54dp slide-in.
  - Set Animator duration scale to 5x and repeat once.
    - The old screen is mostly gone in the first few frames.
    - The new screen eases in.
    - The 54dp layout change happens in one frame, under the fade. It is acceptable for the
      outgoing screen to jump once while it is already fading out. It must not slide.
  - Stream → Player with autoplay, and Player → Back to Stream: still a hard cut, as before.
  - Performance:
    - Enable "Profile GPU rendering → On screen as bars" and do Home → Detail.
    - The tall bars during the transition are shorter and fewer than before.
    - Optionally confirm in Layout Inspector that the NavHost's subtree is not recomposed or
      re-laid-out on every frame of the transition.
  - Reduced motion: set Animator duration scale to "Animation off". Navigation is an instant
    cut in both directions, and the padding change is invisible.
- **Done when**:
  - No `animateDpAsState` drives `contentStartPadding`.
  - All four NavHost transitions use `enterTween()`/`exitTween()` under full motion and
    `None` otherwise.
  - The autoPlay branches are unchanged.
  - The build and guard test are green.
