# Prebuilt Media3 and QuickJS forks

`app/libs/` holds prebuilt AARs inherited from NuvioTV. This note records what they change
compared with the published libraries, where Fetch depends on those changes, and the options
for replacing them (issue #1). It was put together by listing and diffing the classes in each
AAR against Media3 1.8.0 and quickjs-kt 1.0.5.

## Media3 (`lib-common`, `lib-datasource`, `lib-datasource-okhttp`, `lib-exoplayer`, `lib-exoplayer-hls`, `lib-extractor`)

`app/build.gradle.kts` excludes the stock `media3-common`, `-datasource`, `-datasource-okhttp`,
`-exoplayer`, `-exoplayer-hls` and `-extractor` modules everywhere so these AARs win. The other
Media3 modules (dash, smoothstreaming, rtsp, decoder, session, container, database, ui) are
stock 1.8.0 from Maven and run against the forked `lib-common`.

### What the fork adds

| Change | Where in the fork | Used by Fetch |
|---|---|---|
| `NuvioEngineConfig`: process-wide switch between "stock" and "nuvio" mode (allocation `STOCK_HEAP`/`NATIVE_OFF_HEAP`, data pipeline `STOCK`/`ZERO_COPY`, extractor scratch 4 KB/64 KB) | `lib-common` | `FetchExoPlayerPerformanceHelper.applyEngineConfig`, `PlayerMediaSourceFactory`, `PlayerRuntimeControllerInitialization` (the "performance mode" setting) |
| Native off-heap allocator: `DefaultAllocatorNative` (JNI arena, `libmedia3_exoplayer_jni.so`), `Allocation.buffer`/`nativeHandle`, a 4-argument `DefaultAllocator(…, forceNative)` | `lib-exoplayer` | `PlayerRuntimeControllerInitialization`, `FetchExoPlayerPerformanceHelper`, `TrailerPlayerPool`, `ParallelRangeDataSource` |
| `SampleDataQueueNative`: direct-buffer copies into native allocations | `lib-exoplayer` | Internal to the fork |
| `ByteBufferDataReader`: `read(ByteBuffer, Int)` on data sources, implemented by most stock data sources | `lib-common`, `lib-datasource`, `-okhttp`, `-hls`, `lib-extractor` | `ParallelRangeDataSource`, `LoggingDataSource`, `StreamRedirectReporting` |
| `LocalhostZeroCopyDataSource`, and `DefaultDataSource` routing loopback URLs to it in zero-copy mode | `lib-datasource` | Indirect: local proxies (torrent, debrid) in performance mode |
| Extractor scratch size taken from `NuvioEngineConfig` (`DefaultExtractorInput`) | `lib-extractor` | Indirect |

The four decoder AARs (`av1`, `ffmpeg`, `iamf`, `mpegh`), `lib-ui` and `nextlib-mediainfo`
use no fork-only API. `lib-ui-release.aar` is not referenced by any build script; the app uses
stock `media3-ui`. `ffmpeg-decoder-downmix/` is built from source in this repo and compiles
against `lib-exoplayer` (compile-only).

Tests that exist only because of the fork: `app/src/test/java/androidx/media3/**` (4 files),
`app/src/androidTest/java/androidx/media3/**` (6 files) and `ParallelRangeChunkLeaseTest`.

### Options

There is no source for the fork, so "build it in this repo" means rewriting the changes on
top of Media3 1.8.0 source, not rebuilding these AARs.

1. **Drop the fork (stock Media3 1.8.0).** Replace the 4-argument `DefaultAllocator` with the
   stock constructor, remove `NuvioEngineConfig` and the `ByteBufferDataReader` paths (byte
   array reads only), use heap `Allocation`s in `ParallelRangeDataSource`, and delete the fork
   tests. About 10 files change. "Performance mode" becomes a no-op or is removed. Risk: more
   Java-heap pressure and GC pauses on 1–1.5 GB devices with large remuxes, which is what the
   native allocator was for.
2. **Keep a small fork built from source** (`media3-fork/`, Media3 1.8.0 plus a patch): only
   the native allocator and the `forceNative` flag, which are the changes with a plausible
   memory benefit. Zero-copy reads and the larger extractor scratch buffer are
   micro-optimisations worth dropping. This needs the NDK build for the allocator's JNI code
   and a patch to maintain on every Media3 upgrade.
3. **Keep the prebuilt AARs** until a device comparison exists.

Recommendation: measure first. On a Fire TV Stick (1 GB) and a Chromecast with Google TV,
play a large 4K DV/HDR remux with passthrough, once in performance mode and once without
(performance mode off is effectively stock behaviour). If there is no meaningful difference
in dropped frames, rebuffering or heap use, take option 1; otherwise option 2.

## QuickJS (`quickjs-kt-android-1.0.5-nuvio.aar`, `full` flavor only)

Compared with the published `io.github.dokar3:quickjs-kt-android:1.0.5`:

- Same classes and public API, plus `QuickJs.evaluationTimeoutMillis`, `requestInterrupt()`
  and `clearInterrupt()`, backed by new JNI functions and an interrupt handler.
- The native library is built on QuickJS-ng (it reports "QuickJS-ng memory usage" and has
  ng-only features such as `Error.captureStackTrace` and `stackTraceLimit`). The published
  artifact bundles Bellard's QuickJS 2025-09-13.

`PluginRuntime` uses none of the added APIs; it bounds plugins with a coroutine
`withTimeout`. The published artifact is already declared in `libs.versions.toml`
(`quickjs-kt`) but not used. Switching is a one-line dependency change. It should be
checked against the scraper plugins people actually use, because a script that relies on
QuickJS-ng behaviour would break, and a script stuck in a busy loop can't be interrupted by
either build today.
