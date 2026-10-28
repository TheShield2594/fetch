# 017 — Move poster cards off tv-material `Card` onto `LightweightCard` (measure first)

- **Status**: TODO (NEEDS VERIFICATION: Step 0 is a go/no-go gate)
- **Commit**: ee8dd84
- **Severity**: MEDIUM
- **Category**: Performance (state reads during composition on D-pad focus moves)
- **Estimated scope**: 4 files. LightweightCard.kt gets about 20 added lines. Each card file has its `Card(` call reshaped (about 15 lines each).
- **Depends on**: 001 (none of its new tokens are used here; listed for ordering). If **016** has run, keep its `fullMotion` scale gating, as described in Step 4.

## Problem

D-pad focus moves happen hundreds of times a day. On every move, two cards animate: the
one losing focus and the one gaining it. How those animations are read decides whether the
move costs one recomposition or one per frame.

The repo already documents the problem and built a fix for Home rows:

```kotlin
// app/src/main/java/com/fetch/tv/ui/components/LightweightCard.kt:46-57 — current KDoc
/**
 * Drop-in replacement for tv-material `Card` as the home rows configure it: one [shape] and
 * [containerColor] for every state, no scale, no glow and [focusedBorder] while focused or
 * pressed (`CardDefaults.border(focusedBorder = ...)`, `CardDefaults.scale(focusedScale = 1f)`).
 *
 * It draws the same layers in the same order as tv-material's `Surface` (z-index, border,
 * background, clip) and handles D-pad select, focus and semantics the same way. The difference is
 * where the focus animation is read: tv-material reads its animated z-index during composition,
 * so every focus change recomposes the old and the new card on each frame of that animation.
 * Here the same spring is only read while placing the card, so a focus change recomposes this
 * composable once.
 */
```

`LightweightCard` is used by Home rows (`ui/screens/home/ModernHomeRows.kt:1318`), Continue
Watching (`ui/components/ContinueWatchingSection.kt:627`) and episodes
(`ui/screens/detail/EpisodesSection.kt:816`). Three poster cards still use tv-material
`androidx.tv.material3.Card`, and they also use a **focus scale of 1.04**
(`ui/theme/ComponentTokens.kt:91-123`, `posterCard.focusedScale = 1.04f`):

- `ui/components/ContentCard.kt:305-390`. This is the `Card(` call at :305 with
  `shape`/`colors`/`border`/`scale` at :379-390.
  - Used by `CatalogRowSection.kt:425`.
  - `CatalogRowSection` is in turn used by Library server rows
    (`LibraryServerContent.kt:59`), Search result rows (`SearchScreen.kt:765, 817, 925`) and
    Folder detail rows (`FolderDetailScreen.kt:850`).
- `ui/components/GridContentCard.kt:116-191`. Used by:
  - LibraryScreen.kt:448 (the main library grid)
  - CatalogSeeAllScreen.kt:268
  - TmdbEntityBrowseScreen.kt:597
  - SearchDiscoverSection.kt:667
  - CastDetailScreen.kt:622
  - FolderDetailScreen.kt:521
  - MoreLikeThisSection.kt:166
  - TrailerSection.kt:212
  - CollectionSection.kt:175
- `ui/components/CollectionRowSection.kt:264-285`. This is the private `FolderCard`.

Current shape of the call (identical in all three except `containerColor` and the scale
argument):

```kotlin
// app/src/main/java/com/fetch/tv/ui/components/GridContentCard.kt:180-191 — current
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(posterCardStyle.focusedBorderWidth),
                    shape = cardShape
                )
            ),
            scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
```

**What could not be verified while writing this plan.**
- The tv-material sources are not in the local Gradle cache. `find ~/.gradle -iname "*tv-material*"` found nothing, and the project uses `tvMaterial = "1.1.0-rc01"` from `gradle/libs.versions.toml:8`.
- The claim rests on the repo's own KDoc above, plus the following recollection of tv-material 1.0.x `Surface` internals:
  - `val zIndex by animateFloatAsState(...)` passed to `Modifier.zIndex(zIndex)`.
  - A scale applied through `graphicsLayer(scaleX = animatedScale, ...)`, with the value read in composition.
  - Focus/unfocus/press tween durations of roughly 300/500/120ms.
- If that recollection holds, the 1.04 scale makes these cards recompose per frame for even longer than the z-index alone would.
- Step 0 settles all of this by measuring before any code changes.

## Target

1. `LightweightCard` gains two optional parameters, `focusedScale: Float = 1f` and
   `pressedScale: Float = 1f`.
   - When either differs from 1f, it animates the scale with `FetchMotion.focusTween()`
     (180ms, `FastOutSlowInEasing`).
   - It reads the animated value **only inside a `graphicsLayer {}` lambda**.
   - With both at 1f (every current caller), it keeps today's static
     `graphicsLayer(scaleX = 1f, scaleY = 1f)`, so Home rows, Continue Watching and
     episodes are unchanged.
2. The three poster cards call `LightweightCard` instead of `Card`, with this parameter
   mapping:

| tv-material `Card` parameter (as used here) | `LightweightCard` equivalent | Notes |
|---|---|---|
| `onClick = { ... }` | `onClick = { ... }` (same lambda) | Fires on select-key `ACTION_UP` in both. The long-press guards in each card's `onPreviewKeyEvent` run first and consume their events, so behaviour is unchanged. |
| `onLongClick` (not passed) | none; semantics `onLongClick { false }` | Not used by any of the three. Long press is handled by `rememberLongPressKeyTracker` in the card's own modifier. |
| `modifier = ...` | `modifier = ...` (same chain) | Applied before `focusable()` in both, so `focusRequester`, `onFocusChanged`, `focusProperties` and `onPreviewKeyEvent` behave the same. |
| `shape = CardDefaults.shape(shape = s)` | `shape = s` | Same shape in every state. |
| `colors = CardDefaults.colors(containerColor = c, focusedContainerColor = c)` | `containerColor = c` | Both use `contentColorFor(c)` for `LocalContentColor`. |
| `border = CardDefaults.border(focusedBorder = b)` | `focusedBorder = b` | tv-material's `pressedBorder` defaults to `focusedBorder`. `LightweightCard` shows it while focused **or** pressed, so they match. |
| `scale = CardDefaults.scale(focusedScale = x)` | `focusedScale = x` | tv-material's `pressedScale` defaults to the resting `scale` (1f), which matches `pressedScale = 1f`. Confirm in Step 0. |
| `glow` (not passed, default `Glow.None`) | none | No equivalent and none needed. |
| `interactionSource` (not passed) | internal | No equivalent needed. |

**Feel.** The 1.04 scale on focus must look the same. The exact tv-material scale curve
can't be confirmed from here, so Step 5 is an explicit A/B against the pre-change build.

## Repo conventions to follow

- Exemplar for reading animated values at placement or draw time:
  `ui/components/LightweightCard.kt:70-73` and `:113-118`. Its z-index spring is read only
  inside `layout { placeable.place(..., zIndex = zIndex.value) }`.
- Motion specs come from `FetchMotion` (`ui/theme/MotionFocusTokens.kt`).
  `FetchMotion.focusTween()` is `tween(fast /*180*/, easing = standard)`.
- Reduced motion. If plan 016 has run, the scale argument already reads
  `if (FetchTheme.fullMotion) posterCardStyle.focusedScale else FetchMotion.tokens.reducedMotionScale`.
  Carry that expression into `focusedScale = ...` unchanged.

## Steps

All paths are under `app/src/main/java/com/fetch/tv/`.

### Step 0: gate (measure; no code changes)

1. Build and install a **debug** build, then turn on the in-app highlighter: Settings →
   Advanced (or Debug) → **Compose Highlighter**. It is wired at `MainActivity.kt:625-626`
   and only works in debug builds.
   - Caveat: the highlighter modifier sits on the *outer* `Column` of ContentCard
     (`ContentCard.kt:236`) and GridContentCard (`GridContentCard.kt:114`). So it shows
     recompositions of the card composable, **not** of tv-material's internal `Surface`.
   - Use it only as a secondary signal. A card that flashes red on each focus move means
     even the outer card recomposes repeatedly.
2. Primary measurement: Android Studio **Layout Inspector** with "Show Recomposition
   Counts" on, connected to the running debug app.
   1. Open **Library** (the grid uses `GridContentCard`). Reset counts.
   2. Press D-pad right **10 times**, one press per second.
   3. In the component tree, record the recomposition count of each card's tv-material
      node. Expand `GridContentCard` → `Card` → `Surface` (the node may appear as
      `SurfaceImpl` or `Box` under `Surface`). Record counts for 3 cards you passed
      through.
   4. Repeat on a **Search results row** or **Library server row** (`CatalogRowSection` →
      `ContentCard`).
   5. Repeat on Home with "Modern" layout (`LightweightCard`) as the control.
3. While connected, confirm the tv-material defaults by opening
   `androidx.tv.material3.CardDefaults.scale` and `Surface` source with "Go to
   declaration". Android Studio downloads the sources jar. Note:
   - (a) whether `zIndex` and the scale are read in composition;
   - (b) the default `pressedScale`;
   - (c) the scale animation spec.
   Paste these into the PR description.
4. **Decision.**
   - **Per-frame recomposition confirmed.** If the tv-material cards' Surface counts rise by
     about 10 or more per focus move (one per animation frame), while LightweightCard rises
     by 1-2, continue to Step 1.
   - **Not confirmed.** If the old and new cards rise by only 1-2 per focus move (no
     per-frame recomposition), STOP. Set **Status: WONTFIX** in this file, record the
     counts under Verification, and make no code changes.
   - **Pressed scale differs.** If Step 0.3 shows `pressedScale` defaults to something
     other than the resting scale, use that value for `pressedScale` in Step 4 instead of
     1f.

### Step 1: `ui/components/LightweightCard.kt`, the scale parameters

5. Replace the signature lines 59-66:
   ```kotlin
   fun LightweightCard(
       onClick: () -> Unit,
       shape: Shape,
       containerColor: Color,
       modifier: Modifier = Modifier,
       focusedBorder: Border = Border.None,
       content: @Composable ColumnScope.() -> Unit
   ) {
   ```
   with
   ```kotlin
   fun LightweightCard(
       onClick: () -> Unit,
       shape: Shape,
       containerColor: Color,
       modifier: Modifier = Modifier,
       focusedBorder: Border = Border.None,
       /** Scale while focused, as `CardDefaults.scale(focusedScale = ...)`; 1f keeps the card still. */
       focusedScale: Float = 1f,
       /** Scale while pressed; tv-material cards default this to the resting scale, 1f. */
       pressedScale: Float = 1f,
       content: @Composable ColumnScope.() -> Unit
   ) {
   ```
6. After the `val zIndex = animateFloatAsState(...)` block (ending at :73), insert:
   ```kotlin
       // Like the z-index, the scale is only read in the layer block, so it redraws the card
       // without recomposing it. Cards that never scale keep the plain layer and allocate nothing.
       val scaleModifier = if (focusedScale == 1f && pressedScale == 1f) {
           Modifier.graphicsLayer(scaleX = 1f, scaleY = 1f)
       } else {
           val scale = animateFloatAsState(
               targetValue = when {
                   pressed -> pressedScale
                   focused -> focusedScale
                   else -> 1f
               },
               animationSpec = FetchMotion.focusTween(),
               label = "cardScale"
           )
           Modifier.graphicsLayer {
               scaleX = scale.value
               scaleY = scale.value
           }
       }
   ```
7. Replace line 112, `                .graphicsLayer(scaleX = 1f, scaleY = 1f)`, with
   `                .then(scaleModifier)`.
8. Add `import com.fetch.tv.ui.theme.FetchMotion`.
9. In the KDoc (:47-49), replace `no scale, no glow` with
   `no glow, an optional [focusedScale]`. Replace
   `` `CardDefaults.scale(focusedScale = 1f)` `` with `` `CardDefaults.scale(focusedScale = ...)` ``.

### Step 2: `ui/components/ContentCard.kt`

10. At :305 replace `        Card(` with `        LightweightCard(`. Leave the `onClick` lambda
    and the whole `modifier = ...` chain (:306-378) unchanged.
11. Replace :379-390 (from `shape = CardDefaults.shape(...)` through
    `scale = CardDefaults.scale(...)`) with:
    ```kotlin
                shape = cardShape,
                containerColor = Color.Transparent,
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(posterCardStyle.focusedBorderWidth),
                    shape = cardShape
                ),
                focusedScale = posterCardStyle.focusedScale
    ```
    If plan 016 ran, the last line is instead
    `focusedScale = if (FetchTheme.fullMotion) posterCardStyle.focusedScale else FetchMotion.tokens.reducedMotionScale`.
12. Remove the imports `androidx.tv.material3.Card` (:70) and
    `androidx.tv.material3.CardDefaults` (:71). Keep `androidx.tv.material3.Border`.

### Step 3: `ui/components/GridContentCard.kt`

13. At :116 replace `        Card(` with `        LightweightCard(`.
14. Replace :180-191 with:
    ```kotlin
                shape = cardShape,
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(posterCardStyle.focusedBorderWidth),
                    shape = cardShape
                ),
                focusedScale = posterCardStyle.focusedScale
    ```
    Apply the same 016 note as Step 11.
15. Remove the imports `androidx.tv.material3.Card` (:49) and
    `androidx.tv.material3.CardDefaults` (:50).

### Step 4: `ui/components/CollectionRowSection.kt` (`FolderCard`)

16. At :264 replace `    Card(` with `    LightweightCard(`.
17. Replace :274-285 with:
    ```kotlin
            shape = shape,
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(posterCardStyle.focusedBorderWidth),
                shape = shape
            ),
            focusedScale = posterCardStyle.focusedScale
    ```
    Apply the same 016 note as Step 11.
18. Remove the import `androidx.tv.material3.CardDefaults` (:67). Also remove
    `import androidx.tv.material3.Card` if `grep -n "\bCard(" CollectionRowSection.kt` finds
    no other use.

### Step 5: A/B the scale feel

19. Compare against an ee8dd84 build, side by side if possible, at 1x and at Animator
    duration scale 5x. Focus-move across a Library grid row.
    - The poster must grow to the same 1.04 size.
    - It should arrive in about the same time, with no visible lag behind the focus ring.
    - If the new 180ms tween reads as snappier or flatter than tv-material's, try
      `FetchMotion.focusSpring()` as the `animationSpec` in Step 6 and pick whichever matches
      the old feel more closely. Record the choice. Do not invent a new duration.

## Boundaries

- Do NOT change `ModernHomeRows.kt`, `ContinueWatchingSection.kt` or `EpisodesSection.kt`.
  Their `LightweightCard` calls keep the default scale of 1f.
- Do NOT touch anything inside the cards' content lambdas: images, trailer, overlays,
  labels. Plan 015 owns ContentCard's trailer cover.
- Do NOT change any card's `modifier` chain, focus handling or long-press logic.
- Do NOT add dependencies or change `tvMaterial` in `gradle/libs.versions.toml`.
- If Step 0 does not confirm per-frame recomposition, make **no** code changes.
- If an excerpt does not match (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes. No
    literals are added.
  - No unit test covers these cards (`grep -rln "GridContentCard\|LightweightCard" app/src/test`
    is empty). Run the full `./gradlew :app:testFullDebugUnitTest` once to catch anything
    indirect.
- **Feel check** (TV or emulator):
  - Library grid, See-all, Search results rows, More-like-this on a detail page, a
    collection folder row:
    - Focus grows the poster to 1.04 with the focus ring.
    - The focused card draws above its neighbours, with the z-index intact.
    - Select opens the title.
    - Holding select or pressing Menu opens poster options, and does not also open the
      title.
  - Hold D-pad right for 3 seconds of key-repeat across a Library row. Focus keeps up with
    no dropped frames. Compare Profile GPU rendering bars against ee8dd84: the bars should
    be lower.
  - Pressing select on a focused poster briefly returns it to 1.0, as before (tv-material's
    pressed scale).
  - With "Animation off", posters do not scale (if 016 ran), and focus and select still
    work.
- **Performance**: repeat Step 0's Layout Inspector run. Each card should now rise by 1-2
  recompositions per focus move, matching the Home `LightweightCard` control.
- **Done when**:
  - Either Status is WONTFIX with Step 0's counts recorded here, or all three cards use
    `LightweightCard`, show 1-2 recompositions per focus move, keep the 1.04 focus scale,
    and the build is green.
