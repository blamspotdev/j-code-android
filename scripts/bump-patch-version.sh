#!/usr/bin/env sh
# Bump JCode's PATCH version by one, and keep the docs that state it in step.
#
# The version has a single source of truth — `val jcodeVersion = "…"` in
# app/build.gradle.kts — and versionCode derives from it as
# MAJOR*10000 + MINOR*100 + PATCH, so a patch bump is one line of real change.
# docs/specifications states the product version in a few places purely as
# documentation; they are rewritten here so they cannot drift.
#
# Pre-release labels (`1.4.3-beta`) are NEVER stored in the file — the release
# scripts apply them at build time via -PjcodeVersionName — so a suffix found
# here means something upstream is wrong and this refuses to bump it.
#
# Used by .github/workflows/version-bump.yml, which opens the bump PR after a
# merge to main (main is protected, so nothing can push the bump to it directly).
# Run manually:  sh scripts/bump-patch-version.sh
#
# Prints the NEW version on stdout (so it can be captured); progress goes to stderr.
set -eu

cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

GRADLE_FILE=app/build.gradle.kts

# Files that merely *state* the version. Rewritten only where they carry the current
# one, so historical mentions elsewhere are untouched.
DOC_FILES="docs/specifications/README.md
docs/specifications/00-overview/01-product-overview.md
docs/specifications/09-platform/02-build-variants-and-release.md"

fail() {
    echo "bump-patch-version: $1" >&2
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

NEXT="$MAJOR.$MINOR.$((PATCH + 1))"

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

# versionCode must keep climbing or an update-over-install is refused by Android.
CODE="$(echo "$NEXT" | awk -F. '{ printf "%d", $1*10000 + $2*100 + $3 }')"
echo "bump-patch-version: $CURRENT -> $NEXT (versionCode $CODE)" >&2
echo "$NEXT"
