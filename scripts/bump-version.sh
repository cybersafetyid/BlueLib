#!/usr/bin/env bash
set -e

# Resolve script directory and root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# Check for Python 3 or Python
if command -v python3 &>/dev/null; then
    exec python3 "$SCRIPT_DIR/bump-version.py" "$@"
elif command -v python &>/dev/null; then
    exec python "$SCRIPT_DIR/bump-version.py" "$@"
else
    echo "❌ Error: Python 3 is required to run the version bump script."
    exit 1
fi
