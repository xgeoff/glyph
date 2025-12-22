#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-"$ROOT/.gradle-cache"}"

cd "$ROOT"
echo "==> Running glyph-core tests"
./gradlew --no-daemon :glyph-core:test "$@"
