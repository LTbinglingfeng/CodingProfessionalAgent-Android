#!/usr/bin/env bash
# Installed APKs only. Never build, install, clear app data, or launch an Activity.
# Disable inherited tracing before reading the ephemeral credential.
set +x
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5580}"
[[ "$ANDROID_SERIAL" == emulator-5580 ]] || { echo 'Only emulator-5580 is permitted.' >&2; exit 2; }
[[ $# == 0 ]] || { echo 'No positional arguments supported.' >&2; exit 2; }
if [[ "${CPA_TUNNEL_FIXTURE:-}" != source-services ]]; then
  exec node scripts/test-tunnel-contract.mjs bash scripts/test-tunnel-device-contract.sh
fi
[[ "${CPA_TUNNEL_URL:-}" =~ ^http://127\.0\.0\.1:([0-9]+)$ ]] || { echo 'Missing loopback source fixture.' >&2; exit 2; }
host_port="${BASH_REMATCH[1]}"
[[ "${CPA_TUNNEL_PASSWORD:-}" =~ ^[0-9a-f]{64}$ ]] || { echo 'Expected ephemeral fixture credential.' >&2; exit 2; }
[[ "${CPA_TUNNEL_KEY:-}" =~ ^[0-9a-f]{64}$ ]] || { echo 'Expected ephemeral gateway key.' >&2; exit 2; }
[[ "${CPA_TUNNEL_TARGET:-}" == android-contract-desktop ]] || { echo 'Unexpected fixture target.' >&2; exit 2; }
adb_bin="${ANDROID_HOME:-$PWD/.local/android-sdk}/platform-tools/adb"
[[ -x "$adb_bin" ]] || adb_bin="$(command -v adb)"
source scripts/device-contract-adb.sh
[[ "$(adb_device get-state)" == device ]] || { echo 'Designated emulator unavailable.' >&2; exit 2; }
[[ "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Refusing a non-emulator target.' >&2; exit 2; }
[[ "$(adb_device shell getprop ro.build.version.sdk | tr -d '\r')" == 37 ]] || { echo 'This contract runner requires API 37.' >&2; exit 2; }
runner='dev.cpa.android.test/dev.cpa.android.LocalizedTestRunner'
adb_device shell pm list instrumentation | grep -Fq "instrumentation:$runner" || { echo 'Install app and test APKs first.' >&2; exit 2; }
# Runtime permission is required by API 37 even for reversed loopback traffic.
adb_device shell pm grant dev.cpa.android android.permission.ACCESS_LOCAL_NETWORK
url_arg=CPA_TUNNEL_URL
if run_contract_instrumentation \
  -e class dev.cpa.android.core.network.tunnel.RealTunnelDeviceContractTest \
  -e CPA_TUNNEL_FIXTURE source-services \
  -e CPA_TUNNEL_PASSWORD "$CPA_TUNNEL_PASSWORD" \
  -e CPA_TUNNEL_KEY "$CPA_TUNNEL_KEY" \
  -e CPA_TUNNEL_TARGET "$CPA_TUNNEL_TARGET"; then
  echo 'Device tunnel contract: OK (1 test); real Go Hub and desktop forwarding + explicit business fixtures, not model responses.'
else
  echo 'Device tunnel contract failed (raw instrumentation output suppressed to protect credentials). Check installed APKs, permission and fixture compatibility.' >&2
  exit 1
fi
