#!/usr/bin/env python3
"""Offline transfer fault tests. No JDK, Android SDK or network is required."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PAYLOAD = b"verified-toolchain-archive"

# Simulates a CDN and curl's output-file behavior at a real process boundary.
# Each request may time out after a byte budget. Retrying without a Range starts
# from zero; a resume request can make forward progress across invocations.
FAKE_CURL = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
destination = pathlib.Path(args[args.index('--output') + 1])
resuming = '--continue-at' in args
offset = destination.stat().st_size if resuming and destination.exists() else 0
state_file = pathlib.Path(os.environ['TRANSFER_STATE'])
state = json.loads(state_file.read_text()) if state_file.exists() else []
state.append(offset)
state_file.write_text(json.dumps(state))
payload = bytes.fromhex(os.environ['TRANSFER_PAYLOAD'])
scenario = os.environ.get('TRANSFER_SCENARIO', 'success')
status, http = 0, '200'
if scenario in ('no-range', 'stale-range') and len(state) == 1 and offset:
    status, http = (33, '200') if scenario == 'no-range' else (22, '416')
elif scenario == 'unavailable':
    status, http = 6, '000'
else:
    budget = int(os.environ.get('TRANSFER_BUDGET', len(payload)))
    chunk = payload[offset:offset + budget]
    with destination.open('ab' if resuming else 'wb') as output:
        output.write(chunk)
    if offset + len(chunk) < len(payload):
        status = 28
    if offset:
        http = '206'
if '--write-out' in args:
    sys.stdout.write(http)
raise SystemExit(status)
'''


class ToolchainDownloadTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        curl = self.root / 'curl'
        curl.write_text(FAKE_CURL)
        curl.chmod(0o755)
        # Retry backoff is unrelated to transfer correctness.
        sleep = self.root / 'sleep'
        sleep.write_text('#!/bin/sh\nexit 0\n')
        sleep.chmod(0o755)
        self.destination = self.root / 'archive.zip'
        self.partial = self.root / 'archive.zip.part'
        self.state = self.root / 'requests.json'
        self.environment = dict(os.environ, PATH=f'{self.root}:{os.environ["PATH"]}',
                                TRANSFER_STATE=str(self.state), TRANSFER_PAYLOAD=PAYLOAD.hex())

    def download(self, resume=True, **environment):
        self.environment.update(environment)
        return subprocess.run(
            ['bash', '-c', 'set -euo pipefail; source "$1"; download "https://cdn.example/tool.zip" "$2" "$3"',
             'download-test', str(ROOT / 'scripts/lib/download.sh'), str(self.destination),
             '--resume' if resume else ''],
            env=self.environment, capture_output=True, text=True, timeout=10,
        )

    def offsets(self):
        return json.loads(self.state.read_text())

    def test_slow_transfer_completes_across_timeouts(self):
        result = self.download(TRANSFER_BUDGET='9')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(PAYLOAD, self.destination.read_bytes())
        self.assertEqual([0, 9, 18], self.offsets())
        self.assertFalse(self.partial.exists())

    def test_resumes_a_partial_from_an_earlier_invocation(self):
        self.partial.write_bytes(PAYLOAD[:8])
        result = self.download()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([8], self.offsets())
        self.assertEqual(PAYLOAD, self.destination.read_bytes())

    def test_server_without_ranges_restarts_cleanly(self):
        self.partial.write_bytes(PAYLOAD[:8])
        result = self.download(TRANSFER_SCENARIO='no-range')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([8, 0], self.offsets())
        self.assertEqual(PAYLOAD, self.destination.read_bytes())

    def test_unsatisfiable_range_restarts_cleanly(self):
        self.partial.write_bytes(b'x' * 64)
        result = self.download(TRANSFER_SCENARIO='stale-range')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([64, 0], self.offsets())
        self.assertEqual(PAYLOAD, self.destination.read_bytes())

    def test_failed_transfer_preserves_partial_and_existing_archive(self):
        self.partial.write_bytes(PAYLOAD[:8])
        self.destination.write_bytes(b'previous archive')
        result = self.download(TRANSFER_SCENARIO='unavailable')
        self.assertEqual(6, result.returncode)
        self.assertEqual(PAYLOAD[:8], self.partial.read_bytes())
        self.assertEqual(b'previous archive', self.destination.read_bytes())
        self.assertEqual([8, 8, 8], self.offsets())

    def test_metadata_is_replaced_instead_of_resumed(self):
        self.partial.write_bytes(b'outdated metadata')
        result = self.download(resume=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([0], self.offsets())
        self.assertEqual(PAYLOAD, self.destination.read_bytes())


if __name__ == '__main__':
    unittest.main()
