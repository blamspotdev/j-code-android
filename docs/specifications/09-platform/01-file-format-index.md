# File format index

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | Various |
| **Primary sources** | See the per-format specification links below |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

One table of every artifact JCode reads or writes, with the specification that defines it. Use this
as the entry point when you have a file and need to know what owns it.

---

## 2. Project and workspace files

| Path | Format | Written by | Specification |
|---|---|---|---|
| `<workspaceRoot>/.jcode-workspace.yaml` | YAML | `ConfigService` | [Configuration model](../05-workspace/02-configuration-model.md) |
| `<folder>/.jcode/<folderName>.yaml` | YAML | `ConfigService`, `WorkspaceManager` | [Configuration model](../05-workspace/02-configuration-model.md) |
| `<project>/.jcode/run.yaml` | YAML (v1 / v2) | `RunConfigStore` | [Run and build configurations](../05-workspace/03-run-and-build-configurations.md) |
| `<filesDir>/trash/<id>/entry.json` | JSON | `Trash` | [Workspaces and projects §7](../05-workspace/01-workspaces-and-projects.md) |

The per-folder YAML doubles as the **role marker** (`type: project | workspace`) and records
`template:` for scaffolded projects.

---

## 3. Extension packages

| Path / artifact | Format | Specification |
|---|---|---|
| `*.jext` (signed) | `JEXT` magic, format 2, Ed25519 + AES-256-GCM | [`.jext` package format](../07-extensions/02-jext-package-format.md) |
| `*.jext` (plain) | ZIP | [`.jext` package format](../07-extensions/02-jext-package-format.md) |
| `.jext-manifest.json` | JSON — per-file SHA-256 + fingerprint | [`.jext` package format §5](../07-extensions/02-jext-package-format.md#5-inner-package) |
| `extension.yaml` | YAML | [Manifest reference](../07-extensions/03-manifest-reference.md) |
| `extension.jehm` | Markdown with YAML frontmatter (legacy) | [`.jext` package format §6](../07-extensions/02-jext-package-format.md#6-legacy-jehm-header) |
| `templates/<id>/template.yaml` | YAML | [Templates and scaffolding](../07-extensions/05-templates-and-scaffolding.md) |
| `*.vsix` | ZIP with `extension/package.json` | [Extension API and hosts §4](../07-extensions/04-extension-api-and-hosts.md#4-vsix-import) |
| `.jcode-vsix` | Marker file | [Extension API and hosts §4](../07-extensions/04-extension-api-and-hosts.md#4-vsix-import) |

---

## 4. Runtime and toolchain

| Path | Format | Specification |
|---|---|---|
| `core/distro/src/main/assets/distro/catalog.yaml` | YAML — the SDK catalog | [Toolchain catalog and onboarding](../03-runtime/04-toolchain-catalog-and-onboarding.md) |
| `filesDir/distros/<id>/metadata.json` | JSON — installed distro metadata | [Embedded Linux runtime](../03-runtime/03-embedded-linux-runtime.md) |
| Rootfs tarballs (`.tar.xz`, `.tar.gz`, `.tar.bz2`) | tar archives | [Embedded Linux runtime §5](../03-runtime/03-embedded-linux-runtime.md#5-rootfs-lifecycle) |
| `<baseUrl>/manifest.json` | JSON — rootfs manifest | [Embedded Linux runtime §5.2](../03-runtime/03-embedded-linux-runtime.md#52-download) |
| Environment backup `.tar.gz` | tar+gzip, via `RootfsArchiver` | [Embedded Linux runtime §5.5](../03-runtime/03-embedded-linux-runtime.md#55-backup) |
| `filesDir/workspace/.migrated-ext4` | Marker file | [Storage and path model](../01-architecture/05-storage-and-path-model.md) |

Guest-side files JCode writes into the rootfs: `/etc/profile.d/jcode-open.sh`,
`/usr/local/bin/{xdg-open,open,sensible-browser}`, `/usr/local/bin/{bash,zsh,…}` wrappers,
`~/.hushlogin`, `~/.htoprc`, `/etc/resolv.conf`, `/etc/hosts`, and the synthetic
`/proc/{stat,loadavg,uptime,version}` bind sources under `filesDir/tmp/proot-fakeproc`. See
[Terminal, PTY and VT §5.3](../03-runtime/01-terminal-pty-and-vt.md#53-session-creation) and
[Embedded Linux runtime §6](../03-runtime/03-embedded-linux-runtime.md#6-synthetic-proc-and-cpustatsampler).

---

## 5. Application state

| Path | Format | Specification |
|---|---|---|
| Room database (`workspaces`, `projects`, `recents`) | SQLite, version 1 | [Workspaces and projects §3](../05-workspace/01-workspaces-and-projects.md#3-persistence--room) |
| DataStore preferences | Jetpack DataStore | [Settings reference](../06-workbench/04-settings-reference.md) |
| `sdk_catalog_installed.<distroId>` | DataStore key | [Toolchain catalog §5](../03-runtime/04-toolchain-catalog-and-onboarding.md#5-catalog-execution-and-state) |
| `filesDir/session/` manifest + `session/buffers/*` | JSON + raw buffers | [Shell layout §7](../06-workbench/01-shell-layout-and-navigation.md#7-session-restore) |
| Settings backup export | via `SettingsBackup` | [Settings reference §8](../06-workbench/04-settings-reference.md#8-backup-and-restore) |

---

## 6. Wire protocols

Not files, but formats all the same:

| Protocol | Framing | Specification |
|---|---|---|
| JCode shell integration | `ESC ] <code> ; <payload> BEL`, codes 7711–7716 and 52 | [Shell integration protocol](../03-runtime/02-shell-integration-protocol.md) |
| LSP | JSON-RPC 2.0 with `Content-Length` headers | [LSP client](../04-language-services/01-lsp-client.md) |
| DAP | `{seq, type, command\|event}` with `Content-Length` headers | [Debug Adapter Protocol](../04-language-services/02-debug-adapter-protocol.md) |
| ADB transport | 24-byte header of six LE `uint32`s | [ADB bridge §3](../03-runtime/05-adb-bridge.md#3-adbwire--the-transport-protocol) |
| ADB host services | `%04x` length prefix + service string | [ADB bridge §5](../03-runtime/05-adb-bridge.md#5-adbhostclient--the-host-protocol) |
| VS Code extension host | Newline-delimited JSON over stdio | [Extension API and hosts §3.3](../07-extensions/04-extension-api-and-hosts.md#33-wire-protocol) |
| Extension WebView bridge | `{"type":"family.verb","payload":{…}}` | [Extension API and hosts §2](../07-extensions/04-extension-api-and-hosts.md#2-the-webview-bridge) |
| Guest session | AIDL (`IGuestSession`) | [App sandbox architecture §4](../08-virtual-device/01-app-sandbox-architecture.md#4-the-aidl-surface) |

---

## 7. Build and release artifacts

| Path | Notes | Specification |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | Debug build output | [Build variants and release](02-build-variants-and-release.md) |
| `builds/jcode-v<version>-<code>-<variant>.apk` | Release script output | [Build variants and release](02-build-variants-and-release.md) |
| `~/.jcode/jcode-release.jks` | Default keystore location | [Build variants and release §5](02-build-variants-and-release.md#5-signing) |
| `core/config/.../schema/{workspace,project}.schema.json` | JSON Schema draft-07, documentation only | [Configuration model §8](../05-workspace/02-configuration-model.md#8-json-schemas) |

---

## 8. Cross-cutting conventions

- **YAML** is the user/project configuration format. Do not migrate config work to JSON or TOML.
- Config saves preserve unknown keys by merging into the raw document, not the typed model.
- Invalid YAML never replaces a last-known-good configuration.
- Duplicate YAML keys are a parse error, not last-wins.
- Session and manifest writes use **write-then-rename**.
- Extension installs unpack to `.tmp-<id>` and swap atomically.

---

## 9. References

- [Storage and path model](../01-architecture/05-storage-and-path-model.md)
- [Configuration model](../05-workspace/02-configuration-model.md)
- [`.jext` package format](../07-extensions/02-jext-package-format.md)
