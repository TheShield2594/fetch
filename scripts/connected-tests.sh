#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator (ci.yml, "Instrumented tests (emulator)").
#
# The hosted-runner emulator sometimes hangs or drops off adb for reasons unrelated to the code
# under test (#490). This bounds each attempt and retries once, but only when the failure came
# from adb or the device: a failing test assertion fails the job straight away.
#
# Usage: scripts/connected-tests.sh [gradle task...]   (default :app:connectedDebugAndroidTest)
set -uo pipefail

tasks=("$@")
[ ${#tasks[@]} -gt 0 ] || tasks=(:app:connectedDebugAndroidTest)

# A normal run takes about 4 minutes; a hung one used to run until the 60-minute job limit.
attempt_timeout="${CONNECTED_TESTS_TIMEOUT:-15m}"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT

# Matches the Gradle output of a failed assertion or a crashed test, which must not be retried.
test_failure='There were failing tests|Test run failed to complete|tests? completed, [0-9]+ failed'
# Matches adb and device failures: the emulator going offline while installing or uninstalling,
# or never answering.
device_failure='device offline|device .* not found|no connected devices|AdbCommandRejectedException|ShellCommandUnresponsiveException|DeviceException|InstallException|EOF while reading|connection reset'

run() {
  timeout --kill-after=1m "$attempt_timeout" ./gradlew --no-daemon "${tasks[@]}" 2>&1 | tee "$log"
}

wait_for_device() {
  adb kill-server >/dev/null 2>&1 || true
  adb start-server >/dev/null 2>&1 || true
  timeout 5m adb wait-for-device || return 1
  for _ in $(seq 1 60); do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && return 0
    sleep 5
  done
  return 1
}

run
status=$?
[ $status -eq 0 ] && exit 0

if [ $status -eq 124 ] || [ $status -eq 137 ]; then
  reason="the run passed the $attempt_timeout limit"
elif grep -Eq "$test_failure" "$log"; then
  echo "::error::Instrumented tests failed; not retrying a test failure."
  exit $status
elif grep -Eiq "$device_failure" "$log"; then
  reason="adb or the emulator failed: $(grep -Eio "$device_failure" "$log" | head -1)"
else
  echo "::error::connectedAndroidTest failed for a reason that is neither a test failure nor a device error; not retrying."
  exit $status
fi

echo "::warning::Retrying the instrumented tests once: $reason."
if ! wait_for_device; then
  echo "::error::The emulator did not come back after: $reason."
  exit $status
fi
run
