#!/usr/bin/env sh
# Bump JCode's version, and keep the docs that state it in step.
#
#   sh scripts/bump-version.sh [patch|minor|major]     (default: patch)
#
#     patch   1.4.6 -> 1.4.7
#     minor   1.4.6 -> 1.5.0     (patch resets)
#     major   1.4.6 -> 2.0.0     (minor and patch reset)
#
# The version has a single source of truth — `val jcodeVersion = "…"` in
# app/build.gradle.kts — and versionCode derives from it as
# (MAJOR*10000 + MINOR*100 + PATCH) * 100 + tier, so a bump is one line of real
# change. docs/specifications states the product version in a few places purely
# as documentation; they are rewritten here so they cannot drift.
#
# Resetting the lower parts keeps versionCode climbing (1.4.6 = 1040699 ->
# 1.5.0 = 1050099 -> 2.0.0 = 2000099), which Android requires for
# update-over-install.
#
# This OPENS A TRAIN rather than records a shipped version: `jcodeVersion` is
# the version being prepared, and previews of it are built as 1.5.0-beta.N
# before 1.5.0 is ever published. So the bump belongs *after* a release goes
# out, not after a merge — see
# docs/specifications/09-platform/02-build-variants-and-release.md.
#
# Pre-release labels (`1.5.0-beta.1`) are NEVER stored in the file — the release
# scripts and the release workflow apply them at build time via
# -PjcodeVersionName — so a suffix found here means something upstream is wrong
# and this refuses to bump it.
#
# Used by .github/workflows/version-bump.yml, which opens the next train when a
# stable release is published (minor), and can be run by hand for any level.
#
# Prints the NEW version on stdout (so it can be captured); progress goes to stderr.
set -eu

cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

LEVEL="${1:-patch}"
case "$LEVEL" in
    patch | minor | major) : ;;
    *)
        echo "bump-version: unknown level '$LEVEL' (expected patch, minor or major)" >&2
        exit 1
        ;;
esac

GRADLE_FILE=app/build.gradle.kts

# Files that merely *state* the version. Rewritten only where they carry the current
# one, so historical mentions elsewhere are untouched.
DOC_FILES="docs/specifications/README.md
docs/specifications/00-overview/01-product-overview.md
docs/specifications/09-platform/02-build-variants-and-release.md"

fail() {
    echo "bump-version: $1" >&2
    exit 1
}

# The same parse the release scripts use (scripts/build-release-common.sh), so there is
# one idiom for reading the version.
read_version() {
    sed -n 's/^val jcodeVersion = "\([^"]*\)".*/\1/p' "$GRADLE_FILE" 2>/dev/null | head -1
}

CURRENT="$(read_version)"
[ -n "$CURRENT" ] || fail "no 'val jcodeVersion = \"…\"' line in $GRADLE_FILE"

# Strictly MAJOR.MINOR.PATCH, all digits. Anything else (a suffix, a missing part) is a
# problem to report rather than to increment. The shape is checked FIRST because
# `${REST#*.}` leaves the string untouched when there is no dot left, which would quietly
# read "1.4" as 1.4.4 and bump it to 1.4.5.
case "$CURRENT" in
    *.*.*.*) fail "'$CURRENT' has more than three parts" ;;
    *.*.*) : ;;
    *) fail "'$CURRENT' is not a plain MAJOR.MINOR.PATCH version" ;;
esac
MAJOR="${CURRENT%%.*}"
REST="${CURRENT#*.}"
MINOR="${REST%%.*}"
PATCH="${REST#*.}"
for part in "$MAJOR" "$MINOR" "$PATCH"; do
    case "$part" in
        '' | *[!0-9]*) fail "'$CURRENT' is not a plain MAJOR.MINOR.PATCH version" ;;
    esac
done

# Lower parts reset so the result is a real semver step, not 1.5.6 from a minor bump.
case "$LEVEL" in
    patch) NEXT="$MAJOR.$MINOR.$((PATCH + 1))" ;;
    minor) NEXT="$MAJOR.$((MINOR + 1)).0" ;;
    major) NEXT="$((MAJOR + 1)).0.0" ;;
esac

# Dots are regex wildcards; escape them so "1.4.3" cannot match "1x4x3".
ESCAPED="$(printf '%s' "$CURRENT" | sed 's/\./\\./g')"

replace_in() {
    file="$1"
    [ -f "$file" ] || return 0
    sed "s/$ESCAPED/$NEXT/g" "$file" > "$file.bump-tmp"
    mv "$file.bump-tmp" "$file"
}

replace_in "$GRADLE_FILE"
for doc in $DOC_FILES; do
    replace_in "$doc"
done

# Confirm from the file rather than assuming sed matched.
AFTER="$(read_version)"
[ "$AFTER" = "$NEXT" ] || fail "rewrite failed: $GRADLE_FILE still reads '$AFTER'"

# versionCode must keep climbing or an update-over-install is refused by Android. Shown at the
# release tier (99), which is what this version will be built as once its train ships; its previews
# derive lower codes from the same base. See app/build.gradle.kts for the whole table.
CODE="$(echo "$NEXT" | awk -F. '{ printf "%d", ($1*10000 + $2*100 + $3) * 100 + 99 }')"
echo "bump-version: $LEVEL: $CURRENT -> $NEXT (versionCode $CODE)" >&2
echo "$NEXT"
