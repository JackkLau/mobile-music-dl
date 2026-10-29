#!/usr/bin/env bash
# Install a project-local JDK 17, Android SDK 35, and Gradle 8.11.1.
# No sudo, shell-profile edits, or global package manager changes.
set -euo pipefail

accept_licenses=false
case "${1:-}" in
  --accept-licenses) accept_licenses=true ;;
  "") ;;
  -h|--help)
    echo 'Usage: scripts/bootstrap-android.sh [--accept-licenses]'
    echo 'Downloads verified official toolchain archives into .tooling/.'
    echo 'Set MMD_TOOLCHAIN_DIR to choose a different writable directory.'
    echo '--accept-licenses accepts the Android SDK licenses required by sdkmanager.'
    exit 0 ;;
  *) echo "Unknown argument: $1" >&2; exit 2 ;;
esac

project_root="$(cd "$(dirname "$0")/.." && pwd)"
toolchain_root="${MMD_TOOLCHAIN_DIR:-$project_root/.tooling}"
for tool in curl python3 tar unzip; do
  command -v "$tool" >/dev/null || { echo "Required command missing: $tool" >&2; exit 1; }
done
case "$(uname -s)" in
  Darwin) jdk_os=mac; sdk_os=macosx ;;
  Linux) jdk_os=linux; sdk_os=linux ;;
  *) echo 'Use Android Studio on this operating system.' >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) jdk_arch=aarch64 ;;
  x86_64) jdk_arch=x64 ;;
  *) echo 'Unsupported CPU architecture.' >&2; exit 1 ;;
esac
mkdir -p "$toolchain_root/downloads"
toolchain_root="$(cd "$toolchain_root" && pwd)"

source "$project_root/scripts/lib/download.sh"

verify() {
  python3 - "$1" "$2" "$3" <<'PY'
import hashlib, pathlib, sys
path, algorithm, expected = sys.argv[1:]
digest = hashlib.new(algorithm)
with pathlib.Path(path).open('rb') as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
if digest.hexdigest().lower() != expected.strip().lower():
    raise SystemExit('Checksum mismatch: ' + path)
print('Verified:', pathlib.Path(path).name)
PY
}

if [[ ! -f "$toolchain_root/jdk-home.txt" ]]; then
  echo 'Downloading Eclipse Temurin JDK 17 metadata...'
  download "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=$jdk_arch&image_type=jdk&os=$jdk_os&vendor=eclipse" "$toolchain_root/downloads/jdk.json"
  python3 - "$toolchain_root/downloads/jdk.json" "$toolchain_root/downloads/jdk-package.txt" <<'PY'
import json, pathlib, sys, urllib.parse
assets = json.loads(pathlib.Path(sys.argv[1]).read_text())
package = assets[0]['binary']['package']
if urllib.parse.urlparse(package['link']).scheme != 'https':
    raise SystemExit('JDK download must use HTTPS')
pathlib.Path(sys.argv[2]).write_text(package['link'] + '\n' + package['checksum'] + '\n')
PY
  jdk_url="$(sed -n '1p' "$toolchain_root/downloads/jdk-package.txt")"
  jdk_checksum="$(sed -n '2p' "$toolchain_root/downloads/jdk-package.txt")"
  download "$jdk_url" "$toolchain_root/downloads/jdk.tar.gz" --resume
  verify "$toolchain_root/downloads/jdk.tar.gz" sha256 "$jdk_checksum"
  mkdir -p "$toolchain_root/jdk"
  tar -xzf "$toolchain_root/downloads/jdk.tar.gz" -C "$toolchain_root/jdk"
  python3 - "$toolchain_root" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
homes = list((root/'jdk').glob('*/Contents/Home/bin/java')) + list((root/'jdk').glob('*/bin/java'))
if not homes:
    raise SystemExit('JDK archive has no java executable')
(root/'jdk-home.txt').write_text(str(homes[0].parent.parent) + '\n')
PY
fi
export JAVA_HOME="$(cat "$toolchain_root/jdk-home.txt")"
export PATH="$JAVA_HOME/bin:$PATH"
"$JAVA_HOME/bin/java" -version

export ANDROID_HOME="$toolchain_root/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
  echo 'Downloading Android command-line tools metadata...'
  download 'https://dl.google.com/android/repository/repository2-1.xml' "$toolchain_root/downloads/android-repository.xml"
  python3 - "$toolchain_root/downloads/android-repository.xml" "$sdk_os" "$toolchain_root/downloads/sdk-package.txt" <<'PY'
import pathlib, sys, urllib.parse, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
def local(element): return element.tag.rsplit('}', 1)[-1]
def child(element, name): return next((x for x in element if local(x) == name), None)
package = next((x for x in root if local(x) == 'remotePackage' and x.get('path') == 'cmdline-tools;12.0'), None)
if package is None:
    raise SystemExit('Android repository has no command-line tools 12.0 entry (JDK 17 compatible)')
archives = child(package, 'archives')
for archive in archives:
    host = child(archive, 'host-os')
    if host is not None and host.text == sys.argv[2]:
        complete = child(archive, 'complete')
        relative_url = child(complete, 'url').text
        url = urllib.parse.urljoin('https://dl.google.com/android/repository/', relative_url)
        if urllib.parse.urlparse(url).netloc != 'dl.google.com':
            raise SystemExit('Unexpected Android archive host')
        checksum_node = child(complete, 'checksum')
        digest = checksum_node.text.strip()
        algorithm = checksum_node.get('type') or ('sha256' if len(digest) == 64 else 'sha1')
        pathlib.Path(sys.argv[3]).write_text(url + '\n' + algorithm + '\n' + digest + '\n')
        break
else:
    raise SystemExit('No Android command-line tools for this OS')
PY
  sdk_url="$(sed -n '1p' "$toolchain_root/downloads/sdk-package.txt")"
  sdk_algorithm="$(sed -n '2p' "$toolchain_root/downloads/sdk-package.txt")"
  sdk_checksum="$(sed -n '3p' "$toolchain_root/downloads/sdk-package.txt")"
  download "$sdk_url" "$toolchain_root/downloads/commandline-tools.zip" --resume
  verify "$toolchain_root/downloads/commandline-tools.zip" "$sdk_algorithm" "$sdk_checksum"
  sdk_extract="$(mktemp -d "$toolchain_root/sdk-extract.XXXXXX")"
  unzip -q "$toolchain_root/downloads/commandline-tools.zip" -d "$sdk_extract"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$sdk_extract/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rmdir "$sdk_extract"
fi

if [[ "$accept_licenses" == true ]]; then
  # yes may receive SIGPIPE after sdkmanager exits; retain sdkmanager's status.
  set +e
  yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
  license_status=${PIPESTATUS[1]}
  set -e
  if [[ "$license_status" != 0 ]]; then exit "$license_status"; fi
else
  "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
fi
"$sdkmanager" --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'

export GRADLE_HOME="$toolchain_root/gradle-8.11.1"
if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  download 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256' "$toolchain_root/downloads/gradle.sha256"
  download 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' "$toolchain_root/downloads/gradle.zip" --resume
  verify "$toolchain_root/downloads/gradle.zip" sha256 "$(cat "$toolchain_root/downloads/gradle.sha256")"
  unzip -q "$toolchain_root/downloads/gradle.zip" -d "$toolchain_root"
fi
export GRADLE_USER_HOME="$toolchain_root/gradle-cache"
{
  printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
  printf 'export ANDROID_HOME=%q\n' "$ANDROID_HOME"
  printf 'export ANDROID_SDK_ROOT=%q\n' "$ANDROID_HOME"
  printf 'export GRADLE_HOME=%q\n' "$GRADLE_HOME"
  printf 'export GRADLE_USER_HOME=%q\n' "$GRADLE_USER_HOME"
  printf 'export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$GRADLE_HOME/bin:$PATH"\n'
} > "$toolchain_root/env.sh"
echo "Toolchain ready. Run: source '$toolchain_root/env.sh'"
"$GRADLE_HOME/bin/gradle" --version
