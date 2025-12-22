#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLES_DIR="$ROOT/examples"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-"$ROOT/.gradle-cache"}"

overall_status=0

for example in "$EXAMPLES_DIR"/*; do
    if [[ ! -d "$example" ]]; then
        continue
    fi
    if [[ ! -x "$example/gradlew" ]]; then
        echo "==> Skipping $(basename "$example"): no Gradle wrapper found"
        continue
    fi
    echo "==> Running Glyph tasks for $(basename "$example")"
    if ! (cd "$example" && ./gradlew --no-daemon glyphParse "$@"); then
        overall_status=1
        continue
    fi
    if ! (cd "$example" && ./gradlew --no-daemon glyphCompileWasm "$@"); then
        overall_status=1
        continue
    fi
done

exit $overall_status
