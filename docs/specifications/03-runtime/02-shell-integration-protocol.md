# Shell integration protocol

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:core:term`, `:core:distro`, `:app` |
| **Primary sources** | core/term/src/main/java/dev/blamspot/jcode/core/term/TerminalSessionManager.kt (`GUEST_SHELL_INTEGRATION`, `GUEST_OPEN_URL_SHIM`, `NESTED_SHELL_WRAPPER_TEMPLATE`, the reader's `oscHandler`), core/term/src/main/java/dev/blamspot/jcode/core/term/VtParser.kt, native/vt/src/vt_parser.c, app/src/main/java/dev/blamspot/jcode/workbench/SetupTerminalRunner.kt, core/distro/src/main/java/dev/blamspot/jcode/core/distro/DistroService.kt (`CATALOG_SHELL_HELPERS`) |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The private wire protocol between programs running inside the guest Linux environment and the JCode
host. It is carried as **OSC (Operating System Command) escape sequences** in the terminal's own
output stream, which means it works through pipes, over `su`, inside scripts, and from any language
that can `printf` — no socket, no port, no extra file descriptor.

This document is the reference for the sequence formats. It is normative for anyone writing a guest
script or extension that wants to talk to the IDE.

---

## 2. Frame format

```
ESC ] <code> ; <payload> BEL
```

- `ESC` = `0x1B`, `]` = `0x5D`, `BEL` = `0x07`.
- In shell: `printf '\033]<code>;<payload>\007' …`
- The native parser queues each event; `VtParser.drainOsc()` returns them as
  `List<Pair<Int, String>>` (code, payload).
- Native entries are encoded `"<code>;<payload>"` and split at the **first** `;` only — payloads may
  themselves contain `;`.

> The device's native terminal implementation allowlists OSC **7711–7716**. Codes outside that range
> are not delivered.

---

## 3. Code reference

| Code | Name | Payload | Emitted by | Host action |
|---|---|---|---|---|
| `52` | Clipboard write | `c;<base64>` | Any program (standard xterm OSC 52) | `onClipboardWrite(text)` → Android clipboard |
| `7711` | Open file | `<absolutePath>[:line[:col]]` | `jcode` / `code` shell function | `onOpenFileRequest(path)` → open and focus in the editor |
| `7712` | Tab title | `<programName>` | `DEBUG` trap + `PROMPT_COMMAND` | `onTitleChange(id, title)`; sets `Session.foreground` |
| `7713` | Task complete | `<token>;<exitCode>` | `printf` appended to a task | `onTaskComplete(id, payload)` |
| `7714` | Open URL | `<url>` | The `xdg-open` shim | `onOpenUrlRequest(url)` → web preview or chosen browser |
| `7715` | Nested shell open | `open;<token>;<label>` | The nested-shell wrapper | `onNestedShellOpen(parentId, payload)` |
| `7716` | Task progress | `<token>;<percent>;<label>` | `jcode_progress` helper | `onTaskProgress(id, payload)` → determinate progress bar |

### 3.1 OSC 52 — clipboard

Standard xterm semantics, with one deliberate restriction:

> A payload of `?` is a clipboard **read** query. JCode **never answers it** — the guest must not
> see the user's clipboard uninvited.

Writes are `c;<base64>`; the host base64-decodes as UTF-8 and sets the Android clipboard. This is
what makes copy-on-select work in guest CLIs such as Claude Code.

Pasted text going the other way is shell-quoted by `TerminalView.shellQuote` before being written to
the PTY.

### 3.2 OSC 7711 — open a file in the editor

Installed as `/etc/profile.d/jcode-open.sh`, sourced through `/etc/profile`:

```sh
jcode() {
  local a p
  for a in "$@"; do
    [ -z "$a" ] && continue
    case "$a" in
      /*) p="$a" ;;
      *) p="$PWD/$a" ;;
    esac
    printf '\033]7711;%s\007' "$p"
  done
}
command -v code >/dev/null 2>&1 || code() { jcode "$@"; }
```

Relative paths are resolved against `$PWD` before emission, so the host always receives an absolute
guest path. `code` is aliased only when the distro has no real `code` binary.

### 3.3 OSC 7712 — tab title

```sh
if [ -t 1 ]; then
  __jcode_tab() { printf '\033]7712;%s\007' "$1"; }
  __jcode_tab_cmd() {
    case "$BASH_COMMAND" in __jcode_*) return ;; esac
    local w=${BASH_COMMAND%% *}
    __jcode_tab "${w##*/}"
  }
  __jcode_tab_reset() { __jcode_tab terminal; }
  trap '__jcode_tab_cmd' DEBUG
  case ";${PROMPT_COMMAND};" in
    *";__jcode_tab_reset;"*) ;;
    *) PROMPT_COMMAND="__jcode_tab_reset;${PROMPT_COMMAND}" ;;
  esac
fi
```

Three details that matter:

- **`[ -t 1 ]` guard** — the emitter stays silent when stdout is captured, so `$(bash -c …)` does not
  return escape bytes in its output.
- **The literal title `terminal`** (or an empty payload) means "back at the prompt, no foreground
  program". The host maps it to `Session.foreground = null`, which is what makes idle reaping and
  the close-a-busy-terminal warning correct.
- Via `BASH_ENV` this also runs inside `bash run-*.sh`, so a Run tab shows the actual tool
  (`npm`, `vite`, `dotnet`) rather than the wrapper script.

The `PROMPT_COMMAND` injection is idempotent — it checks for itself before prepending.

### 3.4 OSC 7713 — task completion

```
ESC ] 7713 ; <token> ; <exitCode> BEL
```

Appended by the host to a command it wants to observe. Two producers:

**Setup terminal** (`SetupTerminalRunner`) — toolchain installs and project scaffolds run in one
shared "Setup" session so the user can watch and scroll real output. Tasks are serialized because
the session is a single shell:

```sh
<command>; printf '\033]7713;<token>;%s\007' "$?"; rm -f <guestScript>
```

**Run configurations** (`JCodeShell`) — each run terminal appends the same marker with the token
`run`, so the run is "done" once every terminal has reported.

The token disambiguates a stale marker from an abandoned run.

### 3.5 OSC 7714 — open a URL

The shim is installed at `/usr/local/bin/{xdg-open,open,sensible-browser,…}` and `$BROWSER` is
pointed at it:

```sh
#!/bin/sh
printf '\033]7714;%s\007' "$1" >/dev/tty 2>/dev/null || printf '\033]7714;%s\007' "$1"
```

Writing to `/dev/tty` first is what makes it work even when the caller redirects stdout. The host
routes the URL to the in-app web preview or to the user's chosen browser (Settings → Web preview).

### 3.6 OSC 7715 — relocate a nested sub-shell

```
ESC ] 7715 ; open ; <token> ; <label> BEL
```

`token` matches `[A-Za-z0-9_]+` (it is interpolated into guest and host paths). `label` is the shell
name.

The full relocation handshake, including the FIFO ACK/exit protocol and the 3-second watchdog, is
specified in
[Terminal, PTY and VT §5.5](01-terminal-pty-and-vt.md#55-nested-sub-shell-relocation).

> `VtParser.drainOsc`'s KDoc describes a longer payload
> (`open;<token>;<b64label>;<b64cwd>;<b64user>`). That form is not emitted or parsed anywhere. The
> live format is the three-field one above.

### 3.7 OSC 7716 — task progress

```
ESC ] 7716 ; <token> ; <percent> ; <label> BEL
```

Emitted by `jcode_progress`, one of three shell helpers `DistroService` prepends to **every**
catalog install/uninstall script (`CATALOG_SHELL_HELPERS`).

---

## 4. Catalog shell helpers

These are injected unconditionally, so a script may call them on either execution path. Without a
PTY, `JCODE_PROGRESS_TOKEN` is unset and the marker is simply not emitted.

### 4.1 `jcode_progress <percent> <label>`

```sh
jcode_progress() {
  __jc_p=$1
  case "$__jc_p" in ''|*[!0-9]*) return 0 ;; esac
  [ "$__jc_p" -gt 100 ] && __jc_p=100
  if [ -n "${JCODE_PROGRESS_TOKEN:-}" ]; then
    printf '\033]7716;%s;%s;%s\007' "$JCODE_PROGRESS_TOKEN" "$__jc_p" "$2"
  fi
  if [ "$__jc_p" != "$__jc_last_pct" ]; then
    __jc_last_pct=$__jc_p
    printf '[%3d%%] %s\n' "$__jc_p" "$2"
  fi
  return 0
}
```

It emits the OSC marker **and** a plain `[ 42%] …` line, so progress is readable in the terminal
itself as well as on the progress bar. Non-numeric input is ignored; values are clamped to 100.

### 4.2 `jcode_fetch <url> <dest> <from> <to> <label>`

Downloads a file and reports true byte-level progress across the `[from, to]` slice of the bar. It
exists because **curl's own meter is a carriage-return animation from which no percentage can be
recovered once it has passed through a PTY**; `jcode_fetch` reads `Content-Length` up front and
computes the percentage itself.

### 4.3 `jcode_apt <from> <to> <label> <packages…>`

`apt-get update` plus `apt-get install`, reporting apt's own machine-readable `APT::Status-Fd`
percentages (on fd 3), so an apt-based toolchain gets a genuine bar rather than three hand-placed
milestones. It preserves apt's exit status via a temp file, because the exit status of a pipeline is
its last member's.

### 4.4 The `return 0` rule

**Every helper ends with `return 0`.** A script running under `set -e` would otherwise abort on a
progress call whose last comparison happened to be false. Any new helper must do the same.

---

## 5. Environment variables

| Variable | Set by | Meaning |
|---|---|---|
| `JCODE_PROGRESS_TOKEN` | `DistroService` when running a catalog script | Names the task an OSC 7716 marker belongs to. Exported explicitly on the `su - <user> -c …` path too, since a login shell would otherwise drop it |
| `JCODE_NSH_TOP` | The host, on a tab's own top shell | Prevents that shell from relocating itself; cleared by the wrapper so its own sub-shells can still relocate |
| `JCODE_NSH_INLINE` | The user | Per-invocation opt-out of nested-shell relocation |
| `BROWSER` | `TerminalSessionManager` | Points at the OSC 7714 shim |
| `ANDROID_HOME` | `TerminalSessionManager` (`androidSdkEnvVars`) | Present when the Android SDK is installed |
| `ANDROID_SERIAL`, `JCODE_ADB_PORT` | `TerminalSessionManager` (`adbEnvVars`) | Present when the ADB bridge is up |

---

## 6. Invariants and constraints

1. Guard emitters on `[ -t 1 ]` (or write to `/dev/tty`) so captured output is not polluted.
2. Payloads are split at the first `;` only — a payload may contain further separators.
3. New codes must stay within the allowlisted 7711–7716 range, or extend the native allowlist first.
4. OSC 52 read queries are never answered.
5. Tokens interpolated into paths must match `[A-Za-z0-9_]+`.
6. Helper functions must `return 0`.
7. The literal `terminal` title is protocol, not cosmetics — idle reaping depends on it.
8. Sequences must be raw bytes. In Kotlin source, write them as `printf` octal escapes rather than
   embedding a literal `ESC`/`BEL` byte, which is invisible to text tooling.

---

## 7. Failure modes

| Failure | Effect |
|---|---|
| Marker emitted with stdout captured | Escape bytes appear in command output; prevented by the tty guard |
| Stale token from an abandoned run | Host ignores the marker — the token is exactly the disambiguator |
| Helper called under `set -e` with a false last comparison | Would abort the script; prevented by `return 0` |
| Program emits an OSC code outside 7711–7716 | Not delivered |
| Nested-shell FIFO never ACKed | 3-second watchdog aborts and the wrapper runs the shell inline |

---

## 8. References

- [Terminal, PTY and VT](01-terminal-pty-and-vt.md)
- [Toolchain catalog and onboarding](04-toolchain-catalog-and-onboarding.md)
- [Run and build configurations](../05-workspace/03-run-and-build-configurations.md)
- [File format index](../09-platform/01-file-format-index.md)
