# 010 — Stop focus labels from animating layout, and snap the sidebar circle colour

- **Status**: DONE (build not verified locally: no Android SDK in the session)
- **Commit**: ee8dd84
- **Severity**: MEDIUM
- **Category**: 5. Performance / 1. Purpose & frequency
- **Estimated scope**: 2 files (`ui/components/FloatingSidebarButton.kt`, `ui/screens/detail/HeroSection.kt`), ~60 lines
- **Depends on**: 001 (`FetchMotion.enterTween()`)

## Problem

Two focus labels, the sidebar item names and the Detail hero's round-action names, slide out
beside their icon on every focus change. Both animate their own width with
`expandHorizontally` / `shrinkHorizontally`, which re-measures and re-lays-out the parent row on
every frame.

`app/src/main/java/com/fetch/tv/ui/components/FloatingSidebarButton.kt:131-148` (current):

```kotlin
        AnimatedVisibility(
            visible = isFocused && expanded,
            enter = fadeIn(tween(FetchMotion.tokens.durations.fast)) +
                expandHorizontally(tween(FetchMotion.tokens.durations.fast)),
            exit = fadeOut(tween(FetchMotion.tokens.durations.quick)) +
                shrinkHorizontally(tween(FetchMotion.tokens.durations.quick))
        ) {
            Text(
                text = label,
                color = FetchTheme.colors.TextPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(FetchTheme.colors.BackgroundCard, FetchTheme.shapes.navItem)
                    .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
            )
        }
```

`app/src/main/java/com/fetch/tv/ui/screens/detail/HeroSection.kt:714-736` (current):

```kotlin
@Composable
private fun ActionFocusLabel(text: String, visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(FetchMotion.tokens.durations.fast)) +
            expandHorizontally(tween(FetchMotion.tokens.durations.fast)),
        exit = fadeOut(tween(FetchMotion.tokens.durations.quick)) +
            shrinkHorizontally(tween(FetchMotion.tokens.durations.quick))
    ) {
        Text(
            text = text,
            color = FetchTheme.colors.TextPrimary,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = FetchTheme.spacing.sm)
                .clearAndSetSemantics { }
                .background(FetchTheme.colors.BackgroundCard, FetchTheme.shapes.navItem)
                .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
        )
    }
}
```

`ActionFocusLabel` is called at `HeroSection.kt:604`, from `ActionIconButtonPainter`'s `Row` at
:571, and at `HeroSection.kt:706`, from `ActionIconButton`'s `Row` at :627. Those buttons sit in
the hero action row at `HeroSection.kt:268-339`
(`Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md))`), which holds
Play, Library, Watched (movies), Trailer and Shuffle.

What goes wrong:

- **Hero:** moving Right from Library to Watched, Library's label shrinks over 125ms while
  Watched's grows over 180ms.
  - The Watched button, which now has focus, slides left under the focus ring.
  - Every button after it slides too.
  - The whole hero column re-lays-out for each of those frames.
  - The user is aiming at a moving target on every D-pad step through the actions.
- **Sidebar:** each item is a `Row` in a start-aligned `Column` (`MainActivity.kt:1592-1598`),
  so no sibling moves. The `Row` and `Column` still re-measure every frame, on every focus move
  through the drawer.
- **Sidebar colours** (`FloatingSidebarButton.kt:77-92`, current):

  ```kotlin
      val circleColor by animateColorAsState(
          targetValue = when {
              selected -> FetchTheme.colors.Highlight
              isFocused -> FetchTheme.colors.FocusBackground
              else -> Color.Transparent
          },
          label = "sidebarCircle"
      )
      val iconTint by animateColorAsState(
          targetValue = when {
              selected -> FetchTheme.colors.OnHighlight
              isFocused -> FetchTheme.colors.TextPrimary
              else -> FetchTheme.colors.TextSecondary
          },
          label = "sidebarIconTint"
      )
  ```

  - Both use the default spring and are read in composition: `.background(circleColor, CircleShape)`
    at :126, and `leading(iconTint)` at :129.
  - This recomposes the button on every frame of the spring.
  - The focus ring border snaps on (`if (isFocused) Modifier.border(...)` at :119-123) while the
    fill behind it fades, so ring and fill disagree for a few frames.
  - Sidebar focus moves happen 100+ times a day, so the right amount of colour animation is none.

## Target

- **Labels never animate layout.**
  - The label is added to and removed from layout in a single frame, on the same frame focus
    changes.
  - Only its opacity and an 8dp horizontal translation animate. Both run in `graphicsLayer {}`
    (draw phase, no re-measure, no recomposition per frame).
  - Enter, full motion: alpha 0 → 1 and translationX from 8dp toward the icon to 0, using
    `FetchMotion.enterTween()` (200ms, `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`).
  - Enter, reduced motion (`!FetchTheme.fullMotion`): alpha 0 → 1 with the same tween, and no
    translation.
  - Exit: none. The label leaves the frame focus leaves. On the hero row this matters: a fading
    exit would keep the old label's width for 150ms and then shift every button after it, a
    delayed jump.
- **Why this option.** Each option was checked against each component's design:
  - Sidebar: the label already has its own lane to the right of the icon column, and nothing
    is beside it. Keeping it in layout but no longer animating its size keeps the design exactly.
  - Hero, overlay (no layout space): rejected. A label drawn over the row covers the next
    action button, which is the one the user is about to move to.
  - Hero, reserved width: rejected. Up to four action labels would leave large permanent gaps
    in the row.
  - Hero, chosen: keep the inline label but snap its layout. Neighbours shift once, on the
    frame focus moves, instead of sliding under the ring for 125-180ms. The feel check
    confirms this reads as one change.
- **Sidebar colours snap.** `circleColor` and `iconTint` become plain `when` values, matching the
  ring, which already snaps.
- One shared helper, `Modifier.focusLabelEntrance()`, used by both labels.

## Repo conventions to follow

- Durations and easings come from `FetchMotion` in `app/src/main/java/com/fetch/tv/ui/theme/MotionFocusTokens.kt`.
  Plan 001 adds `enterTween()`.
- Deferred reads: animated values are read only inside `graphicsLayer { }`. Exemplar:
  `app/src/main/java/com/fetch/tv/ui/components/PlaceholderShimmer.kt:46-64`, which reads the
  state inside the draw lambda.
- Reduced motion is `FetchTheme.fullMotion`. Drop movement, keep the short opacity fade.
  Exemplar: `app/src/main/java/com/fetch/tv/ui/screens/player/PlaybackDetailsBar.kt:95-104`.
- Spacing is `FetchTheme.spacing.sm`, which is 8dp (`ui/theme/SpacingTokens.kt:63`). Do not
  write `8.dp`.
- RTL is handled in this codebase by checking `LocalLayoutDirection.current == LayoutDirection.Rtl`,
  as in `ModernHomeHero.kt:225`. `graphicsLayer` translation is not mirrored automatically.

## Steps

1. In `app/src/main/java/com/fetch/tv/ui/components/FloatingSidebarButton.kt`, append this helper
   at the end of the file, after `sidebarFocusEntry`:

   ```kotlin
   /**
    * Brings a focus label in beside its icon: it fades in while sliding [FetchTheme.spacing.sm] out
    * of the icon. Only the draw layer animates, so the label's width never changes frame to frame
    * and nothing beside it moves. Call it on a label that is composed only while it is visible;
    * it leaves at once when removed.
    */
   @Composable
   internal fun Modifier.focusLabelEntrance(): Modifier {
       val fullMotion = FetchTheme.fullMotion
       val progress = remember { Animatable(0f) }
       LaunchedEffect(progress) { progress.animateTo(1f, FetchMotion.enterTween()) }
       val slidePx = with(LocalDensity.current) { FetchTheme.spacing.sm.toPx() }
       // Start toward the icon: the icon is on the start side.
       val startTranslationX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) slidePx else -slidePx
       return graphicsLayer {
           val p = progress.value
           alpha = p
           translationX = if (fullMotion) (1f - p) * startTranslationX else 0f
       }
   }
   ```

2. In the same file, replace the `AnimatedVisibility(...) { Text(...) }` block at lines 131-148
   (quoted in **Problem**) with:

   ```kotlin
           if (isFocused && expanded) {
               Text(
                   text = label,
                   color = FetchTheme.colors.TextPrimary,
                   style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                   maxLines = 1,
                   overflow = TextOverflow.Ellipsis,
                   modifier = Modifier
                       .focusLabelEntrance()
                       .background(FetchTheme.colors.BackgroundCard, FetchTheme.shapes.navItem)
                       .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
               )
           }
   ```

3. In the same file, replace the two `animateColorAsState` blocks at lines 77-92 (quoted in
   **Problem**) with:

   ```kotlin
       // Snaps with the focus ring: focus moves through the sidebar far too often for a colour fade.
       val circleColor = when {
           selected -> FetchTheme.colors.Highlight
           isFocused -> FetchTheme.colors.FocusBackground
           else -> Color.Transparent
       }
       val iconTint = when {
           selected -> FetchTheme.colors.OnHighlight
           isFocused -> FetchTheme.colors.TextPrimary
           else -> FetchTheme.colors.TextSecondary
       }
   ```

4. Fix the imports in `FloatingSidebarButton.kt`:
   - Delete:
     - `androidx.compose.animation.AnimatedVisibility`
     - `androidx.compose.animation.animateColorAsState`
     - `androidx.compose.animation.core.tween`
     - `androidx.compose.animation.expandHorizontally`
     - `androidx.compose.animation.fadeIn`
     - `androidx.compose.animation.fadeOut`
     - `androidx.compose.animation.shrinkHorizontally`
   - Add:
     ```kotlin
     import androidx.compose.animation.core.Animatable
     import androidx.compose.runtime.LaunchedEffect
     import androidx.compose.ui.graphics.graphicsLayer
     import androidx.compose.ui.platform.LocalDensity
     import androidx.compose.ui.platform.LocalLayoutDirection
     import androidx.compose.ui.unit.LayoutDirection
     ```
   - Keep `getValue`, `setValue`, `mutableStateOf` and `remember`. They are still used by `isFocused`.

5. In `app/src/main/java/com/fetch/tv/ui/screens/detail/HeroSection.kt`, replace the body of
   `ActionFocusLabel` (lines 714-736, quoted in **Problem**; keep its KDoc at 710-713) with:

   ```kotlin
   @Composable
   private fun ActionFocusLabel(text: String, visible: Boolean) {
       // In and out of layout in one frame, with focus, so the buttons after it shift once instead of
       // sliding under the focus ring; only the fade and slide animate, in the draw layer.
       if (visible) {
           Text(
               text = text,
               color = FetchTheme.colors.TextPrimary,
               style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
               maxLines = 1,
               overflow = TextOverflow.Ellipsis,
               modifier = Modifier
                   .padding(start = FetchTheme.spacing.sm)
                   .focusLabelEntrance()
                   .clearAndSetSemantics { }
                   .background(FetchTheme.colors.BackgroundCard, FetchTheme.shapes.navItem)
                   .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
           )
       }
   }
   ```

6. Fix the imports in `HeroSection.kt`:
   - Delete `import androidx.compose.animation.expandHorizontally` (line 20) and
     `import androidx.compose.animation.shrinkHorizontally` (line 22).
   - Add `import com.fetch.tv.ui.components.focusLabelEntrance`.
   - Keep `AnimatedVisibility`, `fadeIn`, `fadeOut` and `tween`. They are still used at lines
     231, 248, 262, 407, 527 and 538.

## Boundaries

- Do NOT change the label text styles, colours, shapes or paddings, the icon buttons, the
  focus ring, or the hero action `Row` and its arrangement.
- Do NOT touch the other `AnimatedVisibility` blocks in `HeroSection.kt`: the title, the trailer
  hint, the action column, `upNext`, and the blocks at lines 527 and 538. Other plans own those.
- Do NOT touch `ShuffleButton.kt` or `PlayButton`.
- Do NOT touch `MainActivity.kt`, which hosts the sidebar column. Plans 002, 004 and 020 edit it.
- Do NOT add new dependencies.
- If a step doesn't match the code you find (drift since ee8dd84), STOP and report instead of improvising.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes. Neither file
    is in `theme-literals-baseline.txt` and no literal `tween(<digits>` is added.
  - `./gradlew :app:testFullDebugUnitTest --tests '*SidebarFocusEntryTest*'` passes. It covers
    `sidebarFocusEntry` in the same file.
  - Optional: `FETCH_SCREENSHOTS=1 ./gradlew :app:testFullDebugUnitTest --tests '*ReadmeScreenshots*'`.
    The `sidebar` screenshot focuses "Discover" and advances the clock 1000ms. Its label must
    still render fully opaque.
  - `grep -n "expandHorizontally\|shrinkHorizontally\|animateColorAsState" app/src/main/java/com/fetch/tv/ui/components/FloatingSidebarButton.kt app/src/main/java/com/fetch/tv/ui/screens/detail/HeroSection.kt`
    returns nothing.
- **Feel check** (TV or emulator):
  - Detail page of a movie: from Play, press Right through Library → Watched → Trailer and back
    Left.
    - Each label fades in and slides 8dp out of its icon.
    - The focused button never slides under the ring. Any shift of the buttons after it happens
      in the same frame focus moves.
    - Compare against the old build: the new one should feel steadier. If the one-frame shift
      still reads as a jump, report it in the PR rather than adding animation back.
  - Open the sidebar (Left from Home) and press Down through every item.
    - Each label appears beside its icon with the same fade and slide.
    - The circle fill and the ring appear together. The fill no longer trails the ring.
  - Set Animator duration scale to 5x:
    - The labels' 8dp slide and fade run together and finish together.
    - Nothing else moves while they run.
  - RTL (set a right-to-left locale): labels slide out from the icon side, leftward from the icon.
  - Reduced motion, with Animator duration scale at "Animation off": labels appear at once with
    no slide. On a low-RAM device they fade with no slide.
  - Performance: in Layout Inspector, hold Down through the sidebar. Recomposition counts for
    `FloatingSidebarButton` rise by one or two per focus move, not by one per frame.
- **Done when**:
  - Neither label uses `AnimatedVisibility`, `expandHorizontally` or `shrinkHorizontally`.
  - Both labels use `focusLabelEntrance()`.
  - Sidebar colours are plain values.
  - The build and tests are green.
