# 016 — Gate slides, scales, shakes and marquees on reduced motion

- **Status**: DONE (build not verified locally: no Android SDK in the session; all 15 sites applied)
- **Commit**: ee8dd84
- **Severity**: MEDIUM
- **Category**: Accessibility (reduced motion), and also Performance, because `fullMotion` is false on low-RAM devices
- **Estimated scope**: 17 files, small edits in each (about 3-15 lines per site)
- **Depends on**: 001 (`enterTween`, `exitTween`, `durations.enter`/`exit`, `easings.drawer`). **Execute after 005, 008, 010 and 012**, which retime some of the same call sites.

## Problem

`FetchTheme.fullMotion` (`ui/theme/Theme.kt:186-189`) is false when the system animator
scale is 0 **or** the device is low on RAM. Its KDoc says decorative motion then "holds
still or cuts straight to its end state". Only 10 files read it. The sites below still
move, scale, shake or scroll for users who turned animations off, and they cost frames on
the low-RAM boxes the flag is meant to protect.

Two notes on scope:

- When the animator scale is 0, Compose `tween`s finish instantly, so a slide becomes a
  cut. The **low-RAM** case still runs the full movement.
- Some motion ignores the animator scale entirely: the ambient Ken Burns effect on the
  frame clock, `basicMarquee`, and `FocusScrollingText`'s timed loop.

Reduced motion in this repo means **drop movement and scale, keep short opacity fades or
snap**. It never means removing feedback.

### Sites (verified at ee8dd84; paths under `app/src/main/java/com/fetch/tv/`)

| # | Site | Current motion |
|---|---|---|
| A | `ui/screens/player/PlayerSidePanel.kt:61-66` | panel `slideInHorizontally`/`slideOutHorizontally`, `tween(fast)`, default easing |
| B | `ui/screens/player/PlayerScreen.kt:1843-1853` | party panel full-width slide from the right |
| C | `ui/screens/player/PlayerScreen.kt:1907-1917` | subtitle style panel full-height slide from the top |
| D | `ui/screens/player/PlayerScreen.kt:2897-2906` | more-actions row slide + fade |
| E | `ui/screens/player/PostPlayOverlay.kt:96-101` | post-play card slide + fade |
| F | `ui/screens/detail/CommentsSection.kt:816-828` | comment pager slide + fade with **default springs** |
| G | `ui/components/FocusMarqueeText.kt:40-45` | `basicMarquee` on every focused card title |
| H | `ui/components/StreamBadgeChips.kt:124` | `basicMarquee(iterations = Int.MAX_VALUE)`, which loops for as long as focus stays |
| I | `ui/components/FocusScrollingText.kt:86-113` | vertical auto-scroll of descriptions |
| J | `ui/components/ContentCard.kt:390`, `ui/components/GridContentCard.kt:191`, `ui/components/CollectionRowSection.kt:285` | poster focus scale 1.04 |
| K | `ui/screens/ambient/AmbientSlideshow.kt:135-146, 178-181, 201-204, 236-239` | Ken Burns zoom/pan and title/genre rise |
| L | `ui/components/SourceStatusFilterChip.kt:62-82` | error shake (`translationX` ±3px) |
| M | `ui/screens/party/PartyScreen.kt:196-208` | join-error shake (keyframes ±14dp) |
| N | `ui/screens/profile/ProfileSelectionScreen.kt:2652-2657` | PIN error shake (±22px) |
| O | `ui/screens/home/HomeScreen.kt:333-344` | first Home reveal `slideInVertically(it / 24)` |

The exemplar for gating is `ui/screens/player/PlaybackDetailsBar.kt:95-104`:

```kotlin
    val fullMotion = FetchTheme.fullMotion
    AnimatedVisibility(
        visible = visible,
        enter = if (fullMotion) {
            fadeIn(tween(FetchMotion.tokens.durations.fast)) +
                slideInVertically(tween(FetchMotion.tokens.durations.fast)) { it / 2 }
        } else {
            EnterTransition.None
        },
        exit = if (fullMotion) fadeOut(tween(FetchMotion.tokens.durations.quick)) else ExitTransition.None,
```

This plan keeps a short fade in the reduced branch (`FetchMotion.enterTween()` /
`exitTween()`) rather than `None`, so panels still visibly arrive and leave.

## Target

The rule applied everywhere:

```kotlin
val fullMotion = FetchTheme.fullMotion
enter = if (fullMotion) <existing slide/scale enter> else fadeIn(FetchMotion.enterTween())
exit  = if (fullMotion) <existing slide/scale exit>  else fadeOut(FetchMotion.exitTween())
```

Where the full-motion branch already has a fade (D, E, F, O), the reduced branch is just
that fade term. Decorative loops and shakes return early when `!fullMotion`. Scale targets
use `FetchMotion.tokens.reducedMotionScale` (1f, currently unused).

Exact per-site edits are in **Steps**.

## Repo conventions to follow

- Read `FetchTheme.fullMotion` once in composition into `val fullMotion`. It is a
  `@Composable @ReadOnlyComposable` getter, so it cannot be called inside `transitionSpec`
  lambdas, `LaunchedEffect` bodies or local `suspend fun`s.
- Inside long-running effects, read it through `rememberUpdatedState`.
- Arguments to a composable call (`enter = ...`) are evaluated in composition, so
  `FetchTheme.fullMotion` can be read inline there.
- Exemplar: `ui/screens/player/PlaybackDetailsBar.kt:95-104`. For the effect-gating style
  see `ui/components/PlaceholderShimmer.kt:33`.
- No literal durations. `ThemeLiteralGuardTest` counts `tween(<digits>`. No step here adds
  or removes one, so the baseline file is unchanged.

## Steps

**Drift rule for this plan.** Plans 005, 008, 010 and 012 run first and may already have
changed the *durations or easings* of the full-motion branches below. In that case, keep
their specs exactly and only add the `fullMotion` branch. If the *structure* differs (the
slide is gone, or the call moved), STOP and report that site. Do not improvise.

### A. `ui/screens/player/PlayerSidePanel.kt`

1. Replace lines 61-66:
   ```kotlin
           AnimatedVisibility(
               visible = visible,
               enter = slideInHorizontally(animationSpec = tween(FetchMotion.tokens.durations.fast), initialOffsetX = { it }),
               exit = slideOutHorizontally(animationSpec = tween(FetchMotion.tokens.durations.fast), targetOffsetX = { it }),
               modifier = Modifier.align(Alignment.CenterEnd)
           ) {
   ```
   with
   ```kotlin
           val fullMotion = FetchTheme.fullMotion
           AnimatedVisibility(
               visible = visible,
               // Drawer curve for the slide; reduced motion fades the panel in place instead.
               enter = if (fullMotion) {
                   slideInHorizontally(
                       animationSpec = tween(FetchMotion.tokens.durations.enter, easing = FetchMotion.tokens.easings.drawer),
                       initialOffsetX = { it }
                   )
               } else {
                   fadeIn(animationSpec = FetchMotion.enterTween())
               },
               exit = if (fullMotion) {
                   slideOutHorizontally(
                       animationSpec = tween(FetchMotion.tokens.durations.exit, easing = FetchMotion.tokens.easings.drawer),
                       targetOffsetX = { it }
                   )
               } else {
                   fadeOut(animationSpec = FetchMotion.exitTween())
               },
               modifier = Modifier.align(Alignment.CenterEnd)
           ) {
   ```
   The imports (`fadeIn`, `fadeOut`, `tween`, `FetchTheme`, `FetchMotion`) already exist.
   Leave the scrim `AnimatedVisibility` at :51-60 unchanged.

### B, C, D. `ui/screens/player/PlayerScreen.kt`

2. **B, the party panel (:1843-1853).** Replace the `enter = slideInHorizontally(...)` and
   `exit = slideOutHorizontally(...)` arguments with:
   ```kotlin
               enter = if (FetchTheme.fullMotion) {
                   slideInHorizontally(
                       animationSpec = tween(FetchMotion.tokens.durations.fast),
                       initialOffsetX = { it }
                   )
               } else {
                   fadeIn(animationSpec = FetchMotion.enterTween())
               },
               exit = if (FetchTheme.fullMotion) {
                   slideOutHorizontally(
                       animationSpec = tween(FetchMotion.tokens.durations.fast),
                       targetOffsetX = { it }
                   )
               } else {
                   fadeOut(animationSpec = FetchMotion.exitTween())
               }
   ```
   If an earlier plan changed the `tween(...)` inside the slides, keep that.

3. **C, the subtitle style panel (:1907-1917).** Same shape, with the vertical slide:
   ```kotlin
               enter = if (FetchTheme.fullMotion) {
                   slideInVertically(
                       animationSpec = tween(FetchMotion.tokens.durations.fast),
                       initialOffsetY = { -it }
                   )
               } else {
                   fadeIn(animationSpec = FetchMotion.enterTween())
               },
               exit = if (FetchTheme.fullMotion) {
                   slideOutVertically(
                       animationSpec = tween(FetchMotion.tokens.durations.fast),
                       targetOffsetY = { -it }
                   )
               } else {
                   fadeOut(animationSpec = FetchMotion.exitTween())
               }
   ```

4. **D, the more-actions row (:2897-2906, inside `PlayerControlsOverlay`).** Replace with:
   ```kotlin
                       enter = if (FetchTheme.fullMotion) {
                           slideInHorizontally(
                               animationSpec = tween(FetchMotion.tokens.durations.fast),
                               initialOffsetX = { it / 2 }
                           ) + fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast))
                       } else {
                           fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast))
                       },
                       exit = if (FetchTheme.fullMotion) {
                           slideOutHorizontally(
                               animationSpec = tween(FetchMotion.tokens.durations.fast),
                               targetOffsetX = { it / 2 }
                           ) + fadeOut(animationSpec = tween(FetchMotion.tokens.durations.fast))
                       } else {
                           fadeOut(animationSpec = tween(FetchMotion.tokens.durations.fast))
                       }
   ```
   The reduced branch reuses whatever fade spec the full branch has.

### E. `ui/screens/player/PostPlayOverlay.kt`

5. Replace :98-101 (`enter = ...` and `exit = ...` of `transition.AnimatedVisibility`) with:
   ```kotlin
           enter = if (FetchTheme.fullMotion) {
               slideInHorizontally(animationSpec = tween(FetchMotion.tokens.durations.fast), initialOffsetX = { it / 2 }) +
                   fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast))
           } else {
               fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast))
           },
           exit = if (FetchTheme.fullMotion) {
               slideOutHorizontally(animationSpec = tween(FetchMotion.tokens.durations.fast), targetOffsetX = { it / 2 }) +
                   fadeOut(animationSpec = tween(FetchMotion.tokens.durations.fast))
           } else {
               fadeOut(animationSpec = tween(FetchMotion.tokens.durations.fast))
           },
   ```

### F. `ui/screens/detail/CommentsSection.kt` (tokenize and gate)

6. Immediately before `            AnimatedContent(` at :816, insert
   `            val fullMotion = FetchTheme.fullMotion`. Then replace the `transitionSpec`
   body (:818-829) with:
   ```kotlin
                   transitionSpec = {
                       val enterFade = fadeIn(FetchMotion.enterTween())
                       val exitFade = fadeOut(FetchMotion.exitTween())
                       when {
                           !fullMotion || transitionDirection == 0 -> enterFade togetherWith exitFade
                           transitionDirection > 0 -> {
                               slideInHorizontally(FetchMotion.enterTween()) { it / 5 } + enterFade togetherWith
                                   slideOutHorizontally(FetchMotion.exitTween()) { -it / 5 } + exitFade
                           }
                           else -> {
                               slideInHorizontally(FetchMotion.enterTween()) { -it / 5 } + enterFade togetherWith
                                   slideOutHorizontally(FetchMotion.exitTween()) { it / 5 } + exitFade
                           }
                       }
                   },
   ```
   Add `import com.fetch.tv.ui.theme.FetchMotion` after line 3.

### G. `ui/components/FocusMarqueeText.kt`

7. Replace :40-45:
   ```kotlin
       val textModifier = if (focused) {
           modifier.basicMarquee(iterations = MarqueeIterations, velocity = MarqueeVelocity)
       } else {
           modifier
       }
       val textOverflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis
   ```
   with
   ```kotlin
       // Reduced motion keeps the ellipsized title still instead of scrolling it.
       val marquee = focused && FetchTheme.fullMotion
       val textModifier = if (marquee) {
           modifier.basicMarquee(iterations = MarqueeIterations, velocity = MarqueeVelocity)
       } else {
           modifier
       }
       val textOverflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis
   ```
   Add `import com.fetch.tv.ui.theme.FetchTheme`.

### H. `ui/components/StreamBadgeChips.kt`

8. Replace line 124:
   ```kotlin
               .then(if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = MarqueeVelocity, spacing = MarqueeSpacing(36.dp)) else Modifier)
   ```
   with
   ```kotlin
               .then(
                   if (focused && FetchTheme.fullMotion) {
                       Modifier.basicMarquee(iterations = MarqueeIterations, velocity = MarqueeVelocity, spacing = MarqueeSpacing(36.dp))
                   } else {
                       Modifier
                   }
               )
   ```
   - `MarqueeIterations` is the `internal const` (3) in `FocusMarqueeText.kt:18`, in the
     same package, so no import is needed.
   - `FetchTheme` is already imported (:5).
   - Capping the iterations stops a badge row that keeps focus from looping forever. This is
     the same rule `FocusMarqueeTextTest` enforces for titles.

### I. `ui/components/FocusScrollingText.kt`

9. Replace :85-113 (the comment line and the `LaunchedEffect(focused) { ... }` block) with:
   ```kotlin
       // Reduced motion jumps a page at a time instead of scrolling, so the rest of the text is still reachable.
       val fullMotion = FetchTheme.fullMotion
       // Keyed on focus (and the motion setting) only, so the wait always starts when the card is focused instead of restarting every time layout revises the overflow.
       LaunchedEffect(focused, fullMotion) {
           offsetPx.snapTo(0f)
           if (!focused) return@LaunchedEffect

           val revealPxPerSecond = with(density) { ScrollVelocity.toPx() }
           val returnPxPerSecond = with(density) { ReturnVelocity.toPx() }
           // Each pass ends back at the top, so finishing the last one leaves the description resting at the start.
           repeat(MaxScrollCycles) {
               delay(startDelayMillis)
               val distancePx = currentOverflowPx
               if (distancePx <= 0) return@LaunchedEffect
               if (fullMotion) {
                   offsetPx.animateTo(
                       targetValue = distancePx.toFloat(),
                       animationSpec = tween(
                           durationMillis = focusScrollDurationMillis(distancePx, revealPxPerSecond),
                           easing = LinearEasing
                       )
                   )
               } else {
                   offsetPx.snapTo(distancePx.toFloat())
               }
               delay(EndHoldMillis)
               if (fullMotion) {
                   offsetPx.animateTo(
                       targetValue = 0f,
                       animationSpec = tween(
                           durationMillis = focusScrollDurationMillis(distancePx, returnPxPerSecond),
                           easing = LinearEasing
                       )
                   )
               } else {
                   offsetPx.snapTo(0f)
               }
           }
       }
   ```
   Add `import com.fetch.tv.ui.theme.FetchTheme`.

### J. Poster focus scale

10. `ui/components/ContentCard.kt:390` and `ui/components/GridContentCard.kt:191`: replace
    `scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)` with
    ```kotlin
                scale = CardDefaults.scale(
                    focusedScale = if (FetchTheme.fullMotion) posterCardStyle.focusedScale else FetchMotion.tokens.reducedMotionScale
                )
    ```
    - GridContentCard.kt imports only `FetchTheme` (:3). Add
      `import com.fetch.tv.ui.theme.FetchMotion`.
    - ContentCard.kt already imports both.
11. `ui/components/CollectionRowSection.kt:285`: the same replacement, indented one level
    less. It already imports both.
    - If plan 017 has already moved these cards onto `LightweightCard`, apply the same
      ternary to the `focusedScale = ...` argument that plan 017 introduced instead.

### K. `ui/screens/ambient/AmbientSlideshow.kt`

The zoom/pan runs on `withFrameMillis` deliberately (comment at :136-138), so it ignores
the animator scale and keeps moving even with animations off. Keep that timing for full
motion. Under reduced motion, show a still, unzoomed backdrop and let only the fades run.

12. After line 99 (`    val latestSlides by rememberUpdatedState(slides)`), insert:
    ```kotlin
        // Reduced motion: a still, unzoomed backdrop and titles that only fade.
        val fullMotion = FetchTheme.fullMotion
        val currentFullMotion by rememberUpdatedState(fullMotion)
    ```
13. Replace the motion job start, `val motionJob = launch {` (:135), with
    `val motionJob = if (!currentFullMotion) null else launch {`. Leave the block body
    (:136-145) and its closing `}` (:146) unchanged.
14. Replace `                motionJob.cancel()` (:159) with `                motionJob?.cancel()`.
15. Replace `applyAmbientMotion(motion, motionProgress.value)` (:180) with
    `if (fullMotion) applyAmbientMotion(motion, motionProgress.value)`.
16. Replace `translationY = (1f - titleAlpha.value) * titleOffsetPx` (:203) with
    `translationY = if (fullMotion) (1f - titleAlpha.value) * titleOffsetPx else 0f`.
17. Replace `translationY = (1f - genresAlpha.value) * genresOffsetPx` (:238) with
    `translationY = if (fullMotion) (1f - genresAlpha.value) * genresOffsetPx else 0f`.
18. Add `import com.fetch.tv.ui.theme.FetchTheme`. `ambient/AmbientMotion.kt` needs no
    change.

### L. `ui/components/SourceStatusFilterChip.kt`

19. Before `    LaunchedEffect(isError) {` (:62), insert
    `    val fullMotion = FetchTheme.fullMotion`. After the second `alphaPulse.animateTo(...)`
    call (it ends at :76) and before the comment
    `        // Two gentle shakes ...` (:78), insert:
    ```kotlin

            // Reduced motion keeps the pulse and the error colours, without moving the chip.
            if (!fullMotion) return@LaunchedEffect
    ```

### M. `ui/screens/party/PartyScreen.kt`

20. After `    val shake = remember { Animatable(0f) }` (:180), insert
    `    val fullMotion = FetchTheme.fullMotion`. In `suspend fun reject(...)` (:196), after
    `        error = result`, insert:
    ```kotlin
            // Reduced motion: the error message below the code is the feedback; the code doesn't shake.
            if (!fullMotion) return
    ```

### N. `ui/screens/profile/ProfileSelectionScreen.kt`

21. After `    val shakeOffset = remember(state) { androidx.compose.animation.core.Animatable(0f) }`
    (:2582), insert `    val fullMotion = FetchTheme.fullMotion`. In
    `suspend fun playErrorAnimation()` (:2652), after `        shakeOffset.snapTo(0f)`, insert:
    ```kotlin
            // Reduced motion: the red PIN boxes and the message carry the error; the boxes don't shake.
            if (!fullMotion) return
    ```
    - Do not touch `tween(42)` on the next line. It is the literal counted by the baseline
      line `com/fetch/tv/ui/screens/profile/ProfileSelectionScreen.kt|DURATION|1`, and that
      count must stay 1.
    - Do not touch the `cursorAlpha` block (:2583-2594). Plan 021 moves it.

### O. `ui/screens/home/HomeScreen.kt`

22. Replace the `else` branch of `enter = if (hasShownInitialHomeContent) { ... } else { ... }`
    (:337-342):
    ```kotlin
                            } else {
                                fadeIn(animationSpec = tween(FetchMotion.tokens.durations.medium)) +
                                    slideInVertically(
                                        initialOffsetY = { it / 24 },
                                        animationSpec = tween(FetchMotion.tokens.durations.medium)
                                    )
                            }
    ```
    with
    ```kotlin
                            } else if (FetchTheme.fullMotion) {
                                fadeIn(animationSpec = tween(FetchMotion.tokens.durations.medium)) +
                                    slideInVertically(
                                        initialOffsetY = { it / 24 },
                                        animationSpec = tween(FetchMotion.tokens.durations.medium)
                                    )
                            } else {
                                fadeIn(animationSpec = tween(FetchMotion.tokens.durations.medium))
                            }
    ```
    If an earlier plan retimed these specs, keep its specs in both branches.

## Boundaries

- Only make the gating edits listed here. Several of these files are also edited by plans
  005, 008, 010 and 012 (player panels, comments, Home reveal) and by 009, 015, 017 and 021
  (ContentCard, Skeletons, ProfileSelectionScreen, party, ambient host). Do not retime,
  re-ease or restructure anything those plans own.
- Do NOT touch `ambient/AmbientModeHost.kt`. Plan 021(b) owns its exit.
- Do NOT touch `PartyUi.kt` or `PartyCode.kt`. Plan 021 owns their scale and alpha reads.
- Do NOT remove the scrim fades in PlayerSidePanel or PlayerScreen.
- Do NOT change marquee velocity or the `FocusScrollingText` timing constants.
- Do NOT add dependencies.
- If a site's structure does not match its excerpt, STOP and report that site. Continue
  with the others.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes. The
    baseline is unchanged.
  - `./gradlew :app:testFullDebugUnitTest --tests '*FocusMarqueeTextTest*' --tests '*FocusScrollingTextTest*'`
    passes.
- **Feel check, full motion** (Animator duration scale 1x, then 5x): every site behaves
  exactly as before, except:
  - A: the side panels (Audio, Subtitles, Sources) slide in on the drawer curve in 200ms
    and out in 150ms. At 5x, entry decelerates hard into place. A/B against ee8dd84: if 150ms
    reads as too abrupt for a 640dp panel, report it with the observation (do not pick a
    new number).
  - F: the comment pager slides with a crisp ease-out, not a spring settle.
  - H: a focused stream's badge row scrolls 3 times and stops.
- **Feel check, reduced motion**:
  - Set Developer options → "Animation off", relaunch, and walk every site:
    - Panels and the post-play card appear and disappear without sliding.
    - Comments change without sliding.
    - Card titles stay ellipsized when focused.
    - Descriptions jump to the hidden text after the delay and jump back.
    - Posters do not grow on focus. The focus ring still shows.
    - Ambient mode shows a still backdrop with titles fading in place.
    - A failing source chip pulses but does not shake.
    - A wrong party code and a wrong PIN show their error without shaking.
    - The first Home load fades in without rising.
  - To test the low-RAM path with animations on (fades still visible), force
    `rememberFullMotionAllowed()` false in a local debug build, or use a low-RAM emulator
    image (`ro.config.low_ram=true`). Confirm the reduced branches fade (200ms in, 150ms out)
    rather than cut.
- **Done when**:
  - Every site A-O has a `fullMotion` branch.
  - `grep -rn "fullMotion" app/src/main/java/com/fetch/tv/ui/screens/ambient` is non-empty.
  - All three gradle commands are green.
