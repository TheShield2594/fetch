# 009 — Draw the skeleton shimmer in the draw phase from one clock per skeleton

- **Status**: DONE (build not verified locally: no Android SDK in the session; guard counts re-checked by script; shipped 1200ms default)
- **Commit**: ee8dd84
- **Severity**: HIGH
- **Category**: Performance (deferred state reads)
- **Estimated scope**: 4 files (Skeletons.kt rewrite of ~40 lines plus mechanical renames, 2 call-site files, 1 baseline line)
- **Depends on**: 001 (only for ordering; this plan uses the existing `FetchMotion.tokens.durations.shimmer` token, not the new ones)

## Problem

Every loading skeleton in the app recomposes its whole tree on every frame while it is
on screen. That includes Home's cold start, the library and "See all" grids, the detail
page and the cast page, which are the moments a low-end TV is busiest.

The cause is `rememberShimmerBrush()`. It reads the animated value during composition and
builds a new `Brush` each frame:

```kotlin
// app/src/main/java/com/fetch/tv/ui/components/Skeletons.kt:443-475 — current
@Composable
fun rememberShimmerBrush(backdropAware: Boolean = false): Brush {
    val shimmerColors = if (backdropAware) {
        listOf(
            FetchTheme.colors.TextPrimary.copy(alpha = 0.08f),
            FetchTheme.colors.TextPrimary.copy(alpha = 0.20f),
            FetchTheme.colors.TextPrimary.copy(alpha = 0.08f)
        )
    } else {
        listOf(
            FetchTheme.colors.SurfaceVariant.copy(alpha = 0.30f),
            FetchTheme.colors.SurfaceVariant.copy(alpha = 0.60f),
            FetchTheme.colors.SurfaceVariant.copy(alpha = 0.30f)
        )
    }
    // Reduced motion keeps the placeholder's resting colour without the sweep.
    if (!FetchTheme.fullMotion) return SolidColor(shimmerColors.first())
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing)
        ),
        label = "shimmer_translate"
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translate - 1000f, 0f),
        end = Offset(translate, 0f)
    )
}
```

`translate` is read in the composable body. So the composable that calls
`rememberShimmerBrush()` recomposes every frame. Every child that takes the brush as a
parameter also recomposes, because a new `LinearGradient` is never equal to the last one.
These are the affected trees:

- `CatalogRowsSkeleton` (`Skeletons.kt:490`): a hero plus 3 rows of a title bar and 12 poster bars. Shown by HomeScreen.kt:363, ModernHomeContent.kt:195, FolderDetailScreen.kt:923 and LibraryServerContent.kt:41.
- `PosterGridSkeleton` (`Skeletons.kt:538`): 3 × 12 poster bars. Shown by LibraryScreen.kt:255, CatalogSeeAllScreen.kt:345 and FolderDetailScreen.kt:109 and :389.
- `MetaDetailsSkeleton` (`Skeletons.kt:111`): the hero, season tabs, episodes, cast and logos. Shown by MetaDetailsScreen.kt:675.
- `StreamsSkeletonList` (`Skeletons.kt:56`): `StreamCardSkeleton(showAddonLogo = showAddonLogo, shimmerBrush = rememberShimmerBrush())` calls it once per list item, which creates **6 separate infinite transitions** instead of one.
- `CastDetailSkeleton` (`ui/screens/cast/CastDetailScreen.kt:658`).
- The QR sign-in loading bars (`ui/screens/account/AuthQrSignInScreen.kt:632`).

The repo already has the right pattern for the Home row placeholders in
`ui/components/PlaceholderShimmer.kt`. There, a `State<Float>` is created once and read
only inside `drawWithCache { onDrawBehind { ... } }`:

```kotlin
// app/src/main/java/com/fetch/tv/ui/components/PlaceholderShimmer.kt:46-64 — exemplar
fun Modifier.placeholderCardShimmer(
    shimmerOffsetState: State<Float>,
    backgroundColor: Color? = null
): Modifier = drawWithCache {
    onDrawBehind {
        backgroundColor?.let { drawRect(color = it) }
        val shimmerOffset = shimmerOffsetState.value
        drawRect(brush = Brush.linearGradient(...))
    }
}
```

**Why not reuse `rememberPlaceholderShimmerOffsetState` directly.** Its clock runs from -1
to 2 over a literal 1600ms and drives a translucent white band drawn over a separate
background. The skeleton's look is different: the bar itself is a three-stop
SurfaceVariant/TextPrimary gradient. Reusing that clock would retime the skeleton to
1600ms and leave the unused `shimmer` token unused. Changing PlaceholderShimmer.kt to fix
that would also retime the Home row placeholders, which is out of scope here. So this plan
copies the pattern (a state read in the draw phase), not the function.

**Duration.** `FetchMotion.tokens.durations.shimmer` is 1200 and nothing uses it today
(`grep -rn "durations.shimmer" app/src/main` returns nothing). The skeleton moves to it.
The sweep becomes about 15% faster (1400 → 1200ms). That is within the normal range for a
loading shimmer, and it removes the last literal duration from Skeletons.kt. Easing stays
`LinearEasing`, which is correct for constant motion.

## Target

- One public holder type, `SkeletonShimmer`, created once per skeleton tree by
  `rememberSkeletonShimmer(backdropAware)`.
- One modifier, `Modifier.skeletonShimmer(shimmer)`, that reads the clock only in
  `drawBehind`.
- No skeleton composable takes or builds a `Brush` any more. Passing the same stable
  `SkeletonShimmer` instance means children skip recomposition. Each frame only re-runs
  the draw lambdas.
- Reduced motion (`!FetchTheme.fullMotion`) creates no clock and draws the solid resting
  colour `colors.first()`. That matches today's `SolidColor(shimmerColors.first())`.
- The gradient geometry is unchanged: start x = `t - 1000f`, end x = `t`, with `t` running
  linearly from 0f to 1000f, in each element's local coordinates, and default
  `TileMode.Clamp`.

```kotlin
// target — replaces Skeletons.kt:443-475 (the whole rememberShimmerBrush function)

/** Sweep distance of the skeleton gradient, in px of each placeholder's own coordinates. */
private const val SKELETON_SHIMMER_DISTANCE_PX = 1000f

/**
 * The shared shimmer for one skeleton tree. Create it once with [rememberSkeletonShimmer] and pass
 * the same instance to every bar; [skeletonShimmer] reads its clock only while drawing, so the
 * sweep redraws the bars without recomposing them.
 */
@Stable
class SkeletonShimmer internal constructor(
    internal val colors: List<Color>,
    /** Null under reduced motion: the bars hold their resting colour. */
    internal val offset: State<Float>?
)

@Composable
fun rememberSkeletonShimmer(backdropAware: Boolean = false): SkeletonShimmer {
    val colors = if (backdropAware) {
        listOf(
            FetchTheme.colors.TextPrimary.copy(alpha = 0.08f),
            FetchTheme.colors.TextPrimary.copy(alpha = 0.20f),
            FetchTheme.colors.TextPrimary.copy(alpha = 0.08f)
        )
    } else {
        listOf(
            FetchTheme.colors.SurfaceVariant.copy(alpha = 0.30f),
            FetchTheme.colors.SurfaceVariant.copy(alpha = 0.60f),
            FetchTheme.colors.SurfaceVariant.copy(alpha = 0.30f)
        )
    }
    // Reduced motion keeps the placeholder's resting colour without the sweep.
    if (!FetchTheme.fullMotion) return remember(colors) { SkeletonShimmer(colors, offset = null) }
    val offset = rememberInfiniteTransition(label = "skeletonShimmer").animateFloat(
        initialValue = 0f,
        targetValue = SKELETON_SHIMMER_DISTANCE_PX,
        animationSpec = infiniteRepeatable(
            animation = tween(FetchMotion.tokens.durations.shimmer, easing = LinearEasing)
        ),
        label = "skeletonShimmerOffset"
    )
    return remember(colors, offset) { SkeletonShimmer(colors, offset) }
}

/** Fills the element with [shimmer]; put it after `clip(...)` exactly where `background(brush)` was. */
fun Modifier.skeletonShimmer(shimmer: SkeletonShimmer): Modifier = drawBehind {
    val offset = shimmer.offset
    if (offset == null) {
        drawRect(color = shimmer.colors.first())
    } else {
        val t = offset.value
        drawRect(
            brush = Brush.linearGradient(
                colors = shimmer.colors,
                start = Offset(t - SKELETON_SHIMMER_DISTANCE_PX, 0f),
                end = Offset(t, 0f)
            )
        )
    }
}
```

## Repo conventions to follow

- Draw-phase read of an animated `State<Float>`. Imitate
  `ui/components/PlaceholderShimmer.kt:46-64`, and note that it also returns a parked
  state instead of a clock under reduced motion (`:33`).
- Durations come from `FetchMotion.tokens.durations.*`. A literal `tween(1400 ...)` in a
  component is counted by `ThemeLiteralGuardTest`. The baseline file
  `app/src/test/resources/theme-literals-baseline.txt` line 5 currently reads
  `com/fetch/tv/ui/components/Skeletons.kt|DURATION|1`.
- Reduced motion is `FetchTheme.fullMotion`. When it is false, create no clock.

## Steps

All paths are under `app/src/main/java/com/fetch/tv/`.

1. **`ui/components/Skeletons.kt`: replace the brush factory.** Delete the whole
   `rememberShimmerBrush` function (lines 443-475) and put the **Target** code block in its
   place.

2. **`ui/components/Skeletons.kt`: imports.**
   - Add:
     - `import androidx.compose.runtime.Stable`
     - `import androidx.compose.runtime.State`
     - `import androidx.compose.runtime.remember`
     - `import androidx.compose.ui.draw.drawBehind`
     - `import com.fetch.tv.ui.theme.FetchMotion`
   - Remove `import androidx.compose.ui.graphics.SolidColor` (line 3) and
     `import androidx.compose.runtime.getValue` (line 27), which are now unused.
   - Keep `Brush`, which `BackdropLoadingScrim` still uses at :174, :189 and :204, and keep
     `Offset`, `Color`, `LinearEasing`, `tween`, `animateFloat`, `infiniteRepeatable`,
     `rememberInfiniteTransition` and `background`.

3. **`ui/components/Skeletons.kt`: hoist the stream list clock (`:42-59`).** Replace

   ```kotlin
   ) {
       LazyColumn(
           modifier = modifier
               .fillMaxSize()
               .padding(FetchTheme.spacing.lg),
           verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
           contentPadding = PaddingValues(vertical = FetchTheme.spacing.sm)
       ) {
           items(itemCount) {
               StreamCardSkeleton(showAddonLogo = showAddonLogo, shimmerBrush = rememberShimmerBrush())
           }
       }
   ```
   with
   ```kotlin
   ) {
       val shimmer = rememberSkeletonShimmer()
       LazyColumn(
           modifier = modifier
               .fillMaxSize()
               .padding(FetchTheme.spacing.lg),
           verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
           contentPadding = PaddingValues(vertical = FetchTheme.spacing.sm)
       ) {
           items(itemCount) {
               StreamCardSkeleton(showAddonLogo = showAddonLogo, shimmer = shimmer)
           }
       }
   ```

4. **`ui/components/Skeletons.kt`: `StreamCardSkeleton` default (`:65`).** Replace
   `    shimmerBrush: Brush = rememberShimmerBrush()` with
   `    shimmer: SkeletonShimmer = rememberSkeletonShimmer()`.

5. **`ui/components/Skeletons.kt`: the three tree roots.** Change these lines:
   - `:111` `    val shimmerBrush = rememberShimmerBrush(backdropAware = backdropAware)` → `    val shimmer = rememberSkeletonShimmer(backdropAware = backdropAware)`
   - `:490` `    val shimmerBrush = rememberShimmerBrush()` → `    val shimmer = rememberSkeletonShimmer()`
   - `:538` `    val shimmerBrush = rememberShimmerBrush()` → `    val shimmer = rememberSkeletonShimmer()`

6. **`ui/components/Skeletons.kt`: mechanical renames, in this order, each a
   whole-file exact-text replace.** The expected counts are at ee8dd84. If a count differs,
   STOP.
   1. `shimmerBrush = shimmerBrush` → `shimmer = shimmer` (6 occurrences: :131, :135, :140, :146, :150, :154).
   2. `brush = shimmerBrush` → `shimmer = shimmer` (29 occurrences, all `SkeletonBar`/`SkeletonPill` arguments).
   3. `.background(shimmerBrush)` → `.skeletonShimmer(shimmer)` (5 occurrences: :99, :247, :313, :352, :389).
   4. `shimmerBrush: Brush` → `shimmer: SkeletonShimmer` (5 remaining occurrences: :216, :279, :293, :326, :367).
   5. In the three public primitives `SkeletonBar` (Dp width, `:397-410`), `SkeletonBar`
      (Float width, `:413-426`) and `SkeletonPill` (`:429-441`):
      - Replace the parameter `    brush: Brush,` (Dp `SkeletonBar` and Float `SkeletonBar`) and
        `    brush: Brush` (`SkeletonPill`) with `    shimmer: SkeletonShimmer,` and
        `    shimmer: SkeletonShimmer`.
      - Replace `            .background(brush)` with `            .skeletonShimmer(shimmer)`
        in each of the 3 bodies.
   6. Verify `grep -n "shimmerBrush\|rememberShimmerBrush\|brush" Skeletons.kt`. The only
      remaining lowercase `brush` matches must be `drawRect(brush = ...` inside
      `skeletonShimmer`.

7. **`ui/screens/cast/CastDetailScreen.kt`: caller at :658.**
   - Change `    val shimmerBrush = rememberShimmerBrush()` to `    val shimmer = rememberSkeletonShimmer()`.
   - Replace `.background(shimmerBrush)` → `.skeletonShimmer(shimmer)` at all 7 sites:
     :688, :714, :724, :741, :749, :767 and :775.
   - Imports: replace `import com.fetch.tv.ui.components.rememberShimmerBrush` (:94) with
     `import com.fetch.tv.ui.components.rememberSkeletonShimmer` and add
     `import com.fetch.tv.ui.components.skeletonShimmer`.
   - Keep the `background` and `Brush` imports, since `accentGradient` at :660-670 still
     uses them.

8. **`ui/screens/account/AuthQrSignInScreen.kt`: caller at :632.**
   - Change `            val shimmerBrush = rememberShimmerBrush(backdropAware = true)` to
     `            val shimmer = rememberSkeletonShimmer(backdropAware = true)`.
   - In the three `SkeletonBar(...)` calls at :640, :649 and :658, replace
     `brush = shimmerBrush` with `shimmer = shimmer`.
   - Imports: replace `import com.fetch.tv.ui.components.rememberShimmerBrush` (:70) with
     `import com.fetch.tv.ui.components.rememberSkeletonShimmer`.

9. **Baseline.** In `app/src/test/resources/theme-literals-baseline.txt`, delete the line
   `com/fetch/tv/ui/components/Skeletons.kt|DURATION|1`. Skeletons.kt now has zero literal
   tweens, and the guard fails if the line stays.

10. **Repo-wide check.**
    - `grep -rn "rememberShimmerBrush\|shimmerBrush" app/src` must return nothing.
    - Every caller of `rememberShimmerBrush` at ee8dd84 is covered above:
      - Skeletons.kt :56, :65, :111, :490, :538
      - CastDetailScreen.kt :658
      - AuthQrSignInScreen.kt :632
    - None of the other skeleton entry points changes signature, so these callers need no
      edits: `MetaDetailsSkeleton`, `CatalogRowsSkeleton`, `PosterGridSkeleton`,
      `StreamsSkeletonList`.

## Boundaries

- Do NOT touch `ui/components/PlaceholderShimmer.kt` or `ui/components/Shimmer.kt`. They
  are separate shimmers with their own baseline lines (1600ms and 2400ms). Unifying the
  three shimmers is a separate decision.
- Do NOT change skeleton layout, sizes, colours or corner radii. Only the fill mechanism
  changes.
- Do NOT add dependencies.
- If any excerpt or occurrence count above does not match the file (drift since ee8dd84),
  STOP and report instead of improvising.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes with
    the baseline line removed.
  - No unit test covers Skeletons.kt directly. As a regression check, run
    `./gradlew :app:testFullDebugUnitTest --tests '*ModernHomePlaceholderShimmerTest*'`,
    which should be unaffected.
- **Feel check** (TV or emulator):
  - Cold-start the app to Home. The skeleton hero and rows sweep left to right, all
    posters in step, at a constant speed with no stutter.
  - A/B the speed against a build from ee8dd84 at 1x. The new sweep is slightly faster
    (1200ms vs 1400ms). If it reads as frantic on a 55" screen, report it. Do not
    substitute another number. A new token value would be a follow-up.
  - Open Library and "See all" before content loads, and a title's detail page on a slow
    network. These show the poster grid and detail skeletons.
  - Open Streams for a title. All 6 stream placeholder cards sweep in sync. Before, each
    had its own clock and they could drift.
  - Developer options → Animator duration scale **5x**. The sweep slows smoothly.
  - Set "Animation off". Every skeleton shows a flat resting colour, with no sweep and no
    flicker.
  - **Performance**: Layout Inspector with recomposition counts on, on Home cold start.
    `CatalogRowsSkeleton` and its `SkeletonBar` children should show recomposition counts
    that stay flat (single digits) while the sweep runs. Before this change they climbed
    by about 60 per second.
- **Done when**:
  - `rememberShimmerBrush` no longer exists.
  - Every skeleton reads its clock only inside `Modifier.skeletonShimmer`'s `drawBehind`.
  - The baseline line is gone and both gradle commands are green.
