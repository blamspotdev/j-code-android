#!/usr/bin/env sh
# Export the compileOnly jars a native extension builds against.
#
# A native extension is loaded into JCode's own process and compiles against JCode's own classes,
# so `:core:design` and `:core:ext-api` are its SDK. Each extension repo vendors them under
# `<module>/libs/`, and nothing until now regenerated those copies — the jars in the extension repos
# were hand-copied months apart and drifted, which is how a design-system rename reached four
# published extensions as a NoClassDefFoundError instead of a compile error.
#
# Usage:
#   scripts/export-design-sdk.sh                     # build + write to build/sdk/
#   scripts/export-design-sdk.sh DEST [DEST...]      # …and copy into each DEST
#
# A DEST may be a `libs/` directory or a jar path. A directory gets whichever names it already
# uses — the repos disagree (`jcode-design.jar` vs `jcode-core-design.jar`) and renaming them would
# break their build files for no gain.
set -eu

cd "$(dirname "$0")/.."
OUT="build/sdk"

say() { printf '[jcode] %s\n' "$*"; }
die() { printf '[error] %s\n' "$*" >&2; exit 1; }

say "building :core:design and :core:ext-api (release)"
./gradlew -q :core:design:bundleLibCompileToJarRelease :core:ext-api:bundleLibCompileToJarRelease

design="core/design/build/intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar"
extapi="core/ext-api/build/intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar"
[ -f "$design" ] || die "design jar not produced at $design"
[ -f "$extapi" ] || die "ext-api jar not produced at $extapi"

mkdir -p "$OUT"
cp "$design" "$OUT/jcode-design.jar"
cp "$extapi" "$OUT/jcode-ext-api.jar"

# The ABI the exported ext-api implements, so a copy can be named for it the way the repos do.
abi=$(sed -n 's/.*JCODE_EXT_ABI: Int = \([0-9]*\).*/\1/p' \
    core/ext-api/src/main/java/dev/blamspot/jcode/ext/api/NativeExtension.kt | head -1)
say "wrote $OUT/jcode-design.jar and $OUT/jcode-ext-api.jar (JCODE_EXT_ABI=${abi:-?})"

for dest in "$@"; do
    if [ -d "$dest" ]; then
        # Refresh only names the destination already has: an extension's build file names the jar it
        # expects, and inventing a new one there would break it.
        found=0
        for existing in "$dest"/*design*.jar; do
            [ -e "$existing" ] || continue
            cp "$design" "$existing"
            say "updated $existing"
            found=1
        done
        for existing in "$dest"/*ext-api*.jar; do
            [ -e "$existing" ] || continue
            cp "$extapi" "$existing"
            say "updated $existing"
            found=1
        done
        [ "$found" -eq 1 ] || die "$dest has no *design*.jar or *ext-api*.jar to refresh"
    else
        case "$dest" in
            *ext-api*) cp "$extapi" "$dest" ;;
            *) cp "$design" "$dest" ;;
        esac
        say "updated $dest"
    fi
done
