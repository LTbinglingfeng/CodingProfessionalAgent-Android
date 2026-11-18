#!/usr/bin/env bash
# Use the same sources and dependencies as the Android application.
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./scripts/gradle-local :app:testDebugUnitTest --tests 'dev.cpa.android.core.network.*' "$@"
