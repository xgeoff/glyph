#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_DIR="${REPO_ROOT}/tools/glyph-cli"
BINARY="${CLI_DIR}/glyph-cli"
SOURCE="${REPO_ROOT}/examples/closure-playground/src/main/glyph/com/example/closures/main.gly"
ROOT="${REPO_ROOT}/examples/closure-playground"

if [[ ! -f "${SOURCE}" ]]; then
  echo "Missing closure-playground source at ${SOURCE}" >&2
  exit 1
fi

if [[ ! -x "${BINARY}" ]]; then
  echo "==> Building glyph-cli binary"
  pushd "${CLI_DIR}" >/dev/null
  go build -o "${BINARY}" .
  popd >/dev/null
fi

echo "==> Running glyph-cli binary for closure-playground"
"${BINARY}" "${SOURCE}" --root "${ROOT}"
