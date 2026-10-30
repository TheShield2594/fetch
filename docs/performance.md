# Low-end performance baseline

Fetch has to stay smooth on 1.5–2 GB streaming sticks. This page says how to measure
that, and holds the numbers once they are measured. Tracking issue: #13.

Target devices:

| Device | RAM | Android | Why |
|---|---|---|---|
| Fire TV Stick (2nd gen) or Fire TV Stick Lite | 1 GB / 1 GB | Fire OS 5–7 (API 22–28) | The weakest common device |
| Chromecast with Google TV HD | 1.5 GB | Android TV 12 (API 31) | The weakest Google TV device |

## What is already in place

- Coil memory cache scales with RAM (15/20/25 %), with an optional RGB565 mode.
- Image disk cache sized to free storage: 5 % of free space plus the current footprint,
  32–200 MB (#44).
- Startup deferral (#44): TV channel sync, server projection and the matcher start from a
  main-looper idle handler, after the first frame.
  `StartupSyncService` stays eager because `MainActivity` injects it.
- Posters are requested at their displayed size. The ambient slideshow logo is capped at
  460×150 dp (#44).
- Playback buffers are capped by `MemoryBudget` (`ui/screens/settings/MemoryBudget.kt`).
  Heaps under 512 MB are the low-RAM tier: the buffer gets 65 % of the heap, less a 210 MB
  reserve for UI, decoders and caches, and stream chunks are capped at 16 MB.
- Baseline profiles (`baselineprofile/`), at most 3 home catalog loads at once, and decoder
  probing for stream ranking off the main thread.

## 1. Cold start

There are two ways to measure it. Use both on each device:

```sh
# Any adb device, release build installed: `am start -W` TotalTime, 10 runs, median.
scripts/perf_baseline.sh cold-start 10

# Macrobenchmark (API 28+): time to initial display and to fully drawn, with and without
# the baseline profile. Results land in baselineprofile/build/outputs/.
scripts/perf_baseline.sh benchmark
```

The Macrobenchmark is `baselineprofile/src/main/java/com/fetch/tv/baselineprofile/StartupBenchmark.kt`.
Fire OS 5/6 sticks are below API 28, so on those only `cold-start` works.

To show what #44's startup deferral bought, measure a build of the commit before #44 and a
build of the current `main`, on the same device, back to back.

Before every run:

- Install a release build (`./gradlew :app:assembleFullRelease`), not debug.
- Complete onboarding, add the default addons and let the home screen load once.
- Reboot, wait about two minutes for the device to settle, then measure.
- Leave the device on mains power with no other apps running.

### Results

Record the build commit and device firmware with each row. These cells stay empty until
someone measures on real hardware. They are not estimates.

| Device | Build | `am start -W` median | TTID (no profile) | TTID (profile) | Notes |
|---|---|---|---|---|---|
| Fire TV Stick Lite | pre-#44 | | n/a (API < 28) | n/a | |
| Fire TV Stick Lite | main | | n/a (API < 28) | n/a | |
| Chromecast with Google TV HD | pre-#44 | | | | |
| Chromecast with Google TV HD | main | | | | |

## 2. Baseline profile

The committed profile (`app/src/main/generated/baselineProfiles/baseline-prof.txt`) was
recorded on upstream NuvioTV and path-renamed to `com/fetch/tv`. It misses code that is new
in Fetch: stream ranking, the fetching panel, the phone hub, and the removal of
Sentry and membership. To regenerate it you need an emulator or device on API 33+, or a rooted
(`userdebug`) one on API 28–32, with a fresh install:

```sh
scripts/perf_baseline.sh generate-profile   # runs :app:generateBaselineProfile
git add app/src/main/generated/baselineProfiles/
```

The generator (`BaselineProfileGenerator.kt`) runs in English and records two profiles. The
app journey goes through first-run onboarding with the D-pad ("Continue without account", then
"Skip for now" or "Continue" on each first-run wizard step, then "Advanced", which opens Home),
scrolls Home and opens a title; it feeds the baseline profile only. The onboarding steps live in
`Journeys.kt`, shared with the benchmarks. Startup then records a cold launch to Home on its
own, which is all the startup profile should hold, so `startup-prof.txt` comes out much smaller than `baseline-prof.txt`. If the two
files are identical, the startup run is recording more than launch. If onboarding labels or
wizard steps change, update `Journeys.kt`. After
regenerating, run `scripts/perf_baseline.sh benchmark` and check that the "profile" column
beats "no profile".

## 3. Heap with Custom Network / Custom Buffers

This relates to the inherited player memory reports in NuvioMedia/NuvioTV#3945. On each device:

1. Settings → Playback → Buffer & Network. Note the defaults, then turn on Custom Playback
   Buffers and Custom Network, and set the target buffer to the slider maximum.
2. Play a high-bitrate file (a 4K HEVC remux of 50 GB or more) for 10 minutes, then seek
   forward and back a few times.
3. Take snapshots while it plays, and once more after going back to Home:

   ```sh
   scripts/perf_baseline.sh heap cgtv-defaults
   scripts/perf_baseline.sh heap cgtv-custom-max
   scripts/perf_baseline.sh heap cgtv-after-exit
   ```

4. Check that:
   - Java Heap stays under `MemoryBudget.budgetMb` plus the 210 MB reserve on the low-RAM
     tier, and never reaches the heap limit. Fetch sets `largeHeap`, so the limit is
     `adb shell getprop dalvik.vm.heapsize`.
   - `adb logcat | grep -iE "OutOfMemory|lowmemorykiller|am_kill.*com.fetch.tv"` shows no
     kills during playback.
   - Memory after exit drops back to about the pre-playback level, so player buffers are
     released.

Record the numbers here:

| Device | Mode | Java Heap (MB) | Native Heap (MB) | Total PSS (MB) | Killed / OOM? |
|---|---|---|---|---|---|
| | Defaults | | | | |
| | Custom, max buffer | | | | |
| | After exit to Home | | | | |

## 4. Frame timing

Measure on each API 28+ device, release build, same preparation as cold start:

```sh
# Home scrolling and opening a detail page, with the baseline profile.
# Results land in baselineprofile/build/outputs/.
scripts/perf_baseline.sh frames
```

The Macrobenchmark is `baselineprofile/src/main/java/com/fetch/tv/baselineprofile/FrameTimingBenchmark.kt`.
It reports `frameDurationCpuMs` and `frameOverrunMs` at P50/P90/P95/P99; a positive overrun is a
missed frame. Play → fetching panel → first frame needs real streams, so measure it by hand:

```sh
adb shell dumpsys gfxinfo com.fetch.tv reset
# Press Play on a title, wait for the first video frame, then:
adb shell dumpsys gfxinfo com.fetch.tv | grep -E "Total frames|Janky frames|percentile"
```

On Fire OS 5/6 sticks (below API 28) use the `dumpsys gfxinfo` route for all three.

Record the numbers here:

| Device | Build | Journey | Frame duration P50 / P90 / P99 (ms) | Frame overrun P90 / P99 (ms) or janky % | Notes |
|---|---|---|---|---|---|
| Fire TV Stick Lite | main | Home scrolling | | | |
| Fire TV Stick Lite | main | Open detail page | | | |
| Fire TV Stick Lite | main | Play → first frame | | | |
| Chromecast with Google TV HD | main | Home scrolling | | | |
| Chromecast with Google TV HD | main | Open detail page | | | |
| Chromecast with Google TV HD | main | Play → first frame | | | |
