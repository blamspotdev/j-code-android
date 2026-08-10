# Security and privacy

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | Repository-wide |
| **Primary sources** | scripts/check-no-host-root.sh, app/src/main/AndroidManifest.xml, feature/marketplace/src/main/java/dev/jcode/feature/marketplace/JextCrypto.kt, core/distro/src/main/java/dev/jcode/core/distro/adb/AdbAuth.kt, core/distro/src/main/java/dev/jcode/core/distro/ProotManager.kt, app/src/main/java/dev/jcode/vdevice/GuestContext.kt, app/src/main/java/dev/jcode/AppUpdateInstaller.kt, app/src/main/res/xml/network_security_config.xml, app/src/main/res/xml/backup_rules.xml, app/src/main/res/xml/data_extraction_rules.xml |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The trust model: what is isolated from what, which guarantees are real, and which are explicitly
not. This is descriptive — it documents the boundaries as built, so they are not mistaken for
stronger ones.

---

## 2. The central invariant: no host root

> JCode is a sandboxed Android app whose Linux runtime is proot — a ptrace-based **userspace**
> sandbox running within the app's own uid. It must **never** escalate to real host root.

| Not present | Present instead |
|---|---|
| Executing the host `su` binary | proot's `-0`, a **fake** uid 0 inside the guest only |
| `libsu`, libsuperuser, Shizuku, RootTools | Nothing |
| `android:sharedUserId`, `android.uid.system` | Nothing |
| `WRITE_SECURE_SETTINGS`, `MOUNT_UNMOUNT_FILESYSTEMS` | Nothing |

Mechanically enforced by `scripts/check-no-host-root.sh` in CI, in the pre-commit hook, and as a
release pre-flight — see
[CI, quality and invariants §2](03-ci-quality-and-invariants.md#2-the-no-host-root-guard).

A practical consequence: JCode **cannot** enable wireless debugging for the user, because that needs
`WRITE_SECURE_SETTINGS`. The user does it through the system UI.

---

## 3. Isolation boundaries — what is and is not a boundary

| Boundary | Real? | Notes |
|---|---|---|
| App ↔ other apps | **Yes** | Standard Android app sandbox |
| App ↔ guest Linux processes | **Partly** | proot is a ptrace-based *convenience* sandbox, not a security boundary. Guest processes run as the app's uid and can reach anything the app can |
| App ↔ `:guest` (virtual device) | **No** | Separate process, **same uid, same permissions, same data access**. A memory and lifecycle boundary, not a security one |
| Guest APK ↔ JCode's data | **Partly** | `GuestContext` redirects the guest's `dataDir` to `filesDir/vdevice/<package>/`, which "keeps a guest from ever seeing (or writing into) JCode's own data directory" — but the guest runs arbitrary code as JCode's uid and can bypass the wrapper |

**Do not run untrusted APKs in the app sandbox, and do not treat proot as a jail.** Both exist for
developer convenience.

---

## 4. Permissions

Every permission JCode requests and why:

| Permission | Purpose | Gate |
|---|---|---|
| `INTERNET` | Rootfs download, `apt`, marketplace, update check | — |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep terminals, builds, LSP and DAP sessions alive | Subtype `interactive_terminal_and_build_runner` |
| `POST_NOTIFICATIONS` | The foreground-service notification | Runtime prompt |
| `WAKE_LOCK` | Long builds and installs | — |
| `MANAGE_EXTERNAL_STORAGE` | Raw file-path access to the legacy `/storage/emulated/0/JCode` root | System settings toggle; checked with `Environment.isExternalStorageManager()` |
| `REQUEST_INSTALL_PACKAGES` | The in-app updater | `canRequestPackageInstalls()` at runtime |

**Request permissions only when first needed.** Do not front-load RUN_COMMAND, notification or SAF
prompts.

The `<queries>` block for `ACTION_VIEW` + `BROWSABLE` on `http`/`https` exists solely so the
"Open web previews in" picker can enumerate browsers under targetSdk-30 package visibility.

`ProjectsDocumentsProvider` is `exported="true"` but guarded by
`android.permission.MANAGE_DOCUMENTS`, which only the system DocumentsUI holds.

`AppInstallReceiver` is `exported="false"` and handles only the `PendingIntent` this app hands to
its own `PackageInstaller.commit()` — the broadcast must be **explicit**, which was a shipped fix.

---

## 5. Code trust

### 5.1 Extensions

| Property | Guarantee |
|---|---|
| **Authenticity** | Ed25519 signature over the whole payload, key id `jcode-official-v1` |
| **Integrity** | Ed25519 plus AES-GCM tag plus per-file SHA-256 plus a package fingerprint |
| **Confidentiality** | **None.** The AES key ships in the APK; encryption is obfuscation, and the source says so |

The marketplace index's fingerprint is checked against the recomputed one, which binds the downloaded
artifact to the entry the user chose.

**Unsigned** packages install only through Developer options and are marked `dev = true`.

Capabilities (`api.capabilities`: `api`, `exec`, `fs`, `config`, `workbench`, `service`) are declared
in the manifest and **revocable per extension** by the user. An extension with `exec` can run
arbitrary commands in the guest, so that grant is the significant one.

There is **no revocation mechanism** for a compromised signing key short of rotating it and
re-signing every package.

See [`.jext` package format §4](../07-extensions/02-jext-package-format.md#4-security-model--read-this-before-relying-on-it).

### 5.2 `.vsix` extensions

Imported `.vsix` extensions are **not signed or verified** — they run Node code inside the guest with
whatever the guest can reach. Import only from sources you trust.

### 5.3 The app itself

Release APKs are signed with a keystore resolved by the release script. Changing the key breaks Play
Protect's recognition of the app and prevents a same-signature silent self-update.

---

## 6. ADB

| Property | Behavior |
|---|---|
| Binding | **Loopback only.** Nothing listens on a routable interface |
| Daemon auth | `AUTH SIGNATURE` by an **enrolled key** only. `AUTH RSAPUBLICKEY` — the path that raises "Allow USB debugging?" on a real phone — is **never accepted**, because this daemon has no dialog and no notion of a trusted user gesture |
| Enrolled keys | The distro's own `~/.android/adbkey.pub` |
| TLS | The daemon announces a pre-STARTTLS protocol version and does not negotiate TLS |
| Pairing | Out of scope; the user pairs through the system UI |

See [ADB bridge §4](../03-runtime/05-adb-bridge.md#4-adbauth--device-side-authentication).

---

## 7. Data at rest

| Data | Location | Notes |
|---|---|---|
| Projects and workspaces | `filesDir/workspace/` (ext4, app-private) | Wiped on uninstall or "Clear data" |
| Linux rootfs | `filesDir/distros/<id>/rootfs/` | Same |
| Extensions | `filesDir/extensions/` | Same |
| Session and unsaved buffers | `filesDir/session/` | Same |
| Guest APK data | `filesDir/vdevice/<package>/` | Same |
| Settings | Jetpack DataStore | Same |
| Workspace and project rows | Room, version 1, `fallbackToDestructiveMigration` | A schema change **drops** the database |

`android:allowBackup="false"` in the manifest. `backup_rules.xml` and `data_extraction_rules.xml` are
present for the framework's requirements.

`network_security_config.xml` governs cleartext policy for the app's own network use.

**No secrets should be stored in a `.jext`** — see §5.1.

---

## 8. Privacy and telemetry

- **No analytics or telemetry.** There is no reporting SDK in the dependency graph.
- Logs and any telemetry must be **path-redacted**; no personally identifying information in logs
  (`AGENTS.md`).
- **OSC 52 clipboard reads are never answered.** A guest program requesting the clipboard with `?`
  gets nothing — "the guest must not see the user's clipboard uninvited". Writes are honoured.
- Update checks and marketplace fetches are the only routine outbound traffic beyond what the user's
  own tooling makes.
- `VirtualIdentity` reports a synthetic device identity to a guest APK (`JCode vDevice`,
  `JCODEVD00000000`) while leaving hardware-derived values truthful.

---

## 9. Known gaps

- **Rootfs downloads from the built-in fallback manifest are not hash-verified** — the default
  entries carry `sha256 = ""`, so verification engages only when a served manifest supplies a hash.
  A network-position attacker could substitute a rootfs.
- Imported `.vsix` extensions are unverified code.
- The `:guest` process is not a security boundary despite looking like one.
- No signing-key revocation path for extensions.
- The `exec` capability is effectively full control of the guest environment; the permission UI
  cannot express anything finer.

---

## 10. Reporting a vulnerability

Contact the Licensor privately rather than filing a public issue
([`CONTRIBUTING.md`](../../../CONTRIBUTING.md)).

---

## Diagnostic logging

`:core:diag` `DiagnosticLog` is **off by default and never enabled implicitly** — only the user's
Settings → Diagnostics opt-in starts it, via the single `MainViewModel` collector that calls
`DiagnosticLog.configure`. Three sources feed one file: explicit `event`/`trace`/`failure` calls, the
app's **own** logcat (`logcat --pid=<self>`, which the log daemon scopes to this app with no
permission), and uncaught exceptions (the previous handler still runs).

Every line is **path-redacted** before it is written — the app data dir, the shared `JCode` root and
the external-storage root become `<app-data>` / `<jcode>` / `<storage>`. This is not a user-facing
toggle: a log exists to be shared, so redaction is unconditional.

Files rotate at 2 MB × 2 in `JCode/logs/` (shared storage, so a report can be attached from a file
manager or pulled over adb), falling back to the app's external files dir and then internal storage.
The user can view the tail in-app before sharing, export a copy through SAF, or clear it.

---

## 11. References

- [CI, quality and invariants](03-ci-quality-and-invariants.md)
- [`.jext` package format](../07-extensions/02-jext-package-format.md)
- [ADB bridge](../03-runtime/05-adb-bridge.md)
- [App sandbox architecture](../08-virtual-device/01-app-sandbox-architecture.md)
- [Embedded Linux runtime](../03-runtime/03-embedded-linux-runtime.md)
- [`THIRD-PARTY-NOTICES.md`](../../../THIRD-PARTY-NOTICES.md)
