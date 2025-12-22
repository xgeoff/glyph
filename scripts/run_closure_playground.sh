#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE_DIR="${REPO_ROOT}/examples/closure-playground"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-"$REPO_ROOT/.gradle-cache"}"

if [[ ! -x "${EXAMPLE_DIR}/gradlew" ]]; then
  echo "Missing Gradle wrapper in ${EXAMPLE_DIR}" >&2
  exit 1
fi

pushd "${EXAMPLE_DIR}" >/dev/null
echo "==> glyphParse (closure-playground)"
./gradlew --no-daemon glyphParse
echo "==> glyphCompileWasm (closure-playground)"
./gradlew --no-daemon glyphCompileWasm
popd >/dev/null
