#!/usr/bin/env bash
# Build and run the VT parser's resize tests on an attached device.
#
# vt_parser.c has no Android dependencies, so it links as a plain executable rather than through the
# JNI. Running it on the device rather than the host keeps the target ABI honest (the reflow moves a
# lot of memory, and the timing pass is only meaningful on real hardware) and needs no emulator or
# instrumentation harness. Takes about a second.
#
#   native/vt/test/run.sh              # first device adb reports
#   ANDROID_SERIAL=<serial> ...        # a specific one
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
out="$repo/build/vt-test"
mkdir -p "$out"

# The NDK the app builds against, resolved the same way the Gradle build does.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
ndk_version="$(sed -n 's/.*ndkVersion = "\([^"]*\)".*/\1/p' "$repo/build.gradle.kts" | head -1)"
[ -n "$ndk_version" ] || { echo "Could not read ndkVersion from build.gradle.kts"; exit 1; }

for host in windows-x86_64 linux-x86_64 darwin-x86_64; do
  candidate="$sdk/ndk/$ndk_version/toolchains/llvm/prebuilt/$host/bin/clang"
  [ -x "$candidate" ] && { clang="$candidate"; break; }
  [ -x "$candidate.exe" ] && { clang="$candidate.exe"; break; }
done
[ -n "${clang:-}" ] || { echo "No NDK clang under $sdk/ndk/$ndk_version"; exit 1; }

# The Windows NDK clang is a native binary and cannot read the MSYS paths this shell hands it.
# Keyed on cygpath rather than on the compiler's name: MSYS resolves `clang` to `clang.exe`
# transparently, so the path we hold has no suffix to test.
native_path() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

"$clang" --target=aarch64-linux-android33 -O2 -std=c11 -Wall -Wextra \
  -o "$(native_path "$out/vt_reflow_test")" \
  "$(native_path "$here/vt_reflow_test.c")" \
  "$(native_path "$repo/native/vt/src/vt_parser.c")"

adb push "$(native_path "$out/vt_reflow_test")" /data/local/tmp/vt_reflow_test > /dev/null
adb shell "chmod 755 /data/local/tmp/vt_reflow_test && /data/local/tmp/vt_reflow_test"
