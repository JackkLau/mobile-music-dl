"""Offline behavior tests: execute the real shell script with fake process boundaries."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest


class ProbeScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        source = Path(__file__).resolve().parents[1] / "probe-bili-download.sh"
        self.script = self.root / "scripts/probe-bili-download.sh"
        shutil.copy2(source, self.script)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "calls.log"
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                        PROBE_TEST_ROOT=str(self.root), ANDROID_SERIAL="test-device",
                        TINGYU_PROBE_TIMEOUT_SECONDS="5")
        self.write_executable(self.bin / "gradle", """#!/usr/bin/env python3
import os,sys
from pathlib import Path
p=Path(os.environ['PROBE_TEST_ROOT'])
with (p/'calls.log').open('a') as f: f.write('gradle '+ ' '.join(sys.argv[1:])+'\\n')
sys.exit(int(os.environ.get('BUILD_EXIT','0')))
""")
        self.write_executable(self.bin / "adb", """#!/usr/bin/env python3
import os,sys
from pathlib import Path
p=Path(os.environ['PROBE_TEST_ROOT']); args=sys.argv[1:]
with (p/'calls.log').open('a') as f: f.write('adb '+ ' '.join(args)+'\\n')
if args == ['devices']:
 print('List of devices attached')
 state=os.environ.get('DEVICE_STATE','device')
 if state != 'missing': print('test-device\\t'+state)
elif args[2:3] == ['install']:
 sys.exit(int(os.environ.get('INSTALL_EXIT','0')))
elif args[2:5] == ['shell','am','start']:
 if 'cancel' in args:
  (p/'cancelled').write_text('true')
 else:
  (p/'run').write_text(args[args.index('run')+1])
 print('Status: ok')
elif args[2:3] == ['logcat']:
 token=(p/'run').read_text()
 print('I/TingYuProbe: run=historical stage=session event=finished exit=0')
 print('I/TingYuProbe: run='+token+' stage=probe event=sample_result http=206')
 result=os.environ.get('PROBE_EXIT','0')
 if result != 'pending': print('I/TingYuProbe: run='+token+' stage=session event=finished exit='+result)
else: sys.exit(3)
""")
        pc = self.root / "probe/build/install/probe/bin/probe"
        pc.parent.mkdir(parents=True)
        self.write_executable(pc, """#!/usr/bin/env python3
import os,sys
print('run='+sys.argv[1]+' stage=session event=started mode=anonymous')
code=int(os.environ.get('PROBE_EXIT','0'))
print('run='+sys.argv[1]+' stage=session event=finished exit='+str(code))
sys.exit(code)
""")

    def tearDown(self):
        self.temp.cleanup()

    def write_executable(self, path, content):
        path.write_text(content)
        path.chmod(0o755)

    def run_script(self, *args, **env):
        return subprocess.run(["bash", str(self.script), *args], env=self.env | env,
                              capture_output=True, text=True, timeout=15)

    def read_calls(self):
        return self.calls.read_text() if self.calls.exists() else ""

    def test_bad_branch_rejected_before_tools(self):
        result = self.run_script("tablet")
        self.assertEqual(2, result.returncode)
        self.assertEqual("", self.read_calls())

    def test_pc_runs_shared_cli_and_propagates_failure(self):
        result = self.run_script("pc", PROBE_EXIT="1")
        self.assertEqual(1, result.returncode)
        self.assertIn(":probe:installDist", self.read_calls())
        self.assertNotIn("adb", self.read_calls())
        self.assertIn("event=finished exit=1", result.stdout)

    def test_missing_or_unauthorized_device_stops_before_build(self):
        for state in ("missing", "unauthorized", "offline"):
            with self.subTest(state=state):
                self.calls.unlink(missing_ok=True)
                result = self.run_script("phone", DEVICE_STATE=state)
                self.assertEqual(2, result.returncode)
                self.assertNotIn("gradle", self.read_calls())
                self.assertNotIn("install", self.read_calls())

    def test_build_failure_stops_before_install(self):
        result = self.run_script("phone", BUILD_EXIT="7")
        self.assertEqual(7, result.returncode)
        self.assertNotIn("install", self.read_calls())

    def test_install_failure_stops_before_start(self):
        result = self.run_script("phone", INSTALL_EXIT="8")
        self.assertEqual(8, result.returncode)
        self.assertNotIn("shell am start", self.read_calls())

    def test_phone_keeps_data_and_filters_historical_logs(self):
        result = self.run_script("phone", "[link](https://b23.tv/ZhRmMnf) ; echo PRIVATE")
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.read_calls()
        self.assertIn("install -r app/build/outputs/apk/debug/app-debug.apk", calls)
        self.assertNotIn("uninstall", calls)
        self.assertNotIn("logcat -c", calls)
        self.assertNotIn("PRIVATE", calls)
        self.assertNotIn("historical", result.stdout)
        logs = list((self.root / "artifacts/probes").glob("*.log"))
        self.assertEqual(1, len(logs))
        self.assertNotIn("historical", logs[0].read_text())
        self.assertIn("http=206", logs[0].read_text())

    def test_phone_failed_probe_returns_failure(self):
        result = self.run_script("phone", PROBE_EXIT="1")
        self.assertEqual(1, result.returncode)
        self.assertFalse((self.root / "cancelled").exists())

    def test_timeout_cancels_current_probe_without_stopping_app(self):
        result = self.run_script("phone", PROBE_EXIT="pending", TINGYU_PROBE_TIMEOUT_SECONDS="1")
        self.assertEqual(124, result.returncode)
        self.assertTrue((self.root / "cancelled").exists())
        self.assertNotIn("force-stop", self.read_calls())

    def test_termination_cancels_active_phone_probe(self):
        with subprocess.Popen(["bash", str(self.script), "phone"],
                              env=self.env | {"PROBE_EXIT": "pending"},
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as process:
            deadline = time.monotonic() + 8
            while not (self.root / "run").exists() and time.monotonic() < deadline:
                time.sleep(.02)
            self.assertTrue((self.root / "run").exists())
            process.send_signal(signal.SIGTERM)
            process.communicate(timeout=5)
            self.assertEqual(143, process.returncode)
            self.assertTrue((self.root / "cancelled").exists())

    def test_invalid_timeout_rejected_before_build(self):
        result = self.run_script("phone", TINGYU_PROBE_TIMEOUT_SECONDS="0")
        self.assertEqual(2, result.returncode)
        self.assertEqual("", self.read_calls())


if __name__ == "__main__":
    unittest.main()
