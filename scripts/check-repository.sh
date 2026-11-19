#!/usr/bin/env bash
# Verify repository hygiene without displaying file contents or credentials.
set -euo pipefail
cd "$(dirname "$0")/.."
for path in .local/probe local.properties signing.properties test.keystore test.jks app/build/probe .gradle/probe; do
  if ! git check-ignore -q "$path"; then
    printf 'Not ignored: %s\n' "$path" >&2
    exit 1
  fi
done
bad=0
while IFS= read -r -d '' path; do
  case "$path" in
    .local/*|*/build/*|.gradle/*|local.properties|signing.properties|*.keystore|*.jks)
      printf 'Forbidden tracked artifact: %s\n' "$path" >&2
      bad=1 ;;
  esac
done < <(git ls-files -z)
if [[ "$bad" != 0 ]]; then exit 1; fi
printf '%s\n' 'Ignore rules and tracked artifact paths passed. This is not a content-level secret audit.'
