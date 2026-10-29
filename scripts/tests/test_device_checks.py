"""Exercise the actual safe-device runner against an offline adb boundary."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class DeviceChecksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.scripts = Path(__file__).resolve().parents[1]
        (self.root / "scripts").mkdir()
        self.script = self.root / "scripts/check-device.sh"
        shutil.copy2(self.scripts / "check-device.sh", self.script)
        self.app_apk = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        self.test_apk = self.root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        for path in (self.app_apk, self.test_apk):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"offline fixture")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "calls.jsonl"
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("TINGYU_TEST_")}
        self.env.update(PATH=f"{self.bin}:{self.env['PATH']}", DEVICE_CHECK_TEST_ROOT=str(self.root),
                        TINGYU_TEST_DEVICE="test-device", TINGYU_TEST_USER="11", TMPDIR=str(self.root))
        adb = self.bin / "adb"
        adb.write_text("""#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
root=Path(os.environ['DEVICE_CHECK_TEST_ROOT']); args=sys.argv[1:]
with (root/'calls.jsonl').open('a') as out: out.write(json.dumps(args)+'\\n')
if args == ['-s','test-device','shell','am','get-current-user']:
 count_file=root/'current-user-reads'
 count=int(count_file.read_text()) if count_file.exists() else 0
 count_file.write_text(str(count+1))
 print(os.environ.get('DEVICE_CURRENT_USER' if count == 0 else 'DEVICE_LATER_USER',os.environ.get('DEVICE_CURRENT_USER','11')))
 sys.exit(int(os.environ.get('USER_READ_EXIT','0')))
elif args[2:3] == ['install']:
 print('Success')
 sys.exit(int(os.environ.get('INSTALL_EXIT','0')))
elif args[2:5] == ['shell','am','instrument']:
 print(os.environ.get('INSTRUMENT_OUTPUT','OK (5 tests)\\nINSTRUMENTATION_CODE: -1'))
 sys.exit(int(os.environ.get('INSTRUMENT_EXIT','0')))
else:
 print('Unexpected command',file=sys.stderr)
 sys.exit(91)
""")
        adb.chmod(0o755)

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, **env):
        return subprocess.run(["bash", str(self.script)], env=self.env | env,
                              capture_output=True, text=True, timeout=10)

    def commands(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def reset_fake(self):
        self.calls.unlink(missing_ok=True)
        (self.root / "current-user-reads").unlink(missing_ok=True)

    def test_explicit_device_and_user_required_before_any_adb(self):
        for override in ({"TINGYU_TEST_DEVICE": ""}, {"TINGYU_TEST_USER": ""}):
            with self.subTest(override=override):
                result = self.run_script(**override)
                self.assertEqual(2, result.returncode)
                self.assertEqual([], self.commands())

    def test_owner_or_invalid_user_rejected_before_any_adb(self):
        for user in ("0", "00", "-1", "11;echo unsafe", "abc", " 11"):
            with self.subTest(user=user):
                result = self.run_script(TINGYU_TEST_USER=user)
                self.assertEqual(2, result.returncode)
                self.assertEqual([], self.commands())

    def test_missing_apk_rejected_before_any_adb(self):
        self.test_apk.unlink()
        result = self.run_script()
        self.assertEqual(2, result.returncode)
        self.assertIn("APKs are missing", result.stderr)
        self.assertEqual([], self.commands())

    def test_wrong_or_unreadable_current_user_cannot_install_or_run(self):
        for overrides in ({"DEVICE_CURRENT_USER": "0"}, {"DEVICE_CURRENT_USER": "12"}, {"USER_READ_EXIT": "7"}):
            with self.subTest(overrides=overrides):
                self.reset_fake()
                result = self.run_script(**overrides)
                self.assertEqual(2, result.returncode)
                self.assertEqual([["-s", "test-device", "shell", "am", "get-current-user"]], self.commands())

    def test_success_installs_and_runs_only_for_selected_user_without_cleanup(self):
        result = self.run_script(TINGYU_TEST_CLASSES="app.tingyu.ui.FirstTest#caseOne,app.tingyu.ui.SecondTest",
                                 TINGYU_TEST_SCREENSHOTS="true")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("OK (5 tests)", result.stdout)
        self.assertEqual([
            ["-s", "test-device", "shell", "am", "get-current-user"],
            ["-s", "test-device", "install", "--user", "11", "-r", "app/build/outputs/apk/debug/app-debug.apk"],
            ["-s", "test-device", "install", "--user", "11", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"],
            ["-s", "test-device", "shell", "am", "get-current-user"],
            ["-s", "test-device", "shell", "am", "instrument", "--user", "11", "-w", "-e", "class",
             "app.tingyu.ui.FirstTest#caseOne,app.tingyu.ui.SecondTest", "-e", "screenshots", "true",
             "app.tingyu.test/androidx.test.runner.AndroidJUnitRunner"],
        ], self.commands())
        for command in self.commands():
            self.assertTrue({"uninstall", "clear", "remove-user", "delete-user"}.isdisjoint(command))
        self.assertEqual([], list(self.root.glob("tingyu-device-check.*")))

    def test_user_switch_during_install_prevents_instrumentation(self):
        result = self.run_script(DEVICE_LATER_USER="0")
        self.assertEqual(2, result.returncode)
        self.assertEqual(4, len(self.commands()))
        self.assertFalse(any("instrument" in command for command in self.commands()))

    def test_install_failure_does_not_start_instrumentation(self):
        result = self.run_script(INSTALL_EXIT="8")
        self.assertEqual(8, result.returncode)
        self.assertEqual(2, len(self.commands()))
        self.assertFalse(any("instrument" in command for command in self.commands()))

    def test_failure_markers_or_missing_nonempty_success_fail_even_with_adb_exit_zero(self):
        for output in ("FAILURES!!!\nTests run: 5, Failures: 1", "INSTRUMENTATION_FAILED: app.tingyu.test",
                       "INSTRUMENTATION_CODE: -1", "OK (0 tests)", "", "OK (5 tests)\nFAILURES!!!",
                       "OK (5 tests)\nINSTRUMENTATION_RESULT: shortMsg=Process crashed"):
            with self.subTest(output=output):
                self.reset_fake()
                result = self.run_script(INSTRUMENT_OUTPUT=output)
                self.assertEqual(1, result.returncode)
                self.assertIn(output, result.stdout)
                self.assertIn("DEVICE CHECK FAILED", result.stderr)
                self.assertEqual([], list(self.root.glob("tingyu-device-check.*")))

    def test_instrument_process_failure_overrides_printed_success(self):
        result = self.run_script(INSTRUMENT_EXIT="9")
        self.assertEqual(1, result.returncode)
        self.assertIn("OK (5 tests)", result.stdout)

    def test_single_test_success_with_crlf_is_accepted(self):
        result = self.run_script(INSTRUMENT_OUTPUT="OK (1 test)\r\nINSTRUMENTATION_CODE: -1\r")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_optional_arguments_cannot_inject_remote_shell_commands(self):
        for overrides in ({"TINGYU_TEST_CLASSES": "Test; echo unsafe"},
                          {"TINGYU_TEST_CLASSES": "$(echo unsafe)"},
                          {"TINGYU_TEST_SCREENSHOTS": "true; echo unsafe"},
                          {"TINGYU_TEST_DEVICE": "test-device; echo unsafe"}):
            with self.subTest(overrides=overrides):
                result = self.run_script(**overrides)
                self.assertEqual(2, result.returncode)
                self.assertEqual([], self.commands())

    def test_project_device_entry_points_use_safe_runner(self):
        check = (self.scripts / "check.sh").read_text()
        ui = json.loads((self.scripts.parent / "premium-ui.json").read_text())["commands"]["ui"]
        self.assertIn('if [[ "${1:-}" == "--device" ]]; then bash scripts/check-device.sh; fi', check)
        self.assertNotIn("connectedDebugAndroidTest", check)
        self.assertEqual("bash scripts/check-device.sh", ui)


if __name__ == "__main__":
    unittest.main()
