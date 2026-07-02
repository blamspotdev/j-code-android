#!/usr/bin/env bash
# Build a release APK of JCode on Linux (Ubuntu/Debian). Output: ./builds
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

OS_NAME="Linux"
SDK_DEFAULT="$HOME/Android/Sdk"
JDK_HINT="sudo apt-get install -y openjdk-21-jdk"
JDK_INSTALL_CMD="sudo apt-get update && sudo apt-get install -y openjdk-21-jdk"
GIT_HINT="sudo apt-get install -y git"
GIT_INSTALL_CMD="sudo apt-get update && sudo apt-get install -y git"
RUST_HINT="curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y"
RUST_INSTALL_CMD="curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y"

# shellcheck source=build-release-common.sh
source "$SCRIPT_DIR/build-release-common.sh"
