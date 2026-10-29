#!/usr/bin/env bash
# Run instrumentation only in an explicitly selected, already-active non-owner Android user.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

fail() { echo "DEVICE CHECK BLOCKED: $*" >&2; exit 2; }
serial="${TINGYU_TEST_DEVICE:-}"
test_user="${TINGYU_TEST_USER:-}"
test_classes="${TINGYU_TEST_CLASSES:-}"
screenshots="${TINGYU_TEST_SCREENSHOTS:-false}"

[[ "$serial" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]*$ ]] || fail 'Set TINGYU_TEST_DEVICE to an explicit adb device serial.'
[[ "$test_user" =~ ^[1-9][0-9]*$ ]] || fail 'Set TINGYU_TEST_USER to a nonzero Android test-user ID; owner user 0 is never allowed.'
[[ -z "$test_classes" || "$test_classes" =~ ^[A-Za-z0-9_.#,]+$ ]] || fail 'TINGYU_TEST_CLASSES must contain class names, optional #method names, and commas only.'
[[ "$screenshots" == true || "$screenshots" == false ]] || fail 'TINGYU_TEST_SCREENSHOTS must be true or false.'

app_apk='app/build/outputs/apk/debug/app-debug.apk'
test_apk='app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
[[ -s "$app_apk" && -s "$test_apk" ]] || fail 'Debug app/test APKs are missing. Run bash scripts/check.sh first.'
command -v adb >/dev/null 2>&1 || fail 'adb is unavailable. Load .tooling/env.sh or add Android platform-tools to PATH.'

assert_current_user() {
  local current_user
  current_user="$(adb -s "$serial" shell am get-current-user)" || fail 'Cannot read the current Android user.'
  current_user="${current_user//$'\r'/}"
  [[ "$current_user" == "$test_user" ]] || fail "Current Android user is not the explicitly selected test user $test_user. Switch to the intended temporary test user yourself."
}

assert_current_user
adb -s "$serial" install --user "$test_user" -r "$app_apk"
adb -s "$serial" install --user "$test_user" -r "$test_apk"
# A foreground-user change while installing must not silently redirect the test session.
assert_current_user

instrument=(adb -s "$serial" shell am instrument --user "$test_user" -w)
if [[ -n "$test_classes" ]]; then instrument+=(-e class "$test_classes"); fi
if [[ "$screenshots" == true ]]; then instrument+=(-e screenshots true); fi
instrument+=(app.tingyu.test/androidx.test.runner.AndroidJUnitRunner)

umask 077
log_file="$(mktemp "${TMPDIR:-/tmp}/tingyu-device-check.XXXXXX")"
trap 'rm -f "$log_file"' EXIT
instrument_status=0
"${instrument[@]}" >"$log_file" 2>&1 || instrument_status=$?
cat "$log_file"
if [[ "$instrument_status" -ne 0 ]] ||
   LC_ALL=C grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_RESULT: (shortMsg|longMsg)=' "$log_file" ||
   ! LC_ALL=C grep -Eq '^OK \([1-9][0-9]* tests?\)[[:space:]]*$' "$log_file"; then
  echo 'DEVICE CHECK FAILED: instrumentation did not report a complete, successful nonempty test run.' >&2
  exit 1
fi
