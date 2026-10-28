# 022 — Fade the Edit Profile overlay in and out like Create Profile

- **Status**: TODO
- **Commit**: ee8dd84
- **Severity**: LOW-MEDIUM
- **Category**: Missed opportunity (hard cut) + 7. Cohesion
- **Estimated scope**: 1 file (`ProfileSelectionScreen.kt`), ~20 lines
- **Depends on**: 001 (uses `FetchMotion.enterTween()`, `exitTween()`)

## Problem

In `app/src/main/java/com/fetch/tv/ui/screens/profile/ProfileSelectionScreen.kt`, the Create
Profile overlay fades (lines 735-740):

```kotlin
// ProfileSelectionScreen.kt:735-740 — current
// Create Profile Overlay
AnimatedVisibility(
    visible = showCreateProfile,
    enter = fadeIn(tween(FetchMotion.tokens.durations.fast)),
    exit = fadeOut(tween(FetchMotion.tokens.durations.quick))
) {
    CreateProfileOverlay(
```

but the Edit Profile overlay right below it pops in and out in one frame (lines 782-787):

```kotlin
// ProfileSelectionScreen.kt:782-787 — current
// Edit Profile Overlay
profileToEdit?.let { profile ->
    val editorPreferences by remember(profile.id) {
        viewModel.editorPreferences(profile.id)
    }.collectAsState(initial = null)
    EditProfileOverlay(
```

`EditProfileOverlay` (line 2220) is an in-tree full-screen `Box` with a
`Color.Black.copy(alpha = 0.85f)` scrim, so a full-screen dark layer appears and disappears as
a hard cut. Its `profileToEdit?.let` block closes at line 806 (after the `onSaveProfile`
lambda's closing `)` and the `let`'s `}`).

Focus inside the overlay: `EditProfileOverlay` creates `nameFocusRequester` and requests it
after two frames (`LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; runCatching { nameFocusRequester.requestFocus() } }`,
lines ~2258-2261), and registers `BackHandler(onBack = onDismiss)` (line 2231). Inside
`AnimatedVisibility` the content is composed in the same frame the overlay becomes visible,
so the `LaunchedEffect` and the requester work unchanged; Create Profile uses the identical
pattern today. During the 150ms exit the content stays composed, so its focused button could
take a second OK (a second Save). The target swallows key events while exiting.

## Target

- Edit Profile: `AnimatedVisibility(visible = profileToEdit != null, enter = fadeIn(FetchMotion.enterTween()), exit = fadeOut(FetchMotion.exitTween()))`.
  The last non-null profile is kept while it fades out, so the overlay doesn't go blank.
  Keys are ignored while it fades out.
- Create Profile: same `enterTween()` / `exitTween()` so the two siblings match (200ms in,
  150ms out, strong ease-out; was 180/125 with default easing).
- Both are opacity only, so reduced motion keeps them as they are (a short fade is allowed in
  reduced motion).

## Repo conventions to follow

- Exemplar: the Create Profile overlay at `ProfileSelectionScreen.kt:735-780`.
- Keep-last-value pattern for exits: a remembered `MutableState` updated whenever the source is non-null.
- `ProfileSelectionScreen.kt|DURATION|1` (baseline line 8) is the `tween(42)` shake at line
  2655; this plan doesn't touch it and removes no literal, so the baseline stays.

## Steps

1. **Import.** Add `import androidx.compose.animation.EnterExitState` (next to the other
   `androidx.compose.animation` imports, lines 15-34).

2. **Create Profile** (lines 738-739): change to
   ```kotlin
   enter = fadeIn(FetchMotion.enterTween()),
   exit = fadeOut(FetchMotion.exitTween())
   ```

3. **Edit Profile.** Replace the opening of the block (lines 782-783):
   ```kotlin
   // Edit Profile Overlay
   profileToEdit?.let { profile ->
   ```
   with:
   ```kotlin
   // Edit Profile Overlay
   // Keeps the last profile on screen while the overlay fades out, so it doesn't go blank.
   val lastProfileToEdit = remember { mutableStateOf<UserProfile?>(null) }
   profileToEdit?.let { lastProfileToEdit.value = it }
   AnimatedVisibility(
       visible = profileToEdit != null,
       enter = fadeIn(FetchMotion.enterTween()),
       exit = fadeOut(FetchMotion.exitTween())
   ) {
       // Ignore keys while fading out, so a quick second OK can't save twice.
       val exiting = transition.targetState == EnterExitState.PostExit
       Box(modifier = Modifier.onPreviewKeyEvent { exiting }) {
           lastProfileToEdit.value?.let { profile ->
   ```
   Leave the body (from `val editorPreferences by remember(profile.id) {` through the closing
   `)` of `EditProfileOverlay(...)`) unchanged. After the `let`'s closing `}` (line 806), add
   two closing braces: one for the `Box`, one for `AnimatedVisibility`. Re-indent the moved
   body by 8 spaces.

4. `UserProfile`, `Box`, `remember`, `mutableStateOf`, `onPreviewKeyEvent` and `AnimatedVisibility`
   are already imported in this file; add only what the compiler reports missing.

## Not in this plan: the Episode options overlay

`app/src/main/java/com/fetch/tv/ui/screens/detail/EpisodesSection.kt:558-628`
(`optionsEpisode?.let { ... EpisodeOptionsOverlay(...) }`) was not changed, on purpose.
`EpisodeOptionsOverlay` (`EpisodeOptionsOverlay.kt:276-282`) renders inside a
`androidx.compose.ui.window.Dialog`, a separate platform window:
- An `AnimatedVisibility` around it would not fade the dialog's content (the dialog window
  isn't drawn by the parent's layers).
- On exit it would keep the dialog window open and focused for the whole exit duration.
- The app theme is `Theme.AppCompat.DayNight.NoActionBar` (`res/values/themes.xml:3`), whose
  `dialogTheme` carries the platform dialog window animation, so the overlay most likely
  already animates in and out at the window level.
Check that on a device before planning anything for it. If it really cuts, the fix belongs
inside `EpisodeOptionsOverlay.kt`'s `Dialog` content, which is a separate plan.

## Boundaries

- Edit only `ProfileSelectionScreen.kt`, only lines 735-806 and the import.
- Do NOT touch `EditProfileOverlay`/`CreateProfileOverlay` bodies, `EpisodesSection.kt` or
  `EpisodeOptionsOverlay.kt`.
- Do NOT edit `theme-literals-baseline.txt`.
- If the code doesn't match the excerpts (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes.
  - No unit test covers these overlays (`grep -rn "EditProfileOverlay" app/src/test` is empty).
- **Feel check** (TV or emulator, profile selection screen):
  - Animator duration scale 10x. Open Edit on a profile: the scrim and editor fade in; the
    name field gets focus as before. Press Back: the editor fades out showing the same profile
    (never blank), and focus returns to the profile grid as before.
  - Save a change and immediately press OK again: only one save happens.
  - Open Create Profile, then Edit: both fade the same way.
  - Animator duration scale "Animation off": both overlays appear and disappear at once and
    focus still works.
- **Done when**: the build is green and Edit Profile fades in and out like Create Profile
  without going blank or double-saving.
