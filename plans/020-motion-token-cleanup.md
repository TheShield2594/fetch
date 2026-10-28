# 020 — Motion token cleanup: retire dead tokens, unify shimmer and focus scales, tokenize and defer the splash fade

- **Status**: DONE (build not verified locally: no Android SDK in the session; ran with 017 deferred, which needed none of the retired tokens)
- **Commit**: ee8dd84
- **Severity**: LOW
- **Category**: 7. Cohesion & tokens
- **Estimated scope**: 9 files touched and 1 file deleted, ~60 lines
  - `ui/theme/MotionFocusTokens.kt`
  - `ui/components/Shimmer.kt`
  - `ui/components/PlaceholderShimmer.kt`
  - `app/src/test/resources/theme-literals-baseline.txt`
  - `ui/screens/search/SearchScreen.kt`
  - `ui/screens/addon/AddonManagerScreen.kt`
  - `ui/screens/settings/TrackingProviderDialogs.kt`
  - `ui/screens/home/ModernHomeHero.kt`
  - `MainActivity.kt`
  - deleted: `ui/screens/home/AlwaysCrossfadeTransitionFactory.kt`
- **Depends on**: 001, and **every other plan in `plans/`**.
  - **Run this plan LAST.** Other plans start using tokens this plan would otherwise delete. For
    example, 002 uses `focusSpring()`, and 009 may use `durations.shimmer`.
  - Other plans also edit `MainActivity.kt` (002, 004) and `ModernHomeHero.kt` (003). Line
    numbers below are from ee8dd84, so locate every edit by the quoted text, not by line number.

All paths below are relative to `app/src/main/java/com/fetch/tv/` unless they start with `app/`.

## Problem

### 1. Dead and over-budget tokens in `ui/theme/MotionFocusTokens.kt`

These tokens had zero usages outside `MotionFocusTokens.kt` at ee8dd84:

| Group | Unused at ee8dd84 |
|---|---|
| `FetchMotionDurations` (lines 15-33) | `instant`, `sidebarLabelIn`, `sidebarLabelOut`, `sidebarPanelIn`, `sidebarPanelOut`, `sidebarBloomOut`, `sidebarEnter`, `sidebarExit`, `shimmer` |
| `FetchMotionEasings` (lines 36-41) | `accelerate` (an ease-in curve, `CubicBezierEasing(0.4f, 0f, 1f, 1f)`, which should never be used on UI) |
| `FetchMotionTokens` (lines 44-51) | `focusScale`, `selectedScale`, `pressedScale`, `reducedMotionScale` |
| `FetchFocusTokens` (lines 54-62) | `scale`, `subtleScale`, `pressedScale`, `disabledScale` |
| `FetchMotion` helpers (lines 96-107) | `quickTween()`, `focusSpring()` |

- `instant`, `selectedScale`, `pressedScale`, `reducedMotionScale` and the `FetchFocus`
  `pressedScale` / `disabledScale` were not in the original audit list. They are included
  because they matched the same zero-usage grep.
- Leave `longPressInitialDelayMillis` and `longPressRepeatMillis` alone.

Over the 300ms UI budget, and still used:
- `medium` = 350
- `overlay` = 400
- `slow` = 450
- `hero` = 450

Plans 002-019 move many callers to `enter` (200), `exit` (150) and `move` (250). Whatever is
left must at least say it is over budget.

### 2. Three shimmer speeds

- `ui/components/Shimmer.kt:25`:
  `animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),`
- `ui/components/PlaceholderShimmer.kt:39`:
  `animation = tween(durationMillis = 1600, easing = LinearEasing),`
- `ui/components/Skeletons.kt:465`: `tween(durationMillis = 1400, ...)`. Plan 009 owns
  Skeletons.kt; **this plan does not touch it.**
- The `durations.shimmer` token (1200) exists for this purpose and nothing uses it.
- Both literals are counted in `app/src/test/resources/theme-literals-baseline.txt`:
  - line 3: `com/fetch/tv/ui/components/PlaceholderShimmer.kt|DURATION|1`
  - line 4: `com/fetch/tv/ui/components/Shimmer.kt|DURATION|1`

### 3. Hand-typed focus scales

| Where | Value |
|---|---|
| Poster, backdrop, collection, continue-watching and episode cards: `ui/theme/ComponentTokens.kt:91,99,107,115,123` (`focusedScale = 1.04f`) | 1.04 |
| Recent searches, hand-rolled: `ui/screens/search/SearchScreen.kt:1053` `targetValue = if (isSearchFocused) 1.02f else 1f,` | 1.02 |
| Tracking dialog buttons: `ui/screens/settings/TrackingProviderDialogs.kt:704,755` `scale = ButtonDefaults.scale(focusedScale = 1.02f),` | 1.02 |
| Addon rows: `ui/screens/addon/AddonManagerScreen.kt:705,767,856,922` `scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)` | 1.01 |
| `LightweightCard` rows | none, deliberately; see its KDoc at `ui/components/LightweightCard.kt:48-49` |

The 1.02 and 1.01 values already exist as `FetchFocus.tokens.scale` (1.02f) and
`FetchFocus.tokens.subtleScale` (1.01f), exposed as `FetchTheme.focus` (`ui/theme/Theme.kt:164-165`).

### 4. Dead code

- `ui/screens/home/AlwaysCrossfadeTransitionFactory.kt` is referenced nowhere. The only grep hits
  for its name are inside the file itself.
- Its companion `import coil3.request.transitionFactory` at `ui/screens/home/ModernHomeHero.kt:68`
  is unused. That file never calls `.transitionFactory(...)`.

### 5. Splash fade: hard-coded 400ms, read in composition

`MainActivity.kt:1282-1305` (current):

```kotlin
                    val animatedSplashAlpha by animateFloatAsState(
                        targetValue = if (showSplash) 1f else 0f,
                        animationSpec = tween(if (showSplash) 0 else 400),
                        label = "startupSplashAlpha"
                    )
                    val splashAlpha = if (showSplash) 1f else animatedSplashAlpha
                    LaunchedEffect(splashAlpha, startupDestination) {
                        if (splashAlpha == 0f && startupDestination != StartupDestination.ProfileSelection) {
                            focusedSplashColor = null
                            focusedSplashBgUrl = null
                            focusedSplashCacheKey = null
                            focusedSplashTheme = null
                        }
                    }
                    if (splashAlpha > 0f) {
                        com.fetch.tv.ui.components.StartupSplashScreen(
                            profileColorHex = splashBackground.profileColorHex,
                            profileBackgroundUrl = splashBackground.backgroundUrl,
                            backgroundCacheKey = splashBackground.backgroundCacheKey,
                            skipGradient = splashBackground.skipGradient,
                            brandWordmarkRes = splashBackground.brandWordmarkRes,
                            modifier = Modifier.graphicsLayer { alpha = splashAlpha }
                        )
                    }
```

- `400` is a hard-coded duration. `ThemeLiteralGuardTest` does not catch it: its regex
  `\btween(<[^>()]+>)?\(\s*(durationMillis\s*=\s*)?\d+` needs a digit right after `tween(`, and
  here `tween(` is followed by `if`.
- `by animateFloatAsState` reads the value in composition, used as the `LaunchedEffect` key and
  in `if (splashAlpha > 0f)`. So for 400ms the scope recomposes every frame and restarts the
  `LaunchedEffect`, during Home's first frames, the most contended moment of the session.
- A once-per-session splash fade can stay around 400ms, but as a named token.

## Target

- **Tokens.** Each candidate token or helper is deleted only if a grep run at execution time
  finds zero usages outside `MotionFocusTokens.kt` (script in Step 1).
  - `durations.shimmer` becomes used by item 2.
  - `FetchFocus.tokens.scale` and `subtleScale` become used by item 3.
  - `focusSpring()` is used if plan 002 shipped.
  - Never delete a token that a remaining helper inside `MotionFocusTokens.kt` reads, for
    example `medium`, which `mediumTween()` reads.
- **Over-budget durations.** `medium`, `overlay`, `slow` and `hero` stay while used, each with
  a KDoc saying it is over the 300ms UI budget and naming the preferred token.
- **Shimmer.** `Shimmer.kt` and `PlaceholderShimmer.kt` use
  `FetchMotion.tokens.durations.shimmer` (1200ms, linear, unchanged easing). Their two baseline
  lines are deleted.
- **Focus scales.**
  - 1.02 → `FetchTheme.focus.scale`
  - 1.01 → `FetchTheme.focus.subtleScale`
  - Same values, so there is no visual change.
  - Cards stay at 1.04f. Any change to that look is out of scope without a feel check (see
    **Optional** below).
- **Dead code.** The factory file and the unused import are gone.
- **Splash.**
  - New token `FetchMotion.tokens.durations.splashExit = 400`.
  - The fade-out uses `tween(splashExit, easing = FetchMotion.tokens.easings.out)`, the strong
    ease-out for something leaving. Showing is `snap()`, which is equivalent to the old `tween(0)`.
  - Composition reads only a boolean (`derivedStateOf { value > 0f }`). The alpha is read only
    inside `graphicsLayer { }`.

## Repo conventions to follow

- Motion tokens are fields on the `@Immutable` data classes in `ui/theme/MotionFocusTokens.kt`,
  given values in the single `FetchMotion.tokens = FetchMotionTokens(...)` call (named arguments).
  `ui/theme` is exempt from `ThemeLiteralGuardTest`, so new literal values go there.
- `theme-literals-baseline.txt` only shrinks. A file under its count fails too, so delete the
  line when a file reaches 0.
- Deferred reads: exemplar `ui/components/PlaceholderShimmer.kt:46-64`, which reads the state
  inside the draw lambda.
- `FetchTheme.focus` is a plain (non-`@Composable`) getter, usable anywhere.

## Steps

### Step 1 — Inventory, after all other plans are merged

1. Confirm every other plan in `plans/` has Status DONE (or was explicitly dropped). If not, STOP.
2. From `app/src`, run:

   ```bash
   cd app/src && for p in \
    'durations\.sidebarLabelIn\b' 'durations\.sidebarLabelOut\b' 'durations\.sidebarPanelIn\b' \
    'durations\.sidebarPanelOut\b' 'durations\.sidebarBloomOut\b' 'durations\.sidebarEnter\b' \
    'durations\.sidebarExit\b' 'durations\.instant\b' 'durations\.shimmer\b' \
    'durations\.medium\b' 'durations\.overlay\b' 'durations\.slow\b' 'durations\.hero\b' \
    'easings\.accelerate\b' '\.focusScale\b' '\.selectedScale\b' \
    '(motion|FetchMotion\.tokens)\.pressedScale\b' '\.reducedMotionScale\b' \
    '(focus|FetchFocus\.tokens)\.scale\b' '(focus|FetchFocus\.tokens)\.subtleScale\b' \
    '(focus|FetchFocus\.tokens)\.pressedScale\b' '(focus|FetchFocus\.tokens)\.disabledScale\b' \
    '\bquickTween\(' '\bfocusSpring\(' ; do
     n=$(grep -rnE "$p" --include=*.kt . | grep -v 'ui/theme/MotionFocusTokens.kt' | wc -l)
     echo "$n  $p"
   done
   ```

   Save the output. Step 7 uses it **after** Steps 2-6 have added new usages, so run it again
   there.

### Step 2 — Shimmer durations

1. `ui/components/Shimmer.kt:25`: replace `tween(durationMillis = 2400, easing = LinearEasing)`
   with `tween(durationMillis = FetchMotion.tokens.durations.shimmer, easing = LinearEasing)`.
   Add `import com.fetch.tv.ui.theme.FetchMotion` beside `import com.fetch.tv.ui.theme.FetchTheme` (line 3).
2. `ui/components/PlaceholderShimmer.kt:39`: replace `tween(durationMillis = 1600, easing = LinearEasing)`
   with `tween(durationMillis = FetchMotion.tokens.durations.shimmer, easing = LinearEasing)`.
   Add `import com.fetch.tv.ui.theme.FetchMotion` beside `import com.fetch.tv.ui.theme.FetchTheme` (line 5).
3. In `app/src/test/resources/theme-literals-baseline.txt`, delete these two lines:
   ```
   com/fetch/tv/ui/components/PlaceholderShimmer.kt|DURATION|1
   com/fetch/tv/ui/components/Shimmer.kt|DURATION|1
   ```
   Leave every other line. Plan 009 owns the `Skeletons.kt` line.

### Step 3 — Focus scales (no visual change)

1. `ui/screens/search/SearchScreen.kt:1053`: replace
   `targetValue = if (isSearchFocused) 1.02f else 1f,` with
   `targetValue = if (isSearchFocused) FetchTheme.focus.scale else 1f,`.
2. `ui/screens/settings/TrackingProviderDialogs.kt:704` and `:755`: replace both
   `scale = ButtonDefaults.scale(focusedScale = 1.02f),` with
   `scale = ButtonDefaults.scale(focusedScale = FetchTheme.focus.scale),`.
3. `ui/screens/addon/AddonManagerScreen.kt:705, 767, 856, 922`: replace all 4 occurrences of
   `ClickableSurfaceDefaults.scale(focusedScale = 1.01f)` with
   `ClickableSurfaceDefaults.scale(focusedScale = FetchTheme.focus.subtleScale)`.
   Use replace-all. `grep -c` must report 4 before and 0 after.
4. `FetchTheme` is already imported in all three files (SearchScreen.kt:4,
   AddonManagerScreen.kt:7, TrackingProviderDialogs.kt:69).

### Step 4 — Dead code

1. Confirm `grep -rn "AlwaysCrossfadeTransitionFactory" app/src` lists only lines inside
   `ui/screens/home/AlwaysCrossfadeTransitionFactory.kt`. If anything else refers to it, STOP.
2. Delete `app/src/main/java/com/fetch/tv/ui/screens/home/AlwaysCrossfadeTransitionFactory.kt`.
3. In `ui/screens/home/ModernHomeHero.kt`:
   - Run `grep -n "transitionFactory" ModernHomeHero.kt`. The only hit must be the line
     `import coil3.request.transitionFactory`.
   - Delete that line.
   - Do not touch `import coil3.request.crossfade`. It is used by `.crossfade(true)` further down.

### Step 5 — Splash token

In `ui/theme/MotionFocusTokens.kt`:
1. In `data class FetchMotionDurations`, after `val shimmer: Int`, add a comma and:
   ```kotlin
       /** The startup splash fading out over Home: once per session, so it may exceed the 300ms UI budget. */
       val splashExit: Int
   ```
2. In `FetchMotion.tokens`'s `FetchMotionDurations(...)`, after the `shimmer = ...` argument,
   add a comma and `splashExit = 400`.

### Step 6 — Splash fade, deferred read

In `MainActivity.kt`, find the block quoted in **Problem 5** (it starts at
`val animatedSplashAlpha by animateFloatAsState(`) and replace it, up to and including the closing
`}` of `if (splashAlpha > 0f) { ... }`, with:

```kotlin
                    val splashFade = animateFloatAsState(
                        targetValue = if (showSplash) 1f else 0f,
                        animationSpec = if (showSplash) {
                            snap()
                        } else {
                            tween(
                                durationMillis = FetchMotion.tokens.durations.splashExit,
                                easing = FetchMotion.tokens.easings.out
                            )
                        },
                        label = "startupSplashAlpha"
                    )
                    // Composition only needs to know whether the splash is still up; the alpha is read
                    // in the draw layer, so the fade doesn't recompose this scope on every frame.
                    val splashFadeVisible by remember { derivedStateOf { splashFade.value > 0f } }
                    val splashOnScreen = showSplash || splashFadeVisible
                    LaunchedEffect(splashOnScreen, startupDestination) {
                        if (!splashOnScreen && startupDestination != StartupDestination.ProfileSelection) {
                            focusedSplashColor = null
                            focusedSplashBgUrl = null
                            focusedSplashCacheKey = null
                            focusedSplashTheme = null
                        }
                    }
                    if (splashOnScreen) {
                        com.fetch.tv.ui.components.StartupSplashScreen(
                            profileColorHex = splashBackground.profileColorHex,
                            profileBackgroundUrl = splashBackground.backgroundUrl,
                            backgroundCacheKey = splashBackground.backgroundCacheKey,
                            skipGradient = splashBackground.skipGradient,
                            brandWordmarkRes = splashBackground.brandWordmarkRes,
                            modifier = Modifier.graphicsLayer {
                                alpha = if (showSplash) 1f else splashFade.value
                            }
                        )
                    }
```

Add these imports beside the existing `androidx.compose.animation.core` and
`androidx.compose.runtime` imports:

```kotlin
import androidx.compose.animation.core.snap
import androidx.compose.runtime.derivedStateOf
```

and, beside `import com.fetch.tv.ui.theme.FetchTheme`, add `import com.fetch.tv.ui.theme.FetchMotion`
if it is not already there (plan 004 removed it when the sidebar padding stopped animating;
check with `grep -n "import com.fetch.tv.ui.theme.FetchMotion" app/src/main/java/com/fetch/tv/MainActivity.kt`).

- Keep `import androidx.compose.animation.core.tween` and `animateFloatAsState`.

### Step 7 — Retire tokens with zero usages

1. Re-run the Step 1 script.
2. For each line reading `0`, delete that token. In `MotionFocusTokens.kt` that means both
   its field in the data class and its argument in the `FetchMotion.tokens = ...` / `FetchFocus.tokens = ...`
   constructor call. For `quickTween` and `focusSpring`, delete the helper function.
3. Exceptions, which must never be deleted here:
   - `durations.medium` and `durations.slow` while `mediumTween()` / `slowTween()` exist.
   - `durations.fast` (read by `focusTween()`).
   - `durations.quick` while `quickTween()` exists.
   - Anything plan 001 added (`enter`, `exit`, `move`, `out`, `inOut`, `drawer`, `enterTween`,
     `exitTween`, `moveTween`).
   - `splashExit`.
4. If `accelerate` is deleted and that leaves `CubicBezierEasing` used only by other easings,
   keep the import. Remove an import only if the compiler reports it unused.
5. If `focusSpring` is deleted and `Spring`, `SpringSpec` or `spring` become unused, delete those
   imports (`MotionFocusTokens.kt:7, 8, 10`).
6. Compile (`./gradlew :app:compileFullDebugKotlin`). If it fails on a deleted token, restore that
   token. The grep missed an access path. Note it in the PR.

### Step 8 — Mark the over-budget durations

For each of `medium`, `overlay`, `slow` and `hero` that still exists after Step 7, put this KDoc
directly above its field in `FetchMotionDurations`, filling in the value. For `medium`:

```kotlin
    /** 350ms: over the 300ms UI budget. For new UI use [enter], [exit] or [move]; this remains for existing callers. */
```

- `overlay` (400), `slow` (450) and `hero` (450) get the same wording with their own value.
- `long` already has a KDoc. Leave it.

### Optional (do only with a feel check)

- Replacing the five card `focusedScale = 1.04f` literals in `ui/theme/ComponentTokens.kt` with a
  shared token is fine, because the value stays 1.04f.
- Changing the value (for example to `FetchTheme.focus.scale`, 1.02f) changes the app's most
  frequent focus effect. Do it only after an A/B on a TV:
  - Hold Right along a Home poster row at 1.04 and at 1.02.
  - Record which reads better at 3m viewing distance.
  - Default: keep 1.04.

## Boundaries

- Do NOT touch `ui/components/Skeletons.kt` or its baseline line. Plan 009 owns them.
- Do NOT change any token value except adding `splashExit`. In particular, do not change
  `shimmer` (1200), `medium`, `overlay`, `slow` or `hero`.
- Do NOT change `LightweightCard.kt`. Its default no-scale behaviour is intentional (plan 017 may have added an optional `focusedScale` parameter defaulting to 1f; leave it as is).
- Do NOT touch `longPressInitialDelayMillis`, `longPressRepeatMillis` or `scrollViewportTarget`.
- Do NOT edit `MainActivity.kt` outside the splash block and its imports (`snap`, `derivedStateOf`, and `FetchMotion` if missing).
- Do NOT delete a token whose Step 7 count is not `0`.
- Do NOT add new dependencies.
- If a step doesn't match the code you find (drift since ee8dd84 beyond what plans 001-019
  changed), STOP and report instead of improvising.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes, with the
    `Shimmer.kt` and `PlaceholderShimmer.kt` baseline lines gone.
  - `./gradlew :app:testFullDebugUnitTest --tests '*SearchScreen*'`, if any such test exists
    (`grep -rln "SearchScreen\|AddonManagerScreen\|TrackingProviderDialog" app/src/test`), passes.
  - `grep -rn "2400\|1600" app/src/main/java/com/fetch/tv/ui/components/Shimmer.kt app/src/main/java/com/fetch/tv/ui/components/PlaceholderShimmer.kt` returns nothing.
  - `grep -rn "AlwaysCrossfadeTransitionFactory\|coil3.request.transitionFactory" app/src/main/java` returns nothing.
  - `grep -n "tween(if" app/src/main/java/com/fetch/tv/MainActivity.kt` returns nothing.
- **Feel check** (TV or emulator):
  - Shimmer:
    - Open a screen with loading placeholder cards (Home on a cold start, or slow network).
      The sweep runs at the same speed as the skeleton shimmer.
    - Filter a stream list so `SourceStatusFilterChip` shows its loading shimmer
      (`Modifier.shimmer`). It now sweeps twice as fast as before. It must read as "loading",
      not as flicker.
    - If it reads as flicker, report it. Do not add a second shimmer token without sign-off.
  - Focus scales: recent searches, tracking dialog buttons and Addon Manager rows grow exactly
    as before on focus.
  - Splash:
    - Cold-start the app with the startup splash enabled.
    - At 1x, the splash leaves faster at the start and settles at the end (ease-out), finishing
      in 0.4s.
    - At Animator duration scale 5x, the fade is smooth and Home underneath keeps rendering.
    - In Layout Inspector, the `setContent` scope recomposes about twice during the fade, not
      ~24 times.
  - Reduced motion: at Animator duration scale "Animation off", the splash disappears in one
    frame and shimmers hold still, as before.
- **Done when**:
  - Every token with a zero count in Step 7 is gone, and no other token is.
  - Both shimmers use `durations.shimmer`.
  - 1.02/1.01 focus scales use `FetchTheme.focus`.
  - The dead factory and its import are gone.
  - The splash uses `splashExit` with a deferred alpha read.
  - The build and guard test are green.
