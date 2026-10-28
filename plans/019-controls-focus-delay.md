# 019 — Focus play/pause as soon as the controls attach, not after a fixed 250 ms

- **Status**: DONE (build not verified locally: no Android SDK in the session)
- **Commit**: ee8dd84
- **Severity**: LOW
- **Category**: 4. Interruptibility
- **Estimated scope**: 1 file (`PlayerScreen.kt`, ~25 lines)
- **Depends on**: none (no motion tokens used)

All paths are relative to `app/src/main/java/com/fetch/tv/`.

## Problem

Every time the player controls appear, `ui/screens/player/PlayerScreen.kt:594-650` waits a
fixed 250 ms before moving focus to play/pause, or back to the panel button the user came
from. Excerpt `:613-631`:

```kotlin
        if (uiState.showControls && !uiState.showEpisodesPanel && !uiState.showSourcesPanel &&
            !uiState.showQueuePanel && !uiState.showAudioOverlay && !uiState.showSubtitleOverlay &&
            !uiState.showSubtitleStylePanel && !uiState.showSubtitleDelayOverlay &&
            !uiState.showSubtitleTimingDialog &&
            !uiState.showSpeedDialog
        ) {
            // Wait for AnimatedVisibility animation to complete before focusing play/pause button
            kotlinx.coroutines.delay(250)
            val returnTo = panelReturnFocus
            panelReturnFocus = null
            val restored = returnTo != null &&
                runCatching { returnTo.requestFocus(androidx.compose.ui.focus.FocusDirection.Enter) }.getOrDefault(false)
            if (!restored) {
                try {
                    playPauseFocusRequester.requestFocus()
                } catch (e: Exception) {
                    // Focus requester may not be ready yet
                }
            }
        }
```

The controls fade is 180 ms (`:1557-1561`):

```kotlin
        AnimatedVisibility(
            visible = playerControlsVisible,
            enter = fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast)),
            exit = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.fast))
        ) {
```

**Why the delay exists.** `git log -S "delay(250)"` traces it to the initial import
(`ea93f54`), with only the comment as rationale. The real constraint is that
`playPauseFocusRequester` and the panel-button requesters live inside that
`AnimatedVisibility`'s content (`PlayerControlsOverlay`, `:1564`). They are not attached until
that content has been composed and applied.

`AnimatedVisibility` composes its content in the same composition in which `visible` turns
true. The content is then laid out on the next frame, while it is still at alpha ≈ 0. Waiting
for the whole fade, plus 70 ms more, is not needed.

**What goes wrong during the 250 ms.** Focus is still on the root container
(`containerFocusRequester`). With controls visible, the container's key handler returns `false`
for D-pad Left/Right (`:923-941`, "Let focus system handle navigation when controls are
visible"). The press is resolved by the default focus search from the container, so it can
land on any control. When the delay ends, focus is then yanked to play/pause, discarding what
the user did.

This runs on every controls show (tens of times a session), and on every panel close, through
`panelReturnFocus`.

## Target

- Replace `delay(250)` with frame-based waiting: wait **one frame** (`withFrameNanos {}`), then
  request focus. If the request returns `false` (target not attached or not placed yet), retry
  once per frame, up to 4 frames in total. That is about 67 ms at 60 Hz, or about 167 ms at a
  24 Hz AFR refresh, which is still under the old 250 ms.
- **Keep the `panelReturnFocus` behaviour exactly**:
  1. Read `panelReturnFocus` into `returnTo` and clear it, as today.
  2. If `returnTo != null`, try `returnTo.requestFocus(FocusDirection.Enter)` with the
     frame-retry.
  3. Only if that never succeeds (or `returnTo` was null), try
     `playPauseFocusRequester.requestFocus()` with the same frame-retry.
  4. The `else if (!uiState.showControls)` branch (`:632-649`) is untouched.
- `FocusRequester.requestFocus()` and `requestFocus(FocusDirection)` return `Boolean` in this
  Compose version. The existing helper `ui/screens/detail/FocusRestoreUtils.kt:6-18` relies on
  that (`val requested = runCatching { requestFocus() }.getOrDefault(false)`).

## Repo conventions to follow

- Frame-based focus retry: `ui/screens/detail/FocusRestoreUtils.kt:6-18`
  (`requestFocusAfterFrames`: `withFrameNanos { }`, then up to 4 `requestFocus()` attempts one
  frame apart). It only works for the no-argument `requestFocus()`, so add a small private
  variant that takes the request as a lambda. That lets the `FocusDirection.Enter` restore use
  it too.
- Wrap focus calls in `runCatching` as the file already does (an unattached requester can
  throw on older Compose).

## Steps

`PlayerScreen.kt` is also edited by plans 005, 008 and 016. Find each excerpt by its text, not by
line number. If it is not there, stop and report.

1. At file level in `PlayerScreen.kt`, next to `private val SUBTITLE_CONTROLS_GAP = 8.dp`
   (`:2312`), add:
   ```kotlin
   /** Frames to keep retrying focus while freshly shown controls attach (about 67 ms at 60 Hz). */
   private const val CONTROLS_FOCUS_MAX_FRAMES = 4

   /**
    * Runs [request] on each of the next [CONTROLS_FOCUS_MAX_FRAMES] frames until it returns true.
    * The controls' content is composed in the same pass that shows it, so the target is normally
    * ready after one frame, while the fade-in is still running.
    */
   private suspend fun requestFocusWithinFrames(request: () -> Boolean): Boolean {
       repeat(CONTROLS_FOCUS_MAX_FRAMES) {
           withFrameNanos { }
           if (runCatching(request).getOrDefault(false)) return true
       }
       return false
   }
   ```
2. In the `LaunchedEffect` at `:594`, replace these lines (from the excerpt above):
   ```kotlin
               // Wait for AnimatedVisibility animation to complete before focusing play/pause button
               kotlinx.coroutines.delay(250)
               val returnTo = panelReturnFocus
               panelReturnFocus = null
               val restored = returnTo != null &&
                   runCatching { returnTo.requestFocus(androidx.compose.ui.focus.FocusDirection.Enter) }.getOrDefault(false)
               if (!restored) {
                   try {
                       playPauseFocusRequester.requestFocus()
                   } catch (e: Exception) {
                       // Focus requester may not be ready yet
                   }
               }
   ```
   with:
   ```kotlin
               // Focus as soon as the controls' content is attached; it can take focus while it
               // is still fading in. A fixed wait let early D-pad presses land elsewhere first.
               val returnTo = panelReturnFocus
               panelReturnFocus = null
               val restored = returnTo != null && requestFocusWithinFrames {
                   returnTo.requestFocus(androidx.compose.ui.focus.FocusDirection.Enter)
               }
               if (!restored) {
                   requestFocusWithinFrames { playPauseFocusRequester.requestFocus() }
               }
   ```
3. Add `import androidx.compose.runtime.withFrameNanos` if it is not already imported.

## Boundaries

- Do NOT change the `LaunchedEffect`'s keys, its early returns (`:610-612`) or the
  `!uiState.showControls` branch.
- Do NOT change the controls `AnimatedVisibility` enter/exit specs (`:1557-1561`).
- Do NOT touch the separate `delay(250)` in the stream-info restore effect (`:665-671`). It has
  the same smell but restores focus after a different overlay; note it, do not change it.
- Do NOT touch `FocusRestoreUtils.kt`, the post-play block (plan 008) or the Skip Intro block
  (plan 005).
- If the code does not match the excerpts (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes (this plan
    adds no `tween`).
  - `./gradlew :app:testFullDebugUnitTest --tests '*PlayerBackPressTest*'` passes. It is the
    nearest coverage of player focus/back flows; nothing tests this effect directly.
- **Feel check** (TV or emulator, during playback):
  - Controls hidden → press D-pad Down to open them, and **immediately** press D-pad Left or
    Right. Focus must already be on play/pause, and the press moves from play/pause to its
    neighbour. Repeat 10 times: it must never land on a random control or jump back to
    play/pause afterwards.
  - Open the Episodes panel from the controls, then press Back. Focus returns to the Episodes
    button, not play/pause. Repeat for Sources, Queue, Audio and Subtitles.
  - Animator duration scale 10x: open the controls. The focus ring appears on play/pause
    while the controls are still fading in. That is expected and correct.
  - Hold D-pad Down to open the controls, then hit OK straight away: play/pause toggles.
  - On a device with frame-rate matching, open the controls at a 24 Hz refresh. Focus still
    lands on play/pause within a blink.
- **Done when**:
  - `grep -n "delay(250)" PlayerScreen.kt` shows only the stream-info restore (`:667`).
  - The build is green.
  - The immediate-D-pad check passes.
