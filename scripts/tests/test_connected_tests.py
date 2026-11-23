"""scripts/connected-tests.sh: which failures of the emulator tests get their one retry (#490)."""

import os
import stat
import subprocess
import tempfile
import textwrap
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "connected-tests.sh")

# A fake ./gradlew: attempt N prints OUTPUT_N and exits with EXIT_N (default 0), or sleeps when
# EXIT_N is "hang". The attempt count is kept in a file next to it.
FAKE_GRADLEW = textwrap.dedent("""\
    #!/bin/sh
    n=$(cat count 2>/dev/null || echo 0); n=$((n+1)); echo $n > count
    eval "out=\\${OUTPUT_$n:-}"; eval "code=\\${EXIT_$n:-0}"
    printf '%s\\n' "$out"
    [ "$code" = hang ] && exec sleep 30
    exit $code
    """)

# A fake adb whose emulator is always booted.
FAKE_ADB = '#!/bin/sh\n[ "$1" = shell ] && echo 1\nexit 0\n'

OFFLINE = "com.android.ddmlib.AdbCommandRejectedException: device offline"
FAILED = "There were failing tests. See the report at: file:///build/reports/index.html"


def _executable(path, text):
    with open(path, "w") as f:
        f.write(text)
    os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)


class ConnectedTestsRetryTest(unittest.TestCase):
    def run_script(self, **attempts):
        with tempfile.TemporaryDirectory() as work:
            bin_dir = os.path.join(work, "bin")
            os.mkdir(bin_dir)
            _executable(os.path.join(work, "gradlew"), FAKE_GRADLEW)
            _executable(os.path.join(bin_dir, "adb"), FAKE_ADB)
            env = dict(os.environ, PATH=bin_dir + os.pathsep + os.environ["PATH"], CONNECTED_TESTS_TIMEOUT="2s")
            env.update({key: str(value) for key, value in attempts.items()})
            result = subprocess.run(["bash", SCRIPT], cwd=work, env=env, capture_output=True, text=True, timeout=60)
            with open(os.path.join(work, "count")) as f:
                return result.returncode, int(f.read())

    def test_a_pass_runs_once(self):
        self.assertEqual(self.run_script(), (0, 1))

    def test_a_failed_test_is_not_retried(self):
        self.assertEqual(self.run_script(OUTPUT_1=FAILED, EXIT_1=1), (1, 1))

    def test_a_device_failure_is_retried_once(self):
        self.assertEqual(self.run_script(OUTPUT_1=OFFLINE, EXIT_1=1), (0, 2))
        self.assertEqual(self.run_script(OUTPUT_1=OFFLINE, EXIT_1=1, OUTPUT_2=OFFLINE, EXIT_2=1), (1, 2))

    def test_a_dropped_run_counts_as_a_device_failure(self):
        log = "Test run failed to complete. Instrumentation run failed due to 'Process crashed.'"
        self.assertEqual(self.run_script(OUTPUT_1=log, EXIT_1=1), (0, 2))

    def test_a_failed_test_wins_over_a_device_failure_in_the_same_log(self):
        self.assertEqual(self.run_script(OUTPUT_1=FAILED + "\n" + OFFLINE, EXIT_1=1), (1, 1))

    def test_zero_failed_tests_is_not_a_test_failure(self):
        # All tests passed, then the uninstall hit an offline device (the #490 run).
        log = "45 tests completed, 0 failed, 3 skipped\n" + OFFLINE
        self.assertEqual(self.run_script(OUTPUT_1=log, EXIT_1=1), (0, 2))

    def test_a_hang_is_retried_unless_a_test_failed_first(self):
        self.assertEqual(self.run_script(EXIT_1="hang"), (0, 2))
        self.assertEqual(self.run_script(OUTPUT_1=FAILED, EXIT_1="hang"), (124, 1))

    def test_any_other_failure_is_not_retried(self):
        self.assertEqual(self.run_script(OUTPUT_1="e: Compilation error", EXIT_1=1), (1, 1))


if __name__ == "__main__":
    unittest.main()
