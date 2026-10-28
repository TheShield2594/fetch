# 023 — Show the next-episode autoplay countdown as a linear fill on the Play pill

- **Status**: DONE (build not verified locally: no Android SDK in the session)
- **Commit**: ee8dd84
- **Severity**: LOW
- **Category**: 8. Missed opportunities
- **Estimated scope**: 3 files: `PostPlayOverlay.kt` (~30 lines), `PlayerUiState.kt` (2 constants), `PlayerRuntimeControllerStreams.kt` (2 literals replaced)
- **Depends on**: none (no new motion tokens; the fill is paced by the countdown's own tick)

All paths are relative to `app/src/main/java/com/fetch/tv/`.

## Problem

When the next episode's source is found, a 3-second autoplay countdown starts. It is shown
only as text, in `ui/screens/player/PostPlayOverlay.kt:206-213`:

```kotlin
            val statusText = when {
                !isPlayable && !nextEpisode.unairedMessage.isNullOrBlank() -> nextEpisode.unairedMessage
                mode.searching -> stringResource(R.string.next_episode_finding_source)
                !mode.sourceName.isNullOrBlank() && mode.countdownSec != null ->
                    stringResource(R.string.next_episode_playing_via, mode.sourceName, mode.countdownSec)
                else -> null
            }
```

Nothing on the card shows the auto-advance building up. The Play pill (`:220-252`) is static,
so the switch to the next episode arrives with no visual lead-in.

**Where `countdownSec` comes from.** This is the single source of truth, and no second timer
may be created. `ui/screens/player/PlayerRuntimeControllerStreams.kt:1824-1838`:

```kotlin
                val sourceName = (streamToPlay.name?.takeIf { it.isNotBlank() } ?: streamToPlay.addonName).trim()
                for (remaining in 3 downTo 1) {
                    _uiState.update { current ->
                        val episodeForMode = current.nextEpisode ?: nextInfo
                        current.copy(
                            postPlayMode = PostPlayMode.AutoPlay(
                                nextEpisode = episodeForMode,
                                searching = false,
                                sourceName = sourceName,
                                countdownSec = remaining,
                            ),
                        )
                    }
                    delay(1000)
                }
```

After the loop, `postPlayMode` becomes `null` and the next episode starts. Cancelling (for example Back or
dismiss) cancels this job or clears `postPlayMode` through existing logic. The overlay then exits, or `countdownSec` becomes `null`.

**Still-watching countdown (decided: leave it as text).**

- `PostPlayOverlay.kt:294-299` shows `StillWatching.countdownSec`, a 60 s countdown
  (`PlayerRuntimeControllerStillWatching.kt:8, 38-48`).
- That countdown ends in `exitFromStillWatching()`: the player **exits**; it does not advance.
- A fill on the focused "Continue" pill would wrongly suggest Continue will happen by itself.
- Playback is paused there, so there is no decode contention either way.
- This plan does not add a fill to the still-watching prompt.

## Target

- On the AutoPlay card's Play pill (`PostPlayOverlay.kt:220-252`), draw a fill in `drawBehind`
  behind the pill's border and content. It is clipped by the pill's existing `CircleShape` and
  grows from the start edge (from the right in RTL).
- Colour: `Color.White.copy(alpha = 0.16f)`. This is just above the pill's 0.2-alpha hairline
  border tone and matches the focused-pill fill (`PostPlayOverlay.kt:357`,
  `focusedContainerColor = Color.White.copy(alpha = 0.15f)`). It is a feel-check item (A/B
  below).
- **Driven by `countdownSec`, with no independent timer.** One `Animatable<Float>` holds the
  elapsed fraction. On each new `countdownSec = n` (total `N = AUTO_PLAY_COUNTDOWN_SECONDS`):
  - `tickStart = (N - n) / N` and `tickEnd = (N - n + 1) / N`.
  - If the fill is behind `tickStart` (for example the card appeared mid-countdown), snap it
    forward to `tickStart`. Never jump backwards.
  - Full motion: `animateTo(tickEnd, tween(POST_PLAY_COUNTDOWN_TICK_MS, easing = LinearEasing))`.
    Linear is correct for constant progress. Each tick re-syncs to the controller, so drift
    cannot accumulate past one tick.
  - Reduced motion (`FetchTheme.fullMotion == false`): `snapTo(tickEnd)`, a stepped fill, one
    step per second. This keeps the information and drops continuous motion and per-frame
    redraws on low-RAM devices.
  - `countdownSec == null` (searching, unaired, or cancelled): `snapTo(0f)` and draw nothing.
- The fill is drawn only when `isPlayable && mode.countdownSec != null`.
- `drawBehind` reads `fill.value` in the draw phase, so the pill redraws without recomposing.
- **Shared constants, so the overlay and the controller cannot disagree**, in `PlayerUiState.kt`:
  - `internal const val AUTO_PLAY_COUNTDOWN_SECONDS = 3`
  - `internal const val POST_PLAY_COUNTDOWN_TICK_MS = 1_000`

  The controller loop uses both. Behaviour is identical: 3 → 1, 1000 ms apart.

## Repo conventions to follow

- Draw-phase fill: `ui/screens/player/PlayerProgressFill.kt:15-37` (`drawBehind`, width =
  `size.width * fraction`).
- Reduced motion: `ui/screens/player/PlaybackDetailsBar.kt:95-105` (`val fullMotion = FetchTheme.fullMotion`).
- `ThemeLiteralGuardTest` counts `tween(<digits>`; `tween(POST_PLAY_COUNTDOWN_TICK_MS, ...)`
  does not match. The tick period is a domain constant shared with the controller, not a motion
  token, which is why it lives next to `PostPlayMode` rather than in `ui/theme`. None of the
  three files is in `app/src/test/resources/theme-literals-baseline.txt`.

## Steps

Plan 016 also edits `PostPlayOverlay.kt` (the card transition at `:96-101`), so line numbers
here may have shifted. Find each excerpt by its text. If the text itself differs, stop and report.

1. In `ui/screens/player/PlayerUiState.kt`, directly after the closing `}` of
   `sealed interface PostPlayMode` (`:32-59`), add:
   ```kotlin
   /** Seconds the autoplay countdown runs once the next episode's source is chosen. */
   internal const val AUTO_PLAY_COUNTDOWN_SECONDS = 3
   /** Interval between autoplay countdown ticks; the up-next fill animates over each one. */
   internal const val POST_PLAY_COUNTDOWN_TICK_MS = 1_000
   ```
2. In `ui/screens/player/PlayerRuntimeControllerStreams.kt`:
   - Replace `for (remaining in 3 downTo 1) {` (`:1826`) with
     `for (remaining in AUTO_PLAY_COUNTDOWN_SECONDS downTo 1) {`.
   - In the same loop, replace `delay(1000)` (`:1838`) with
     `delay(POST_PLAY_COUNTDOWN_TICK_MS.toLong())`.
   - Change nothing else. The file is in the same package, so no import is needed.
3. In `ui/screens/player/PostPlayOverlay.kt`, inside `AutoPlayBody`, directly after
   `val isPlayable = nextEpisode.hasAired` (`:178`), add:
   ```kotlin
       // Elapsed share of the autoplay countdown, paced by the controller's own ticks.
       val countdownFill = remember { Animatable(0f) }
       val fullMotion = FetchTheme.fullMotion
       val countdownSec = mode.countdownSec
       LaunchedEffect(countdownSec) {
           if (countdownSec == null) {
               countdownFill.snapTo(0f)
               return@LaunchedEffect
           }
           val total = AUTO_PLAY_COUNTDOWN_SECONDS.toFloat()
           val tickStart = ((total - countdownSec) / total).coerceIn(0f, 1f)
           val tickEnd = ((total - countdownSec + 1) / total).coerceIn(0f, 1f)
           if (countdownFill.value < tickStart) countdownFill.snapTo(tickStart)
           if (fullMotion) {
               countdownFill.animateTo(tickEnd, tween(POST_PLAY_COUNTDOWN_TICK_MS, easing = LinearEasing))
           } else {
               countdownFill.snapTo(tickEnd)
           }
       }
       val showCountdownFill = isPlayable && countdownSec != null
   ```
4. In the Play pill `Row` (`:220-228`), change its modifier from:
   ```kotlin
               modifier = Modifier
                   .padding(start = FetchTheme.spacing.sm)
                   .clip(CircleShape)
                   .border(
                       BorderStroke(FetchTheme.spacing.hairline, Color.White.copy(alpha = 0.2f)),
                       CircleShape,
                   )
                   .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs),
   ```
   to:
   ```kotlin
               modifier = Modifier
                   .padding(start = FetchTheme.spacing.sm)
                   .clip(CircleShape)
                   // Autoplay countdown, read while drawing only.
                   .drawBehind {
                       if (!showCountdownFill) return@drawBehind
                       val fraction = countdownFill.value.coerceIn(0f, 1f)
                       if (fraction <= 0f) return@drawBehind
                       val fillWidth = size.width * fraction
                       val left = if (layoutDirection == LayoutDirection.Rtl) size.width - fillWidth else 0f
                       drawRect(
                           color = Color.White.copy(alpha = 0.16f),
                           topLeft = Offset(left, 0f),
                           size = Size(fillWidth, size.height),
                       )
                   }
                   .border(
                       BorderStroke(FetchTheme.spacing.hairline, Color.White.copy(alpha = 0.2f)),
                       CircleShape,
                   )
                   .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs),
   ```
5. Imports in `PostPlayOverlay.kt`. Add any that are missing:
   - `androidx.compose.animation.core.Animatable`
   - `androidx.compose.animation.core.LinearEasing`
   - `androidx.compose.runtime.LaunchedEffect`
   - `androidx.compose.ui.draw.drawBehind`
   - `androidx.compose.ui.geometry.Offset`
   - `androidx.compose.ui.geometry.Size`

   `LayoutDirection` and `tween` are already imported (`:59`, `:12`).

## Boundaries

- Do NOT add a fill to `StillWatchingBody` (see the decision above).
- Do NOT change cancellation, focus or timing logic in `PlayerRuntimeControllerStreams.kt`
  beyond the two literal replacements in step 2. Do NOT touch
  `PlayerRuntimeControllerStillWatching.kt`.
- Do NOT change the countdown text, the card layout, or the overlay's enter/exit transitions.
- Do NOT start any `delay`-based or `while`-loop timer in the overlay.
- If the code does not match the excerpts (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes with no
    baseline change.
  - `./gradlew :app:testFullDebugUnitTest --tests '*PostPlayModeTest*' --tests '*PlayerNextEpisodeRulesTest*' --tests '*AutoplaySessionCountTest*'`
    passes. These are the existing tests around `PostPlayMode` and autoplay.
- **Feel check** (TV or emulator, a series episode with autoplay on, near its end so the
  next-episode card appears):
  - Once "Playing via <source> in 3…" shows, the Play pill fills from its start edge at a
    constant speed and reaches full exactly as the next episode starts. There must be no pause
    at each second and no jump backwards.
  - Press Back or dismiss during the countdown: the card leaves and nothing keeps filling.
  - While the source is still being searched ("Finding source…"), the pill is empty.
  - Open and close the Audio panel during the countdown. The card hides while a panel is open,
    because `postPlayOverlayMode` is null then. When the card reappears, the fill resumes at
    the current second's position, not from zero. If the countdown finished meanwhile, the next
    episode simply starts.
  - A/B the fill alpha: 0.16 against 0.24. Pick the value that reads clearly on a bright
    credits frame without overpowering the "Play" label. Keep 0.16 unless 0.24 is clearly
    better.
  - Animator duration scale → "Animation off": the fill steps by a third each second.
  - Switch the app to an RTL language: the fill grows from the right.
  - The still-watching prompt is unchanged (text countdown only).
- **Done when**:
  - `grep -n "3 downTo 1\|delay(1000)" PlayerRuntimeControllerStreams.kt` no longer shows the
    autoplay loop lines.
  - The build and tests are green.
  - The fill completes in sync with the auto-advance.
