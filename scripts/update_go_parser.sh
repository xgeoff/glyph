#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRAMMAR="$ROOT/grammar/glyph.peg"
PARSER_DIR="$ROOT/tools/glyph-cli/parser"

if ! command -v pigeon >/dev/null 2>&1; then
    echo "pigeon binary not found in PATH; install with 'go install github.com/mna/pigeon@latest'" >&2
    exit 1
fi

echo "==> Regenerating Go parser from grammar/glyph.peg"
(cd "$ROOT/tools/glyph-cli" && pigeon -o parser/glyph_parser.go ../../grammar/glyph.peg)

sed -i '' '1s/^/package parser\n\n/' "$PARSER_DIR/glyph_parser.go"

echo "==> Building glyph-cli to verify parser output"
(cd "$ROOT/tools/glyph-cli" && go build ./...)
