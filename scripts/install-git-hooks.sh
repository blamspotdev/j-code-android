#!/usr/bin/env sh
# One-time: point this repo's git hooks at the tracked .githooks/ directory, so the
# no-host-root pre-commit guard runs locally. Safe to re-run.
set -eu
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath .githooks
chmod +x .githooks/* scripts/check-no-host-root.sh 2>/dev/null || true
echo "Installed: core.hooksPath -> .githooks (no-host-root pre-commit guard active)."
