# Shared logic for build-release-macos.sh / build-release-linux.sh (sourced, not executed).
# Expects: OS_NAME, SDK_DEFAULT, JDK_HINT, JDK_INSTALL_CMD, GIT_HINT, GIT_INSTALL_CMD,
#          RUST_HINT, RUST_INSTALL_CMD

NDK_VERSION="27.2.12479018"
PLATFORM_PKG="platforms;android-36"
BUILD_TOOLS_PKG="build-tools;34.0.0"
CMAKE_PKG="cmake;3.22.1"

ASSUME_YES=0
VARIANT="${JCODE_VARIANT:-}"
PRERELEASE_LABEL="${JCODE_PRERELEASE_LABEL:-beta}"
for arg in "$@"; do
    case "$arg" in
        -y|--yes) ASSUME_YES=1 ;;
        --release) VARIANT="release" ;;
        --beta|--prerelease|--pre) VARIANT="beta" ;;
        --label=*) PRERELEASE_LABEL="${arg#--label=}" ;;
        -h|--help)
            echo "Usage: $(basename "$0") [-y|--yes] [--release|--beta] [--label=<s>]"
            echo "Builds a release APK of JCode into ./builds."
            echo "  -y, --yes       auto-accept install prompts"
            echo "  --release       final build (default; dev.jcode / \"JCode\"; version from VERSION.txt)"
            echo "  --beta          side-by-side testing build (dev.jcode.beta / \"JCode (beta)\") that"
            echo "                  installs ALONGSIDE the release app; versionName gets a -label suffix"
            echo "  --label=<s>     beta version label (default: beta -> 1.0.2-beta)"
            echo "Without --release/--beta you're prompted interactively."
            exit 0
            ;;
    esac
done

say()  { printf '\033[1;36m[jcode]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

ask() {
    if [ "$ASSUME_YES" = 1 ]; then return 0; fi
    ask_interactive "$1"
}

# Never auto-accepted by -y (used for choices, not installs).
ask_interactive() {
    if [ ! -t 0 ]; then return 1; fi
    local a
    read -r -p "$1 [y/N] " a
    case "$a" in y|Y|yes|Yes|YES) return 0 ;; *) return 1 ;; esac
}

require_tool() {
    local tool="$1" hint="$2" install_cmd="$3"
    if have "$tool"; then return 0; fi
    warn "Missing required tool: $tool"
    say  "  install with: $hint"
    if [ -n "$install_cmd" ] && ask "Install $tool now?"; then
        eval "$install_cmd" || die "install command for $tool failed"
        have "$tool" || die "$tool still not found after install (open a new shell and re-run?)"
    else
        die "Please install $tool and re-run."
    fi
}

REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"
say "JCode release build ($OS_NAME) — repo: $REPO_ROOT"

require_tool git "$GIT_HINT" "$GIT_INSTALL_CMD"

if ! have java && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
require_tool java "$JDK_HINT" "$JDK_INSTALL_CMD"
if ! java -version >/dev/null 2>&1; then
    # macOS ships a /usr/bin/java stub that exists but can't run.
    warn "java is on PATH but not runnable — no JDK is actually installed."
    say  "  install with: $JDK_HINT"
    die  "Please install JDK 21 and re-run."
fi
JAVA_MAJOR="$(java -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}' || true)"
case "$JAVA_MAJOR" in
    ''|*[!0-9]*) warn "Could not parse Java version; continuing (need JDK 17+, 21 recommended)." ;;
    *)
        if [ "$JAVA_MAJOR" -lt 17 ]; then
            warn "JDK $JAVA_MAJOR found, but 17+ is required (21 recommended)."
            say  "  install with: $JDK_HINT"
            die  "Please install JDK 21 and re-run."
        fi
        say "JDK $JAVA_MAJOR OK"
        ;;
esac

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK_ROOT" ] && [ -f local.properties ]; then
    SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
fi
if [ -z "$SDK_ROOT" ] && [ -d "$SDK_DEFAULT" ]; then
    SDK_ROOT="$SDK_DEFAULT"
fi
if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT" ]; then
    warn "Android SDK not found (checked ANDROID_HOME, ANDROID_SDK_ROOT, local.properties, $SDK_DEFAULT)."
    say  "Install it, then re-run:"
    say  "  - Android Studio (easiest): https://developer.android.com/studio → SDK lands at $SDK_DEFAULT"
    say  "  - or command-line tools only: https://developer.android.com/studio#command-line-tools-only"
    say  "    unzip to $SDK_DEFAULT/cmdline-tools/latest, then:"
    say  "    sdkmanager \"platform-tools\" \"$PLATFORM_PKG\" \"$BUILD_TOOLS_PKG\" \"ndk;$NDK_VERSION\" \"$CMAKE_PKG\""
    die  "Android SDK is required."
fi
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
say "Android SDK: $SDK_ROOT"

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
    SDKMANAGER="$(ls -d "$SDK_ROOT"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1 || true)"
fi

sdk_component() {
    local path="$1" pkg="$2" label="$3"
    if [ -e "$path" ]; then return 0; fi
    warn "Missing SDK component: $label"
    if [ -n "$SDKMANAGER" ] && [ -x "$SDKMANAGER" ]; then
        say "  install with: sdkmanager \"$pkg\""
        if ask "Install $label now via sdkmanager?"; then
            "$SDKMANAGER" "$pkg" || die "sdkmanager failed (try accepting licenses: sdkmanager --licenses)"
            return 0
        fi
    else
        say "  install \"$pkg\" via Android Studio's SDK Manager or sdkmanager (cmdline-tools not found in the SDK)."
    fi
    die "Please install $label and re-run."
}

sdk_component "$SDK_ROOT/ndk/$NDK_VERSION" "ndk;$NDK_VERSION" "NDK $NDK_VERSION"
sdk_component "$SDK_ROOT/platforms/android-36" "$PLATFORM_PKG" "Android platform 36"
if ! ls "$SDK_ROOT/cmake" >/dev/null 2>&1 || [ -z "$(ls "$SDK_ROOT/cmake" 2>/dev/null)" ]; then
    sdk_component "$SDK_ROOT/cmake/3.22.1" "$CMAKE_PKG" "SDK CMake"
fi
if ! ls "$SDK_ROOT/build-tools" >/dev/null 2>&1 || [ -z "$(ls "$SDK_ROOT/build-tools" 2>/dev/null)" ]; then
    sdk_component "$SDK_ROOT/build-tools/34.0.0" "$BUILD_TOOLS_PKG" "Android build-tools"
fi

CARGO_TASKS=""
rust_ready=1
if ! have cargo; then
    # shellcheck disable=SC1091
    [ -f "$HOME/.cargo/env" ] && . "$HOME/.cargo/env"
fi
if ! have cargo; then
    rust_ready=0
    warn "Rust (cargo) not found — the ripgrep/wasmtime native libs will be built as stubs."
    say  "  install with: $RUST_HINT"
    if [ -n "$RUST_INSTALL_CMD" ] && ask "Install Rust now?"; then
        eval "$RUST_INSTALL_CMD" || warn "Rust install failed; continuing without Rust."
        # shellcheck disable=SC1091
        [ -f "$HOME/.cargo/env" ] && . "$HOME/.cargo/env"
        have cargo && rust_ready=1
    fi
fi
if [ "$rust_ready" = 1 ]; then
    if ! cargo ndk --version >/dev/null 2>&1; then
        warn "cargo-ndk not found."
        if ask "Install cargo-ndk now (cargo install cargo-ndk)?"; then
            cargo install cargo-ndk || rust_ready=0
        else
            rust_ready=0
        fi
    fi
fi
if [ "$rust_ready" = 1 ]; then
    if have rustup; then
        if ! rustup target list --installed 2>/dev/null | grep -q '^aarch64-linux-android$'; then
            warn "Rust target aarch64-linux-android not installed."
            if ask "Add it now (rustup target add aarch64-linux-android)?"; then
                rustup target add aarch64-linux-android || rust_ready=0
            else
                rust_ready=0
            fi
        fi
    else
        # A distro cargo (apt/brew) can't add the Android std target without rustup.
        warn "cargo found but rustup isn't — can't verify the aarch64-linux-android target."
        say  "  install Rust via rustup instead: $RUST_HINT"
        rust_ready=0
    fi
fi
if [ "$rust_ready" = 1 ]; then
    CARGO_TASKS=":native:ripgrep-ffi:cargoBuildReleaseJniLibs :native:wasmtime-ffi:cargoBuildReleaseJniLibs"
    say "Rust toolchain OK"
else
    warn "Continuing without Rust — search/wasm features in the APK will use stub libraries."
fi

# --- Build variant: Release or Beta (interactive unless --release/--beta/$JCODE_VARIANT) ---
if [ -z "$VARIANT" ]; then
    if [ -t 0 ]; then
        printf '\n'
        say 'Which build?'
        printf '  [1] Release  - final build (dev.jcode / "JCode"), version straight from VERSION.txt\n'
        printf '  [2] Beta     - side-by-side build (dev.jcode.beta / "JCode (beta)"): installs ALONGSIDE\n'
        printf '                 the release app, own data, versionName gets a -label suffix\n'
        read -r -p 'Select [1] ' _sel
        case "$_sel" in 2|p|P|b|B|pre*|beta) VARIANT="beta" ;; *) VARIANT="release" ;; esac
        if [ "$VARIANT" = "beta" ]; then
            read -r -p "Pre-release label [$PRERELEASE_LABEL] " _lbl
            [ -n "$_lbl" ] && PRERELEASE_LABEL="$(printf '%s' "$_lbl" | tr -cd '0-9A-Za-z.-')"
        fi
    else
        VARIANT="release"
    fi
fi
case "$VARIANT" in beta|prerelease) IS_PRE=1; VARIANT="beta" ;; *) IS_PRE=0; VARIANT="release" ;; esac
# Beta = a separate app id (fixed ".beta", one beta slot) so it never overwrites the release build.
ID_SUFFIX=""; APP_LABEL="JCode"; [ "$IS_PRE" = 1 ] && { ID_SUFFIX=".beta"; APP_LABEL="JCode (beta)"; }
[ "$IS_PRE" = 1 ] && say "Variant: beta -> app id dev.jcode$ID_SUFFIX, label '$APP_LABEL', version label: $PRERELEASE_LABEL" || say "Variant: release"

VERSION="$(tr -d '[:space:]' < VERSION.txt 2>/dev/null || echo 1.0.0)"
# Beta appends the label to versionName (1.0.2 -> 1.0.2-beta); versionCode ignores the suffix.
if [ "$IS_PRE" = 1 ]; then VERSION_NAME="$VERSION-$PRERELEASE_LABEL"; else VERSION_NAME="$VERSION"; fi
# versionCode = MAJOR*10000 + MINOR*100 + PATCH (must match app/build.gradle.kts jcodeVersionCode).
CODE="$(echo "$VERSION" | awk -F. '{ c = $1*10000 + $2*100 + $3; printf "%d", (c > 0 ? c : 10000) }')"
say "Building JCode v$VERSION_NAME ($CODE) [$VARIANT] — this compiles native code and can take a while..."

# Cargo libs build in a separate invocation: assembleRelease's configuration then sees them
# and drops the CMake stub for the Rust modules (see root build.gradle.kts).
if [ -n "$CARGO_TASKS" ]; then
    # shellcheck disable=SC2086
    ./gradlew $CARGO_TASKS || die "Cargo build failed."
fi
# -PjcodeIdSuffix makes the Beta build a separate app id + label (see app/build.gradle.kts).
GRADLE_ARGS=(:app:assembleRelease "-PjcodeVersionName=$VERSION_NAME")
[ -n "$ID_SUFFIX" ] && GRADLE_ARGS+=("-PjcodeIdSuffix=$ID_SUFFIX")
./gradlew "${GRADLE_ARGS[@]}" || die "Gradle build failed."

APK="$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || die "Build finished but no APK found in app/build/outputs/apk/release/"

mkdir -p builds
OUT="builds/jcode-v$VERSION_NAME-$CODE-$VARIANT.apk"

latest_build_tools() { ls "$SDK_ROOT/build-tools" | sort -V | tail -1; }
APKSIGNER="$SDK_ROOT/build-tools/$(latest_build_tools)/apksigner"

# Locate keytool alongside the java we already resolved (for auto-creating a keystore).
find_keytool() {
    if have keytool; then command -v keytool; return; fi
    local j; j="$(command -v java 2>/dev/null)"
    [ -n "$j" ] && [ -x "$(dirname "$j")/keytool" ] && echo "$(dirname "$j")/keytool"
}

# Generate a fresh 4096-bit RSA release keystore at the default path (sets JCODE_KEYSTORE/_PASS).
create_release_keystore() {
    local keytool; keytool="$(find_keytool)"
    [ -n "$keytool" ] || { warn "keytool not found; cannot create a keystore."; return 1; }
    mkdir -p "$(dirname "$JCODE_KEYSTORE_DEFAULT")"
    local pw; pw="$(head -c 18 /dev/urandom | base64 | tr -d '+/=' | cut -c1-21)aA1"
    "$keytool" -genkeypair -v -keystore "$JCODE_KEYSTORE_DEFAULT" -alias jcode \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -storepass "$pw" -keypass "$pw" -dname "CN=JCode, O=JCode, C=US" \
        || { warn "keytool failed to create the keystore."; return 1; }
    printf '%s' "$pw" > "$JCODE_KEYSTORE_PASS_FILE"
    chmod 600 "$JCODE_KEYSTORE_PASS_FILE" 2>/dev/null || true
    JCODE_KEYSTORE="$JCODE_KEYSTORE_DEFAULT"
    JCODE_KEYSTORE_PASS="$pw"
    JCODE_KEY_ALIAS="jcode"
    JCODE_KEY_PASS="$pw"
    say  "Created release keystore: $JCODE_KEYSTORE"
    say  "  password saved to: $JCODE_KEYSTORE_PASS_FILE"
    warn "KEEP this keystore + password — reuse it to sign every future release."
}

# Resolve the signing keystore: explicit env wins; else the default JCode keystore; else create one.
JCODE_KEYSTORE_DEFAULT="$HOME/.jcode/jcode-release.jks"
JCODE_KEYSTORE_PASS_FILE="$HOME/.jcode/jcode-release.password.txt"
if [ -z "${JCODE_KEYSTORE:-}" ] && [ -f "$JCODE_KEYSTORE_DEFAULT" ]; then
    JCODE_KEYSTORE="$JCODE_KEYSTORE_DEFAULT"
    JCODE_KEY_ALIAS="${JCODE_KEY_ALIAS:-jcode}"
    if [ -z "${JCODE_KEYSTORE_PASS:-}" ] && [ -f "$JCODE_KEYSTORE_PASS_FILE" ]; then
        JCODE_KEYSTORE_PASS="$(cat "$JCODE_KEYSTORE_PASS_FILE")"
    fi
    JCODE_KEY_PASS="${JCODE_KEY_PASS:-${JCODE_KEYSTORE_PASS:-}}"
    say "Using release keystore $JCODE_KEYSTORE"
fi
if [ -z "${JCODE_KEYSTORE:-}" ] && ask "No release keystore found. Create one now at $JCODE_KEYSTORE_DEFAULT and sign with it?"; then
    create_release_keystore || true
fi

SIGN_STATE="unsigned"
if [ -n "${JCODE_KEYSTORE:-}" ]; then
    [ -f "$JCODE_KEYSTORE" ] || die "JCODE_KEYSTORE is set but not a file: $JCODE_KEYSTORE"
    [ -n "${JCODE_KEYSTORE_PASS:-}" ] || die "No keystore password (set JCODE_KEYSTORE_PASS or provide $JCODE_KEYSTORE_PASS_FILE)."
    say "Signing with keystore $JCODE_KEYSTORE"
    "$APKSIGNER" sign --ks "$JCODE_KEYSTORE" --ks-pass "pass:$JCODE_KEYSTORE_PASS" \
        ${JCODE_KEY_ALIAS:+--ks-key-alias "$JCODE_KEY_ALIAS"} \
        ${JCODE_KEY_PASS:+--key-pass "pass:$JCODE_KEY_PASS"} \
        --out "$OUT" "$APK"
    SIGN_STATE="release-signed"
elif [ -f "$HOME/.android/debug.keystore" ] && ask_interactive "No release keystore configured. Sign with the Android debug keystore so the APK is installable?"; then
    OUT="builds/jcode-v$VERSION_NAME-$CODE-$VARIANT-debugsigned.apk"
    "$APKSIGNER" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out "$OUT" "$APK"
    SIGN_STATE="debug-signed"
else
    OUT="builds/jcode-v$VERSION_NAME-$CODE-$VARIANT-unsigned.apk"
    cp "$APK" "$OUT"
    warn "APK is UNSIGNED and cannot be installed as-is."
    say  "  sign later: apksigner sign --ks <keystore> --out <signed.apk> $OUT"
fi

if have sha256sum; then SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"; else SHA="$(shasum -a 256 "$OUT" | cut -d' ' -f1)"; fi
SIZE="$(du -h "$OUT" | cut -f1)"
say "Done: $OUT ($SIZE, $SIGN_STATE)"
[ -n "$ID_SUFFIX" ] && say "Installs as a SEPARATE app: dev.jcode$ID_SUFFIX ('$APP_LABEL') — won't overwrite the release build."
say "sha256: $SHA"
