#!/usr/bin/env bash
# Sourced by bootstrap-android.sh. Downloads are verified by the caller.
download() {
  local url="$1" destination="$2" mode="${3:-}"
  local attempt status=1 http_code
  case "$mode" in
    ''|--resume) ;;
    *) echo 'Unknown download mode' >&2; return 2 ;;
  esac
  for attempt in 1 2 3; do
    # Positional parameters also work with macOS Bash 3.2 + nounset, where
    # expanding an empty array is treated as an unbound variable.
    set --
    if [[ "$mode" == --resume && -s "$destination.part" ]]; then
      set -- --continue-at -
    fi
    # Retry in separate curl processes: each retry discovers the CURRENT partial
    # size, including bytes received before a timeout in the previous attempt.
    if http_code="$(curl --proto '=https' --proto-redir '=https' --tlsv1.2 \
      --fail --location --connect-timeout 20 --max-time 900 \
      "$@" "$url" --output "$destination.part" --write-out '%{http_code}')"; then
      mv "$destination.part" "$destination"
      return 0
    else
      status=$?
    fi
    # Some CDNs do not support Range, or the archive size has changed. Only an
    # explicit range failure discards the partial; DNS and timeout errors keep it.
    if [[ "$mode" == --resume && ( "$status" == 33 || "$http_code" == 416 ) ]]; then
      rm -f "$destination.part"
    fi
    if [[ "$attempt" != 3 ]]; then
      echo "Download attempt $attempt failed (curl $status); retrying..." >&2
      sleep "$attempt"
    fi
  done
  echo "Download failed (curl $status). Any received bytes remain in $destination.part" >&2
  return "$status"
}
