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
  check-profile         Check the committed profiles: start-up differs from baseline, Fetch code covered
  benchmark             Run the cold-start Macrobenchmark (StartupBenchmark) on a connected device
  benchmark-emulator    The same on an emulator, where the numbers only show profile against no profile
  frames                Run the frame-timing Macrobenchmark (FrameTimingBenchmark): Home scrolling
                        and opening a detail page, on a connected device
  cold-start [runs]     Measure cold start with 'am start -W' on the installed app (default 10 runs)
                        and print TotalTime per run plus the median; works on any adb device
  restart-check [secs]  Force-stop the app, wait (default 5 s), and report whether its process came
                        back and what started it (ActivityManager "Start proc" lines, services, jobs)
  heap [label]          Snapshot 'dumpsys meminfo' for the running app into perf/heap-<label>.txt
  release-with-profile  Build release APK/AAB with the merged baseline profile
USAGE
}

cmd="${1:-}"

case "$cmd" in
  generate-profile)
    ./gradlew :app:generateBaselineProfile
    ;;
  check-profile)
    python3 "$(dirname "$0")/check_baseline_profiles.py"
    ;;
  benchmark)
    ./gradlew \
      :baselineprofile:connectedBenchmarkReleaseAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.fetch.tv.baselineprofile.StartupBenchmark \
      -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
    ;;
  benchmark-emulator)
    # Macrobenchmark refuses to run on an emulator unless told the numbers are not for the record.
    ./gradlew \
      :baselineprofile:connectedBenchmarkReleaseAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.fetch.tv.baselineprofile.StartupBenchmark \
      -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
      -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
    python3 "$(dirname "$0")/summarise_benchmark.py" baselineprofile/build/outputs
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
  restart-check)
    # Issue #461: does anything bring the process back after it is killed?
    wait_secs="${2:-5}"
    adb logcat -c
    adb shell am force-stop "$PACKAGE"
    sleep "$wait_secs"
    pid="$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)"
    if [ -z "$pid" ]; then
      echo "not running ${wait_secs}s after force-stop"
    else
      echo "running again ${wait_secs}s after force-stop (pid $pid)"
    fi
    echo "--- process starts since the force-stop"
    adb logcat -d -s ActivityManager:I | tr -d '\r' | grep "Start proc.*$PACKAGE" || echo "(none logged)"
    echo "--- services"
    adb shell dumpsys activity services "$PACKAGE" | tr -d '\r' | grep -E "ServiceRecord|app=|startRequested|createdFromFg" || echo "(none)"
    echo "--- jobs"
    adb shell dumpsys jobscheduler | tr -d '\r' | grep -A3 "$PACKAGE" | head -40 || echo "(none)"
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
