#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
python3 scripts/verify-source.py
python3 -m unittest discover -s scripts/tests -v
if [[ -f .tooling/env.sh ]]; then source .tooling/env.sh; fi
if ! command -v gradle >/dev/null 2>&1; then
  echo 'BLOCKED: Gradle/JDK/Android SDK required. Run scripts/bootstrap-android.sh first, or use Android Studio.' >&2
  exit 2
fi
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
if [[ "${1:-}" == "--device" ]]; then gradle --no-daemon :app:connectedDebugAndroidTest; fi
