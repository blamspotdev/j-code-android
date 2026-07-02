#!/usr/bin/env bash
# Build a release APK of JCode on macOS. Output: ./builds
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

OS_NAME="macOS"
SDK_DEFAULT="$HOME/Library/Android/sdk"
JDK_HINT="brew install --cask temurin@21   (or https://adoptium.net)"
GIT_HINT="xcode-select --install   (or brew install git)"
RUST_HINT="curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y"
RUST_INSTALL_CMD="curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y"
if command -v brew >/dev/null 2>&1; then
    JDK_INSTALL_CMD="brew install --cask temurin@21"
    GIT_INSTALL_CMD="brew install git"
else
    JDK_INSTALL_CMD=""
    GIT_INSTALL_CMD=""
fi

# shellcheck source=build-release-common.sh
source "$SCRIPT_DIR/build-release-common.sh"
