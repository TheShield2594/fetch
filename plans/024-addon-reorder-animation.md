# 024 — Animate addon cards into their new place when reordered

- **Status**: TODO
- **Commit**: ee8dd84
- **Severity**: LOW (missed opportunity)
- **Category**: Missed opportunity, spatial continuity
- **Estimated scope**: 1 file (`AddonManagerScreen.kt`, ~15 changed lines)
- **Depends on**: 001 (`FetchMotion.moveTween()`, `FetchMotion.tokens.durations.move`)

## Problem

In Settings → Addons, each installed addon card has Move up / Move down buttons. Pressing
one swaps the card with its neighbour, and the focused card **teleports**. Focus stays on
its Move button, but nothing shows the user that the card went one slot up or down. Only
the content of the two slots changes, so the swap is easy to misread as "nothing happened"
or "the other addon moved".

`Modifier.animateItem` is used nowhere in the repo:
`grep -rn "animateItem" app/src/main` returns nothing.

```kotlin
// app/src/main/java/com/fetch/tv/ui/screens/addon/AddonManagerScreen.kt:503-537 — current
                itemsIndexed(
                    items = uiState.installedAddons,
                    key = { _, addon -> addon.baseUrl }
                ) { index, addon ->
                    val bringIntoViewRequester = remember { BringIntoViewRequester() }
                    AddonCard(
                        modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester),
                        addon = addon,
                        health = addonHealth[addon.displayName],
                        canMoveUp = index > 0,
                        canMoveDown = index < uiState.installedAddons.lastIndex,
                        onMoveUp = {
                            viewModel.moveAddonUp(addon.baseUrl)
                            addonListScope.launch {
                                kotlinx.coroutines.delay(50)
                                bringIntoViewRequester.bringIntoView()
                            }
                        },
                        onMoveDown = {
                            viewModel.moveAddonDown(addon.baseUrl)
                            addonListScope.launch {
                                kotlinx.coroutines.delay(50)
                                bringIntoViewRequester.bringIntoView()
                            }
                        },
                        ...
```

**Preconditions (verified):**

- **Stable keys.** Items are keyed by `addon.baseUrl` (:505). The reorder in
  `AddonManagerViewModel.kt:366-381` moves the same `Addon` object and persists the order
  through `addonRepository.setAddonOrder(...)`, so a moved card keeps its key and its
  composition. That includes the focused Move button.
- **API.** `gradle/libs.versions.toml:6` uses `composeBom = "2026.05.01"`, so foundation is
  well past 1.7. `LazyItemScope.animateItem(fadeInSpec, placementSpec, fadeOutSpec)` exists,
  and all three specs are nullable. The deprecated `animateItemPlacement` must not be used.

**Existing `animateContentSize()` calls (untokened default spring):**

- `:308`, the "Install addon" card at the top of the list (not an addon item);
- `:1374`, the read-only `AddonCard` `Surface`;
- `:1398`, the editable `AddonCard` `Card`.

These animate a card's *own* height when its content changes, for example health info
arriving or the install card's error. `animateItem` animates *other* items' positions when
something changes size or order, but it does not animate an item's own size. So they don't
overlap: reordering never changes a card's size.

**Decision:** keep the three `animateContentSize`s, which are rare relayouts on a settings
screen. Retime them to `FetchMotion.moveTween()`, so a card growing and its neighbours
sliding (now via `animateItem`) share one duration and curve.

## Target

- Every addon item gets `Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = ...)`,
  where `placementSpec` is:
  - `FetchMotion.moveTween()` (250ms, `CubicBezierEasing(0.77f, 0f, 0.175f, 1f)`) when
    `FetchTheme.fullMotion`;
  - `null` (snap) under reduced motion.
- `fadeInSpec` and `fadeOutSpec` are `null`. Installing or removing an addon still makes it
  appear or vanish instantly, as today, while the others slide to make room or close the
  gap.
- `animateContentSize(animationSpec = FetchMotion.moveTween())` at :308, :1374 and :1398.
- The post-move `bringIntoView` waits for the slide to land, so it scrolls to the card's
  final position, not a mid-flight one.
  - The animated placement offset is part of the item's layout position, so a scroll at
    50ms would target where the card is partway through its move.
  - Under reduced motion, keep the 50ms.

## Repo conventions to follow

- Motion specs come from `FetchMotion` (`ui/theme/MotionFocusTokens.kt`). There are no
  literal durations, and `ThemeLiteralGuardTest` counts `tween(<digits>`.
- Reduced motion: `val fullMotion = FetchTheme.fullMotion`, then branch. Exemplar:
  `ui/screens/player/PlaybackDetailsBar.kt:95-104`.
- `AddonManagerScreen.kt` imports `FetchTheme` (:7) but not `FetchMotion`.

## Steps

All edits are in `app/src/main/java/com/fetch/tv/ui/screens/addon/AddonManagerScreen.kt`.

1. Add `import com.fetch.tv.ui.theme.FetchMotion` next to `import com.fetch.tv.ui.theme.FetchTheme` (:7).

2. In the `itemsIndexed(...) { index, addon ->` lambda (:506), replace
   ```kotlin
                       val bringIntoViewRequester = remember { BringIntoViewRequester() }
                       AddonCard(
                           modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester),
   ```
   with
   ```kotlin
                       val bringIntoViewRequester = remember { BringIntoViewRequester() }
                       val fullMotion = FetchTheme.fullMotion
                       // Scroll once the card has landed, so bringIntoView targets its final slot.
                       val settleMillis = if (fullMotion) FetchMotion.tokens.durations.move.toLong() else 50L
                       AddonCard(
                           // Moved cards slide to their new slot; added/removed cards still appear and vanish at once.
                           modifier = Modifier
                               .animateItem(
                                   fadeInSpec = null,
                                   placementSpec = if (fullMotion) FetchMotion.moveTween() else null,
                                   fadeOutSpec = null
                               )
                               .bringIntoViewRequester(bringIntoViewRequester),
   ```
   `animateItem` must be the first modifier on the item's root. `AddonCard` passes
   `modifier` straight to its `Surface`/`Card`, which is the item root (:1373, :1397).

3. In the same lambda, replace both occurrences of
   `                                kotlinx.coroutines.delay(50)` (in `onMoveUp` :517 and
   `onMoveDown` :524) with `                                kotlinx.coroutines.delay(settleMillis)`.
   The literal `50` is not a `tween(` literal, so the guard baseline is unaffected.

4. Replace `.animateContentSize()` with `.animateContentSize(animationSpec = FetchMotion.moveTween())`
   at :308, :1374 and :1398. The first two are written `.animateContentSize(),`, the third
   `.animateContentSize()` followed by a newline.

## Focus must follow the moved card

- Focus sits on the Move up/down `Button` inside the moved card. Because the item is keyed,
  its composition, and so its focused node, moves with it. `animateItem` only offsets
  placement, so focus is not touched.
- Check this in the feel check below.
- **Pre-existing, out of scope:** moving a card to the first or last slot disables its
  Move up or Move down button (`canMoveUp = index > 0`), and tv-material moves focus off a
  disabled button. Note what happens in the PR, but do not change it here.
- **Ordering note.** The order round-trips through `addonRepository.setAddonOrder`, so the
  list updates a moment after the press, not on the same frame. The 250ms settle delay
  starts at the press, so it also covers that round-trip in the common case. If the scroll
  still lands early on a slow device, report it rather than lengthening the delay.

## Boundaries

- Do NOT touch `AddonManagerViewModel.kt`, the reorder logic, `CatalogOrderScreen.kt` (which
  has the same teleporting reorder; that is a separate follow-up) or `AddonHealthPanel.kt`.
- Do NOT add fades to item insertion or removal.
- Do NOT change list keys, padding or arrangement.
- Do NOT add dependencies.
- If an excerpt does not match (drift since ee8dd84), STOP and report.

## Verification

- **Mechanical**:
  - `./gradlew :app:compileFullDebugKotlin` succeeds. If `animateItem` doesn't resolve, the
    compose BOM is older than expected: STOP and report. Do not fall back to
    `animateItemPlacement`.
  - `./gradlew :app:testFullDebugUnitTest --tests '*ThemeLiteralGuardTest*'` passes with an
    unchanged baseline.
  - `./gradlew :app:testFullDebugUnitTest --tests '*AddonHealthSortTest*' --tests '*AddonManagementAccessTest*'`
    passes. These cover this screen's helpers.
- **Feel check** (TV or emulator), in Settings → Addons with at least 4 addons:
  - Focus the 2nd card's Move down and press it.
    - The card slides down one slot while its neighbour slides up, both in 250ms with an
      ease-in-out.
    - Focus stays on the same Move down button in the moved card.
    - Press again: it keeps going.
  - Press Move down 4 times quickly. Each press retargets smoothly from the current
    position, and nothing jumps or restarts from the old slot.
  - Scroll so a card sits at the bottom edge, then move it down. After it lands, the list
    scrolls so the whole card is visible. It does not stop half-scrolled.
  - Set Animator duration scale to 5x and watch one move. Both cards travel and cross
    without overlapping glitches. Z-order during the cross is acceptable either way.
  - With "Animation off", reorders snap into place, focus follows, and the scroll still
    happens.
  - Install, then remove, an addon. The new or removed card appears or vanishes instantly
    while the cards below slide.
  - A card whose health info arrives late grows over 250ms, the same feel as the slides.
- **Done when**:
  - Reordering animates.
  - Focus stays in the moved card.
  - The three `animateContentSize` calls use `moveTween()`.
  - The build is green.
