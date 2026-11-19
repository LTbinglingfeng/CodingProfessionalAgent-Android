#!/usr/bin/env bash
# Host verification only. Device/real-desktop tests are intentionally separate.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .local/logs
if [[ ! -f gradlew ]]; then
  printf '%s\n' 'Gradle wrapper is not installed; see docs/migration/environment.md.' >&2
  exit 2
fi
if [[ -d .local/android-sdk ]]; then
  export ANDROID_HOME="$PWD/.local/android-sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi
# Keep caches local and untracked; no shell profile changes.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.local/gradle-user-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$PWD/.local/android-user}"
./gradlew --console=plain :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug "$@" \
  2>&1 | tee .local/logs/android-verification.log
printf '\nHost checks passed. This does NOT certify device UI, screenshots, or real desktop/tunnel operation.\n'
