#!/usr/bin/env bash
# Low-end performance tooling. See docs/performance.md for what to record and where.
set -euo pipefail

PACKAGE="com.fetch.tv"
ACTIVITY="$PACKAGE/.MainActivity"

usage() {
  cat <<USAGE
Usage: $0 <command> [args]

Commands:
  generate-profile      Regenerate app/src/main/generated/baselineProfiles from a Fetch build
                        (connected device or emulator, API 28+, first-run onboarding reachable)
  benchmark             Run the cold-start Macrobenchmark (StartupBenchmark) on a connected device
  frames                Run the frame-timing Macrobenchmark (FrameTimingBenchmark): Home scrolling
                        and opening a detail page, on a connected device
  cold-start [runs]     Measure cold start with 'am start -W' on the installed app (default 10 runs)
                        and print TotalTime per run plus the median; works on any adb device
  heap [label]          Snapshot 'dumpsys meminfo' for the running app into perf/heap-<label>.txt
  release-with-profile  Build release APK/AAB with the merged baseline profile
USAGE
}

cmd="${1:-}"

case "$cmd" in
  generate-profile)
    ./gradlew :app:generateBaselineProfile
    ;;
  benchmark)
    ./gradlew \
      :baselineprofile:connectedBenchmarkReleaseAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.fetch.tv.baselineprofile.StartupBenchmark \
      -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
    ;;
  frames)
    ./gradlew \
      :baselineprofile:connectedBenchmarkReleaseAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.fetch.tv.baselineprofile.FrameTimingBenchmark \
      -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
    ;;
  cold-start)
    runs="${2:-10}"
    activity="$(adb shell cmd package resolve-activity --brief "$PACKAGE" | tail -n 1 | tr -d '\r')"
    activity="${activity:-$ACTIVITY}"
    times=()
    for i in $(seq 1 "$runs"); do
      adb shell am force-stop "$PACKAGE"
      sleep 2
      total="$(adb shell am start -W -n "$activity" | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}')"
      echo "run $i: ${total} ms"
      times+=("$total")
      sleep 3
    done
    printf '%s\n' "${times[@]}" | sort -n | awk '{a[NR]=$1} END {m=(NR%2)?a[(NR+1)/2]:(a[NR/2]+a[NR/2+1])/2; print "median: " m " ms over " NR " runs"}'
    ;;
  heap)
    label="${2:-$(date +%Y%m%d-%H%M%S)}"
    mkdir -p perf
    adb shell dumpsys meminfo "$PACKAGE" | tr -d '\r' > "perf/heap-$label.txt"
    grep -E "TOTAL PSS|Java Heap|Native Heap|Graphics" "perf/heap-$label.txt" || true
    echo "saved perf/heap-$label.txt"
    ;;
  release-with-profile)
    ./gradlew :app:assembleRelease :app:bundleRelease
    ;;
  *)
    usage
    exit 1
    ;;
esac
