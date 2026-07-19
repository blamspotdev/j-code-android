#!/usr/bin/env sh
# Enforce JCode's no-host-root invariant.
#
# JCode is a sandboxed Android app whose Linux runtime is proot (a ptrace-based
# USERSPACE sandbox running within the app's own uid). It must NEVER escalate to
# real host root, to protect user/developer data. This scan fails (exit 1) if any
# host-root escalation appears in the JVM/Android sources, Gradle build scripts,
# the version catalog, or the manifests.
#
# NOT flagged (legitimate, all in-sandbox userspace):
#   - the proot guest `sudo` shim used by catalog install scripts
#   - proot's `-0` flag (fake UID 0 INSIDE the guest only)
#   - `rootfs` / `distroRoot` naming
#
# Single source of truth: invoked by CI (.github/workflows/no-host-root.yml),
# the pre-commit hook (.githooks/pre-commit), and the release build scripts.
# Run manually:  sh scripts/check-no-host-root.sh
set -eu

cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

# File types where a host-root dependency could actually be introduced. Docs (*.md)
# and proot catalog scripts (*.yaml/*.sh, which legitimately use the guest sudo shim)
# are intentionally excluded. This scanner itself is *.sh and so is never scanned.
PATHSPEC='*.kt *.java *.gradle *.gradle.kts *.toml *AndroidManifest.xml'

fail=0
scan() {
    label="$1"
    pattern="$2"
    # git grep exits 1 when there are no matches; tolerate that with `|| true`.
    # shellcheck disable=SC2086
    hits="$(git grep -nIE "$pattern" -- $PATHSPEC 2>/dev/null || true)"
    if [ -n "$hits" ]; then
        echo "X  host-root escalation [$label]:"
        echo "$hits" | sed 's/^/       /'
        fail=1
    fi
}

# 1) Executing the host `su` binary (guest `sudo` inside proot is NOT this).
scan "host su execution" \
    'exec[A-Za-z]*\([[:space:]]*"su"|"su"[[:space:]]*,[[:space:]]*"-c"|/system/x?bin/su([^A-Za-z]|$)'

# 2) Root-helper libraries (libsu / libsuperuser / Shizuku / RootTools).
scan "root helper library" \
    'com\.topjohnwu\.superuser|topjohnwu|libsuperuser|eu\.chainfire|rikka\.shizuku|(dev|moe)\.shizuku|com\.stericson\.(RootTools|RootShell)'

# 3) Privileged manifest markers (system shared-uid / secure-settings / mount perms).
scan "privileged manifest" \
    'sharedUserId[[:space:]]*=[[:space:]]*"android\.uid\.system"|android:sharedUserId|android\.permission\.WRITE_SECURE_SETTINGS|android\.permission\.MOUNT_UNMOUNT_FILESYSTEMS'

if [ "$fail" -ne 0 ]; then
    echo ""
    echo "JCode must run entirely in its proot userspace sandbox and must NOT use host root."
    echo "Remove the escalation(s) above. (The guest 'sudo' shim in proot catalog scripts is fine.)"
    exit 1
fi

echo "check-no-host-root: OK - no host-root escalation found."
