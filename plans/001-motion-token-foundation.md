# 001 — Add strong ease-out / ease-in-out curves and enter/exit tween helpers to FetchMotion

- **Status**: DONE (build not verified locally: no Android SDK in the session)
- **Commit**: ee8dd84
- **Severity**: HIGH (enabler — most other plans depend on it)
- **Category**: 7. Cohesion & tokens (and 2. Easing & duration)
- **Estimated scope**: 1 file, ~30 added lines, no behaviour change on its own

## Problem

The app's motion tokens live in `app/src/main/java/com/fetch/tv/ui/theme/MotionFocusTokens.kt`.
There are two problems:

- Every easing is a built-in Material curve. `standard = FastOutSlowInEasing` is a mild
  ease-in-out, not a responsive ease-out.
- There is no duration under 300ms meant for entering or exiting UI, apart from
  `quick` (125) and `fast` (180).

As a result, screens reach for `medium` (350), `overlay` (400), `slow` (450) and
`long` (600) on frequent UI. The later plans in this folder (002–024) need a shared
vocabulary: one strong ease-out for things entering and leaving, one strong ease-in-out
for things moving on screen, and an enter/exit duration pair.

Current code (`MotionFocusTokens.kt:13-41`):

```kotlin
@Immutable
data class FetchMotionDurations(
    val instant: Int,
    val quick: Int,
    val fast: Int,
    val medium: Int,
    val slow: Int,
    /** Large, deliberate moves such as the detail hero growing to full screen. */
    val long: Int,
    val overlay: Int,
    val sidebarLabelIn: Int,
    val sidebarLabelOut: Int,
    val sidebarPanelIn: Int,
    val sidebarPanelOut: Int,
    val sidebarBloomOut: Int,
    val sidebarEnter: Int,
    val sidebarExit: Int,
    val hero: Int,
    val shimmer: Int
)

@Immutable
data class FetchMotionEasings(
    val standard: Easing,
    val emphasized: Easing,
    val decelerate: Easing,
    val accelerate: Easing
)
```

and `MotionFocusTokens.kt:64-104` (the `FetchMotion` object, constructing these with named
arguments, plus helpers `quickTween`, `focusTween`, `mediumTween`, `slowTween`, `focusSpring`).

## Target

Add tokens only. Do not change or remove any existing value. Plan 020 handles retiring
old tokens.

New durations:

| Token | Value (ms) | Use |
|---|---|---|
| `enter` | 200 | anything appearing: overlays, panels, labels, crossfades the user waits on |
| `exit` | 150 | anything leaving (exits are faster than entries) |
| `move` | 250 | on-screen movement or morph between two resting states |

New easings (copy the values exactly):

| Token | Curve | Use |
|---|---|---|
| `out` | `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)` | strong ease-out for entering and exiting UI |
| `inOut` | `CubicBezierEasing(0.77f, 0f, 0.175f, 1f)` | strong ease-in-out for on-screen movement |
| `drawer` | `CubicBezierEasing(0.32f, 0.72f, 0f, 1f)` | iOS-like drawer curve for side panels |

New helpers on `FetchMotion`:

```kotlin
/** Something appearing: overlays, panels, labels, crossfades the user is waiting on. */
fun <T> enterTween(): TweenSpec<T> = tween(tokens.durations.enter, easing = tokens.easings.out)

/** Something leaving. Shorter than [enterTween] so the response feels immediate. */
fun <T> exitTween(): TweenSpec<T> = tween(tokens.durations.exit, easing = tokens.easings.out)

/** Something moving or morphing between two on-screen resting states. */
fun <T> moveTween(): TweenSpec<T> = tween(tokens.durations.move, easing = tokens.easings.inOut)
```

## Repo conventions to follow

- Tokens are fields on the `@Immutable` data classes, given values in the single
  `FetchMotion.tokens = FetchMotionTokens(...)` call with named arguments. This is the only
  constructor call in the codebase (verified with
  `grep -rn "FetchMotionDurations(\|FetchMotionEasings(" --include=*.kt .`).
- Durations have short KDoc only where the name isn't obvious; see `long` at line 19.
- Helpers are one-line `fun <T> xTween(): TweenSpec<T> = tween(...)`, exactly like
  `quickTween()` at line 96.
- `ui/theme` is exempt from `ThemeLiteralGuardTest`, so literal numbers are allowed in this
  file.

## Steps

1. In `FetchMotionDurations`, add three fields after `fast`:
   ```kotlin
       /** Something appearing on screen. */
       val enter: Int,
       /** Something leaving the screen; shorter than [enter]. */
       val exit: Int,
       /** On-screen movement or a morph between two resting states. */
       val move: Int,
   ```
2. In `FetchMotionEasings`, add three fields after `accelerate`:
   ```kotlin
       /** Strong ease-out for things entering or leaving. */
       val out: Easing,
       /** Strong ease-in-out for things moving on screen. */
       val inOut: Easing,
       /** Drawer and side-panel slides. */
       val drawer: Easing
   ```
3. In `FetchMotion.tokens`, set the new values:
   - In `FetchMotionDurations(...)` after `fast = 180,`, add `enter = 200,`, `exit = 150,`
     and `move = 250,`.
   - In `FetchMotionEasings(...)` after
     `accelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)`, add a comma and then:
     - `out = CubicBezierEasing(0.23f, 1f, 0.32f, 1f),`
     - `inOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f),`
     - `drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)`
4. After `slowTween()`, add the three helpers `enterTween`, `exitTween` and `moveTween` from
   **Target**, with their KDoc.

## Boundaries

- Do NOT change any existing token value, remove any token, or edit any file outside
  `MotionFocusTokens.kt`.
- Do NOT start using the new tokens anywhere; later plans do that.
- If the file no longer matches the excerpts above (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes.
- **Feel check**: none. This plan adds vocabulary only, so the app must look identical.
- **Done when**:
  - `grep -n "enterTween\|exitTween\|moveTween\|inOut\|drawer" MotionFocusTokens.kt` shows
    the new definitions.
  - The build is green.
