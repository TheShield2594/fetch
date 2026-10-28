# 018 — Only spin the network test icon while a test runs, and spin it in the layer

- **Status**: DONE (build not verified locally: no Android SDK in the session; guard counts re-checked by script)
- **Commit**: ee8dd84
- **Severity**: LOW
- **Category**: Performance (deferred state reads, idle animation clocks)
- **Estimated scope**: 3 files (NetworkSettingsScreen.kt ~35 lines, MotionFocusTokens.kt 2 lines, 1 baseline line)
- **Depends on**: 001 (both edit `MotionFocusTokens.kt`; run 001 first to avoid an edit conflict)

## Problem

On Settings → Network, the two speed-test metric cards each run an infinite rotation
clock **from the moment the first test starts until the user leaves the screen**, even when
nothing is loading. The clock is also read in composition, so both cards recompose on
every frame the whole time.

```kotlin
// app/src/main/java/com/fetch/tv/ui/screens/settings/NetworkSettingsScreen.kt:901-933 — current
@Composable
private fun NetworkMetricCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    loading: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(
        modifier = modifier.padding(FetchTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        Icon(
            imageVector = if (loading) Icons.Default.Refresh else icon,
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .then(if (loading) Modifier.rotate(rotation) else Modifier),
            tint = if (loading) FetchTheme.colors.Secondary else FetchTheme.colors.Primary
        )
```

- The cards are composed at :672 and :679 inside `if (testState != NetworkTestState.Idle)`
  (:654). Once a test has run, both exist for the rest of the visit.
- `rememberInfiniteTransition` is unconditional, so the clock never stops.
- `val rotation by ...` read at the top of `NetworkMetricCard` invalidates the whole card
  every frame, including both `Text`s.

**Why not reuse an existing spinner?**

- `ui/components/LoadingIndicator.kt` draws an accent arc (not the refresh glyph) and
  hard-sizes itself to `FetchTheme.spacing.xxxl` inside (`:47`), so it can't fit the
  card's 28dp icon slot without changing the card's look and height.
- `ui/components/RefreshFilterChip.kt:47-81` is welded to a `FilterChip` and has
  finish-the-turn logic that this card doesn't need.

So the fix stays local and copies the right pattern: RefreshFilterChip's
`graphicsLayer { rotationZ = ... }` at :113.

**Duration.** The literal `1000` (one turn per second, linear) is counted by
`ThemeLiteralGuardTest`, via baseline line
`com/fetch/tv/ui/screens/settings/NetworkSettingsScreen.kt|DURATION|1` in
`app/src/test/resources/theme-literals-baseline.txt`. No existing token is 1000ms. The
nearest are `shimmer` (1200) and `long` (600), and both would visibly change the spin
speed. Add a `spinner = 1000` token. Easing stays `LinearEasing`, which is correct for
constant rotation.

## Target

- The rotation clock exists only while `loading` is true, in a child composable, and only
  when `FetchTheme.fullMotion` is true.
- The angle is read only in `graphicsLayer { rotationZ = ... }`.
- Under reduced motion, the refresh glyph shows still and the "..." value text carries
  the loading state.
- `FetchMotionDurations.spinner = 1000`.

```kotlin
// target — NetworkSettingsScreen.kt
@Composable
private fun NetworkMetricCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    loading: Boolean
) {
    Column(
        modifier = modifier.padding(FetchTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        if (loading) {
            SpinningRefreshIcon()
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = FetchTheme.colors.Primary
            )
        }
        // ... the two Text(...) calls unchanged ...
    }
}

/**
 * The refresh glyph turning once per second while a test runs. The clock lives only as long as
 * this composable and is read in the layer, so only the icon redraws; reduced motion holds it still.
 */
@Composable
private fun SpinningRefreshIcon() {
    val rotation = if (FetchTheme.fullMotion) {
        rememberInfiniteTransition(label = "spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(FetchMotion.tokens.durations.spinner, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )
    } else {
        null
    }
    Icon(
        imageVector = Icons.Default.Refresh,
        contentDescription = null,
        modifier = Modifier
            .size(28.dp)
            .graphicsLayer { rotationZ = rotation?.value ?: 0f },
        tint = FetchTheme.colors.Secondary
    )
}
```

## Repo conventions to follow

- New literal durations belong in `ui/theme/MotionFocusTokens.kt` as named fields of
  `FetchMotionDurations`, set in the single `FetchMotion.tokens = FetchMotionTokens(...)`
  call with named arguments.
- Layer-phase rotation: `ui/components/RefreshFilterChip.kt:111-113`
  (`.graphicsLayer { rotationZ = rotationAnimatable.value }`).
- Reduced-motion holding still: `ui/components/PlaceholderShimmer.kt:33`.

## Steps

1. **`app/src/main/java/com/fetch/tv/ui/theme/MotionFocusTokens.kt`: add the token.**
   - In `data class FetchMotionDurations`, directly after the line `    val hero: Int,`, insert:
     ```kotlin
         /** One full turn of a loading spinner glyph (linear, repeating). */
         val spinner: Int,
     ```
   - In `FetchMotion.tokens`, directly after `            hero = 450,`, insert
     `            spinner = 1000,`.
   - Do not touch the `shimmer` lines. Plan 020 appends `splashExit` after `shimmer`, and
     plan 001 inserts its fields after `fast`, so neither collides with this insertion.

2. **`app/src/main/java/com/fetch/tv/ui/screens/settings/NetworkSettingsScreen.kt:901-933`.**
   - Replace from `private fun NetworkMetricCard(` through the closing `)` of the `Icon(...)`
     call (:933) with the **Target** `NetworkMetricCard` header, `Column` and `if/else` icon
     block.
   - Keep the two `Text(...)` calls (:934-947) and the closing braces exactly as they are.
   - Add the `SpinningRefreshIcon` composable from **Target** directly after
     `NetworkMetricCard`'s closing brace (before `@Composable private fun StreamTestResultRow`).

3. **Imports in `NetworkSettingsScreen.kt`.**
   - Add `import androidx.compose.ui.graphics.graphicsLayer` and
     `import com.fetch.tv.ui.theme.FetchMotion`.
   - Remove `import androidx.compose.ui.draw.rotate` (:52), which is now unused.
   - Keep `LinearEasing`, `RepeatMode`, `animateFloat`, `infiniteRepeatable`,
     `rememberInfiniteTransition` and `tween`, which `SpinningRefreshIcon` uses.
   - Keep `getValue`, which is used elsewhere in the file.

4. **Baseline.** In `app/src/test/resources/theme-literals-baseline.txt`, delete the line
   `com/fetch/tv/ui/screens/settings/NetworkSettingsScreen.kt|DURATION|1`.
   `tween(1000, ...)` was the file's only literal tween (`grep -n "tween(" NetworkSettingsScreen.kt`
   shows only :915), so its count drops to 0.

## Boundaries

- Do NOT touch `LoadingIndicator.kt` or `RefreshFilterChip.kt`. RefreshFilterChip computes
  `remaining / 360f * 1000` and could adopt the new token in a later cleanup, but not here.
- Do NOT change the card's layout, text or colours.
- Do NOT rename or reorder existing `FetchMotionDurations` fields.
- If the excerpt does not match (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes with
    the baseline line deleted.
  - No other unit test covers this screen
    (`grep -rln "NetworkSettings" app/src/test` finds only the settings search index tests).
    Run `./gradlew :app:testFullDebugUnitTest --tests '*SettingsSearchIndexTest*'` as a
    sanity check.
- **Feel check** (TV or emulator), on Settings → Network, run the speed test:
  - While latency is measured, only the latency card's icon spins, at one turn per second
    (unchanged speed). The download card shows its speed glyph still. Then the roles
    swap.
  - When a phase finishes, its icon snaps back to the metric glyph.
  - At Animator duration scale 5x, the spin slows proportionally.
  - With "Animation off", the refresh glyph shows still while loading, and "..." shows in
    the value.
  - **Performance**: in Layout Inspector with recomposition counts on, after the test
    finishes, both `NetworkMetricCard`s stop accumulating recompositions. Before the change
    they climbed at about 60 per second until you left the screen. During a test, the
    spinning card's count stays flat while the icon turns.
- **Done when**:
  - `NetworkMetricCard` contains no `rememberInfiniteTransition`.
  - The rotation is read only in `graphicsLayer {}`.
  - The `spinner` token exists.
  - The baseline line is gone and the build is green.
