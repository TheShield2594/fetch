# Animation plans

These plans come from a motion audit of the Android TV app, covering every animation from
launch to exit. The audit measured each one against Emil Kowalski's animation bar, for how it
looks and for performance on low-end TV hardware. Each plan is self-contained: an executor with
no context can carry it out from the plan file alone.

All plans are stamped at commit `ee8dd84`. If the code has drifted, each plan tells the
executor to stop and report rather than improvise.

## Plans

| # | Plan | Severity | Area | Status |
|---|---|---|---|---|
| 001 | [Motion token foundation](001-motion-token-foundation.md) | HIGH (enabler) | Tokens | DONE |
| 002 | [Scroll spring stiffness](002-scroll-spring-stiffness.md) | HIGH | Every row and column | DONE |
| 003 | [Home hero backdrop crossfade](003-home-hero-backdrop-crossfade.md) | HIGH | Home | DONE |
| 004 | [Screen transitions](004-screen-transitions.md) | HIGH | Navigation | DONE |
| 005 | [Skip Intro button](005-skip-intro-button.md) | HIGH | Player | TODO |
| 006 | [Loading overlay](006-loading-overlay.md) | HIGH | Fetching / buffering | TODO |
| 007 | [Detail hero ⇄ trailer morph](007-detail-hero-trailer-morph.md) | HIGH | Detail | TODO |
| 008 | [Post-play surface shrink](008-post-play-surface-shrink.md) | HIGH | Player | TODO |
| 009 | [Skeleton shimmer](009-skeleton-shimmer.md) | HIGH | Loading states | TODO |
| 010 | [Focus labels without relayout](010-focus-labels-no-relayout.md) | MEDIUM | Sidebar, detail actions | TODO |
| 011 | [Subtitle rail fade](011-subtitle-rail-fade.md) | MEDIUM | Player | TODO |
| 012 | [Playback-start overlays](012-playback-start-overlays.md) | MEDIUM | Player | TODO |
| 013 | [Play → Fetching morph](013-play-to-fetching-morph.md) | MEDIUM | Stream | TODO |
| 014 | [Detail backdrop fades and scroll restore](014-detail-backdrop-fades.md) | MEDIUM | Detail | TODO |
| 015 | [Card trailer cover fade](015-card-trailer-cover-fade.md) | MEDIUM | Poster cards | TODO |
| 016 | [Reduced-motion sweep](016-reduced-motion-sweep.md) | MEDIUM | App-wide | TODO |
| 017 | [tv-material Card recomposition](017-tv-card-recomposition.md) | MEDIUM (gated on measurement) | Poster cards | TODO |
| 018 | [Network test spinner](018-network-spinner.md) | LOW | Settings | TODO |
| 019 | [Controls focus delay](019-controls-focus-delay.md) | LOW | Player | TODO |
| 020 | [Motion token cleanup](020-motion-token-cleanup.md) | LOW | Tokens | TODO |
| 021 | [Low-severity polish (a–f)](021-low-polish-items.md) | LOW | Party, ambient, cast, PIN, first-run | TODO |
| 022 | [Edit Profile overlay fade](022-overlay-hard-cuts.md) | LOW-MEDIUM (missed opportunity) | Profiles | TODO |
| 023 | [Up-next countdown fill](023-up-next-countdown-fill.md) | LOW (missed opportunity) | Post-play | TODO |
| 024 | [Addon reorder animation](024-addon-reorder-animation.md) | LOW (missed opportunity) | Addons | TODO |
| 025 | [Best-so-far reveal](025-best-so-far-reveal.md) | LOW (missed opportunity) | Fetching | TODO |

## Recommended execution order

Run the plans one at a time, in this order. Each step builds on the ones before it, and several
plans edit the same files.

1. **001**. Every other plan uses its tokens.
2. **High-frequency feel**, the motion people hit hundreds of times a day: **002 → 003 → 004**.
3. **Loading and fetch path**: **009 → 006 → 013 → 007 → 014**.
4. **Player**, which shares `PlayerScreen.kt`: **019 → 008 → 005**, then **011**, then **012**.
5. **Medium and missed opportunities**: **010 → 015 → 025 → 022 → 023 → 018 → 024**.
6. **Cross-cutting**: **016**, then **017** (if its step 0 measurement says go), then **021**.
7. **020 last**. Its first step checks that every other plan is DONE.

If you only have time for the biggest wins, do **001, 002, 003, 004, 005, 006, 008, 009**. That
covers every D-pad move, every navigation, every fetch and the playback overlays.

## Dependencies and shared files

- **001 comes before everything** except 019 and 023, which use no new tokens.
- **`MainActivity.kt`** is edited by 002 (bring-into-view spec, ~612-617), 004 (sidebar padding,
  ~1664-1680) and 020 (splash fade, ~1282-1305). The regions don't overlap.
- **`PlayerScreen.kt`**: run **019 → 008 → 005** in that order, then 016. Each plan finds its edits
  by quoted text, not by line number. 005 and 008 each remove an `animateDpAsState` use;
  whichever runs last deletes the now-unused import.
- **`StreamScreen.kt` / `FetchingPanel.kt`**: run **013 before 025**.
  - 025 deliberately does **not** animate the Fetching panel height. That height drives 006's
    `bottomInset`, so animating it would restart 006's glide every frame.
- **`MetaDetailsScreen.kt`**: 007 and 014 edit different lines and can run in either order.
- **`ContentCard.kt`**: 015, 016 and 017 edit different regions. **017 runs after 016** and carries
  016's `reducedMotionScale` gating into the new `LightweightCard` call.
- **`ProfileSelectionScreen.kt`**: 016 changes the PIN shake, 021 the PIN cursor, and 022 the Edit
  overlay. Run 016 before 021.
- **`theme-literals-baseline.txt`** (`app/src/test/resources/`) is checked by
  `ThemeLiteralGuardTest`, and a file under its count fails just like one over it.
  - 009 removes the `Skeletons.kt` line.
  - 018 removes the `NetworkSettingsScreen.kt` line.
  - 020 removes the `Shimmer.kt` and `PlaceholderShimmer.kt` lines.
  - No other plan changes the baseline.
- **`MotionFocusTokens.kt`**: 001 adds tokens, 018 adds `spinner` after `hero`, and 013 may add
  `launch`. 020 retires unused tokens last, using a grep it runs at execution time.

## Verification for every plan

- `./gradlew :app:compileFullDebugKotlin`
- `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'`, plus any test class
  the plan names. For example, 013 updates `FetchLaunchMorphTest`.
- A **feel check on a real TV or emulator**:
  - Set Developer options → Animator duration scale to 5× or 10× to watch in slow motion.
  - Set it to "Animation off" to check the reduced-motion path.
  - Hold the D-pad to stress key repeat.
  - Use Layout Inspector recomposition counts for the performance plans.

## Corrections made during planning

Plan writers re-checked every finding against the code. These are where the audit was wrong
or incomplete:

- **006**: the loading logo's "1s fade-in" never runs. `animateFloatAsState(targetValue = 1f)`
  starts at its target, so the code is dead. The plan deletes it rather than retiming it.
- **008**: both video engines (ExoPlayer and mpv) render to a `SurfaceView`, and minSdk is 24.
  - So the shrink keeps a layout resize, cut down to one value read only in layout and draw,
    with a shorter duration.
  - A `graphicsLayer` scale is written up as an API 29+ follow-up, not implemented.
- **012**: the overlay items didn't stagger at all; each fade was awaited one after another.
  The plan makes it a real 50ms stagger.
- **017** is unverified. The tv-material source wasn't available offline, so step 0 measures
  first and the plan becomes WONTFIX if the recomposition doesn't show up.
  - ContentCard is used only via CatalogRowSection. Most browse screens use GridContentCard.
- **022** covers Edit Profile only. The Episode options overlay is a platform `Dialog`, which
  probably already gets a window animation, so check that on a device before planning it.

## Noted, not planned

- `CatalogOrderScreen` has the same teleporting reorder as the addon manager (024 is the template).
- `PlayerScreen.kt:667` has a second fixed `delay(250)` focus wait, the same pattern as 019.
- `LoadingOverlay.kt:172` has hard-coded 160/400ms fill durations that slip past the literal guard.
- The poster backdrop-expand (`ContentCard.kt:189-273`, `ModernHomeRows.kt:1118-1237`) animates
  card width with relayout. On a cache miss it also swaps the image request through a flat
  placeholder. It came up in the audit but isn't in any plan yet.
- In `AddonManagerScreen.kt`, moving an addon to the first or last slot disables the focused
  Move button, so focus jumps (pre-existing, not motion).
