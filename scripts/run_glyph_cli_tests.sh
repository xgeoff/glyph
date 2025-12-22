#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_DIR="${REPO_ROOT}/tools/glyph-cli"

if [[ ! -d "${CLI_DIR}" ]]; then
  echo "Glyph CLI directory not found at ${CLI_DIR}" >&2
  exit 1
fi

echo "==> Running go test ./... in tools/glyph-cli"
cd "${CLI_DIR}"
go test ./... "$@"
