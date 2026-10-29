#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
usage() {
  echo '用法: bash scripts/probe-bili-download.sh pc|phone [视频链接或分享文本]' >&2
  echo '手机默认序列号 7c1213a1；可用 ANDROID_SERIAL 指定设备。PC 使用匿名模式，手机使用应用当前访问模式。' >&2
}
if [[ $# -lt 1 || $# -gt 2 || ( "$1" != pc && "$1" != phone ) ]]; then usage; exit 2; fi
mode="$1"
input="${2:-https://www.bilibili.com/video/BV1sVtw6JEeq/?vd_source=1b2cad1e91ade28f394b35922f927023}"
if [[ -f .tooling/env.sh ]]; then source .tooling/env.sh; fi
command -v python3 >/dev/null || { echo '需要 Python 3。' >&2; exit 2; }
timeout_seconds="${TINGYU_PROBE_TIMEOUT_SECONDS:-240}"
if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]*$ || ${#timeout_seconds} -gt 4 || "$timeout_seconds" -gt 1800 ]]; then
  echo 'TINGYU_PROBE_TIMEOUT_SECONDS 必须是 1 到 1800 的整数。' >&2; exit 2
fi
run_id="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
output_dir="${TINGYU_PROBE_OUTPUT_DIR:-$project_dir/artifacts/probes}"
mkdir -p "$output_dir"
log_file="$output_dir/$mode-$run_id.log"
phone_started=0
process_id=''
serial="${ANDROID_SERIAL:-7c1213a1}"
adb_target() { adb -s "$serial" "$@"; }
cancel_phone() {
  if [[ "$phone_started" == 1 ]]; then
    adb_target shell am start -W -n app.tingyu/.debug.ProbeActivity --es run "$run_id" --ez cancel true >/dev/null 2>&1 || true
    phone_started=0
  fi
}
cleanup() {
  cancel_phone
  if [[ -n "$process_id" ]]; then kill "$process_id" 2>/dev/null || true; fi
}
interrupt() {
  local code="$1"
  cleanup
  printf 'run=%s stage=session event=cancelled exit=%s\n' "$run_id" "$code" | tee -a "$log_file"
  exit "$code"
}
trap cleanup EXIT
trap 'interrupt 130' INT
trap 'interrupt 143' TERM

# Check authorization before spending time building or touching the installed app.
if [[ "$mode" == phone ]]; then
  command -v adb >/dev/null || { echo '未找到 adb，请安装 Android platform-tools。' >&2; exit 2; }
  devices="$(adb devices)" || { echo '无法读取 Android 设备列表。' >&2; exit 2; }
  state="$(printf '%s\n' "$devices" | awk -v serial="$serial" '$1 == serial { print $2 }')"
  if [[ "$state" != device ]]; then
    case "$state" in
      unauthorized) echo "设备 $serial 尚未授权，请在手机上允许 USB 调试。" >&2 ;;
      offline) echo "设备 $serial 离线，请重新连接。" >&2 ;;
      *) echo "未找到已连接设备 $serial。可通过 ANDROID_SERIAL 选择设备。" >&2 ;;
    esac
    exit 2
  fi
fi
if command -v gradle >/dev/null 2>&1; then gradle_command=(gradle --no-daemon)
elif [[ -x ./gradlew ]]; then gradle_command=(./gradlew --no-daemon)
else echo '需要 Gradle 8.11.1 和 JDK 17，请先运行 scripts/bootstrap-android.sh。' >&2; exit 2
fi
echo "开始 $mode 诊断，本次编号 $run_id。日志：$log_file"
if [[ "$mode" == pc ]]; then
  "${gradle_command[@]}" :probe:installDist
  "$project_dir/probe/build/install/probe/bin/probe" "$run_id" "$input" >"$log_file" 2>&1 &
  process_id=$!
  result=0
  wait "$process_id" || result=$?
  process_id=''
  cat "$log_file"
  exit "$result"
fi

"${gradle_command[@]}" :app:assembleDebug
adb_target install -r app/build/outputs/apk/debug/app-debug.apk
# Only base64 and a validated random token cross adb's remote shell boundary.
encoded="$(python3 -c 'import base64,sys; data=sys.argv[1]; assert len(data)<=8192; print(base64.b64encode(data.encode()).decode())' "$input")"
phone_started=1
start_result="$(adb_target shell am start -W -n app.tingyu/.debug.ProbeActivity --es run "$run_id" --es input "$encoded")"
if [[ "$start_result" == *"Error:"* || "$start_result" == *"Exception"* ]]; then
  phone_started=0
  echo '手机诊断入口启动失败，请检查调试包安装结果。' >&2; exit 1
fi
deadline=$((SECONDS + timeout_seconds))
while (( SECONDS < deadline )); do
  # Do not clear logcat. A fresh token excludes all historical and unrelated sessions.
  current="$(adb_target logcat -d -t 5000 -v brief -s TingYuProbe:I '*:S' | awk -v token="run=$run_id " 'index($0, token) { print substr($0, index($0, token)) }')"
  printf '%s\n' "$current" >"$log_file"
  terminal="$(printf '%s\n' "$current" | awk '/stage=session event=finished / { for (i=1;i<=NF;i++) if ($i ~ /^exit=/) { sub(/^exit=/,"",$i); print $i } }' | tail -1)"
  if [[ "$terminal" =~ ^[0-9]+$ ]]; then
    phone_started=0
    cat "$log_file"
    case "$terminal" in 0|1|2|130) exit "$terminal" ;; *) exit 1 ;; esac
  fi
  sleep 1
done
cancel_phone
printf 'run=%s stage=session event=timeout exit=124\n' "$run_id" | tee -a "$log_file"
exit 124
