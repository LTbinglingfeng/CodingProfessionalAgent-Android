# Sourced by the two installed-APK contract runners (Bash).
# Six setup calls at 5s each + 120s instrumentation + 3s cleanup leaves
# headroom before the fixture's 180s outer deadline. Never retry allocation.
command -v timeout >/dev/null || { echo 'Device contracts require the timeout utility.' >&2; exit 2; }
adb_device() { timeout --foreground -s KILL 5 "$adb_bin" -s "$ANDROID_SERIAL" "$@"; }

run_contract_instrumentation() {
  port='' job='' interrupted=0 stop_target=0
  cleanup() {
    trap '' INT TERM
    if [[ -n "$job" ]]; then
      kill -KILL -- "-$job" 2>/dev/null || true
      wait "$job" 2>/dev/null || true
    fi
    local rc=0
    if (( stop_target )); then
      # Killing host adb does not establish that device instrumentation exited.
      adb_device shell am force-stop dev.cpa.android >/dev/null 2>&1 || { echo 'Unable to stop owned instrumentation target.' >&2; rc=1; }
    fi
    if [[ "$port" =~ ^[0-9]+$ ]]; then
      if ! timeout --foreground -s KILL 3 "$adb_bin" -s "$ANDROID_SERIAL" reverse --remove "tcp:$port" >/dev/null 2>&1; then
        echo 'Unable to confirm removal of owned reverse mapping.' >&2
        return 1
      fi
    fi
    return "$rc"
  }
  trap 'status=$?; cleanup || status=1; exit "$status"' EXIT
  # Record cancellation rather than exiting between allocation/launch and
  # recording its handle. wait (unlike a foreground command) is interruptible.
  trap 'interrupted=130' INT
  trap 'interrupted=143' TERM
  port="$(adb_device reverse tcp:0 "tcp:$host_port" | tr -d '\r')" || exit 1
  [[ "$port" =~ ^[0-9]+$ ]] || { echo 'Unable to allocate reverse mapping.' >&2; exit 1; }
  (( ! interrupted )) || exit "$interrupted"
  # Put the output-checking shell and host adb in one owned process group;
  # cancellation must not leave the adb client running or log its arguments.
  stop_target=1
  set -m
  (
    set +m
    output="$(timeout --foreground -s KILL 120 "$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w -r \
      "$@" -e "$url_arg" "http://127.0.0.1:$port" "$runner" 2>&1)" || exit 125
    grep -Eq 'OK \(1 test\)' <<<"$output" && grep -q 'INSTRUMENTATION_CODE: -1' <<<"$output" &&
      ! grep -Eq 'INSTRUMENTATION_STATUS_CODE: -[1234]|FAILURES|INSTRUMENTATION_FAILED' <<<"$output"
  ) &
  job=$!
  trap 'exit 130' INT
  trap 'exit 143' TERM
  (( ! interrupted )) || exit "$interrupted"
  status=0
  wait "$job" || status=$?
  job=''
  if (( status == 0 || status == 1 )); then stop_target=0; else status=1; fi
  set +m
  cleanup || status=1
  trap - EXIT INT TERM
  return "$status"
}
