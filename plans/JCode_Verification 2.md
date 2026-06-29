# J Code — Verification Plan (v2, basic)

> Companion to [`JCode_Plan.md`](JCode_Plan.md). Per-phase checklists of "does this feature actually work in front of a user." Build/compile is assumed via CI — this doc is about **behavior**.
>
> A phase is done when every box below it is ticked on at least one phone and one tablet.

---

## Conventions

- **Devices:** one phone (e.g. Pixel 6a) and one tablet (e.g. Pixel Tablet). Add a Bluetooth keyboard for any phase touching shortcuts.
- **Run pattern:** install debug build → execute the checks for the current phase → tick the boxes → fix what's red → re-run.
- **Smoke suite** (at the bottom): rerun before every release.

---

## Phase 0 — Bootstrap

- [ ] App installs and launches on phone and tablet.
- [ ] Screen shows "J Code" centered.
- [ ] No crash on launch (`logcat | grep -i fatal` is empty).

---

## Phase 1 — Adaptive shell

- [ ] Phone portrait: bottom navigation bar + drawer slides over content.
- [ ] Tablet landscape: side rail + persistent drawer + main pane all visible.
- [ ] Rotating the device doesn't lose state or flicker.
- [ ] `Ctrl+Shift+P` opens the command palette; Esc dismisses.
- [ ] Status bar at bottom shows placeholder cells (branch, errors, cursor, language, distro).

---

## Phase 2 — Workspace & filesystem

- [ ] First launch creates a default workspace; relaunch reopens it.
- [ ] "Add Project" creates a folder in the default location and shows it in the explorer.
- [ ] "Open External Folder" via SAF picker remembers access across relaunch.
- [ ] Files created/deleted via `adb push`/`rm` appear/disappear in the app within a couple of seconds.

---

## Phase 3 — Explorer

- [ ] Tree view shows the project; expanding folders works.
- [ ] List view toggle shows current folder contents with name/size/mtime.
- [ ] Create / rename / copy / delete via context menu work; deleted files appear in `.jcode/trash/`.
- [ ] Drag a file into another folder: it moves.
- [ ] Scrolling a folder with thousands of files stays smooth.

---

## Phase 4 — Editor (built from scratch)

### 4.1–4.3 Buffer + state + undo
- [ ] Open a small file (5 kLOC): renders within a second.
- [ ] Open a large file (50–100 MB): opens without freezing or OOM.
- [ ] Typing text changes the buffer; saving writes the same bytes back to disk.
- [ ] Undo (`Ctrl+Z`) reverts the last edit; redo restores it.
- [ ] Typing then waiting 1 s then typing more produces two undo entries (not one).

### 4.4 Rendering
- [ ] Text renders in a monospace font; line numbers in the gutter line up.
- [ ] Scrolling top-to-bottom of a 10 kLOC file is smooth (no obvious jank).
- [ ] Ligatures (`=>`, `!=`) render when enabled.
- [ ] A file with mixed scripts (Arabic + English) renders without garbled glyphs.

### 4.5 IME — the make-or-break sub-phase
Use GBoard plus one CJK input (Pinyin or Hangul) on the phone.
- [ ] English typing produces exactly what you typed, even with autocorrect on.
- [ ] Backspace over an emoji removes the whole emoji (not half).
- [ ] Pinyin: typing `nihao` shows candidates; picking 你好 commits `你好`.
- [ ] Hangul: typing `안녕` via the keyboard commits exactly `안녕`.
- [ ] While composing CJK, the candidate window stays anchored to the cursor when you scroll.
- [ ] Esc cancels composing without committing.
- [ ] Landscape on phone does *not* open fullscreen IME extract UI.

### 4.6 Gestures & input
- [ ] Tap places the caret; double-tap selects a word; triple-tap selects a line.
- [ ] Long-press shows selection handles and a magnifier.
- [ ] Two-finger pinch zooms the font.
- [ ] Fling scrolls and decays with friction.
- [ ] Soft keyboard appearing pushes content up and keeps the caret visible.
- [ ] On Compact, a virtual key bar (Esc/Tab/Ctrl/arrows) appears above the keyboard; on a device with a Bluetooth keyboard, it doesn't.
- [ ] Mouse hover changes the cursor over text vs gutter vs handles; right-click opens a context menu.

### 4.7 Decorations
- [ ] Selection rectangles render behind text, not on top of it.
- [ ] Adding a test decoration on one line doesn't repaint the whole screen.
- [ ] An inline widget (test inlay) shifts following glyphs right; clicking on the widget doesn't drop a caret inside it.

### 4.8 Tabs & splits
- [ ] Opening 5 files shows 5 tabs; closing with unsaved changes prompts.
- [ ] Split editor right opens a second pane; edits in one update the other.
- [ ] Dragging the split handle resizes both panes.

### 4.9 Open/save
- [ ] BOM and CRLF files round-trip without corruption.
- [ ] Binary file (PNG) shows a "cannot open binary" dialog.
- [ ] A 50 MB log file opens read-only with a banner.
- [ ] Save writes via temp-rename (mid-save kill doesn't truncate the file).

### 4.10 Completion / snippets / ghost text
- [ ] Completion popup opens on prefix and stays anchored at the caret while scrolling.
- [ ] Tab/Enter accepts; Esc dismisses.
- [ ] A snippet with `${1:name}` placeholders is navigable with Tab.
- [ ] Ghost text (when AI is enabled with a mock endpoint) renders dim; Tab inserts; Esc clears.

### 4.11 Accessibility
- [ ] TalkBack reads each visible line; selection changes are announced.
- [ ] Caret moves at character / word / line granularity via the TalkBack rotor.

### 4.12 Multi-cursor
- [ ] `Ctrl+D` adds the next occurrence; typing edits all carets at once.
- [ ] `Alt`+drag (with keyboard) makes a column selection.

### 4.13 Folding
- [ ] Clicking a chevron in the gutter folds a function; clicking again unfolds.
- [ ] Folded state persists after closing and reopening the file.

### 4.14 Minimap
- [ ] Minimap renders on the right when enabled.
- [ ] Clicking jumps to that part of the file; dragging scrubs.

### 4 — Performance sanity (not measured precisely, just "feels right")
- [ ] Typing in a 10 kLOC file feels instant.
- [ ] Scrolling a 100 MB file stays responsive.
- [ ] Idle editor doesn't drain battery or run the CPU hot.

---

## Phase 5 — Tree-sitter & languages

- [ ] One file per supported language renders with syntactic colors.
- [ ] Outline panel shows top-level symbols and clicking jumps to them.
- [ ] Editing produces an updated outline within a moment.
- [ ] Renaming a file extension switches the language on the open tab.

---

## Phase 6 — Configuration

- [ ] Changing `editor.fontSize` in the Settings UI scales the editor immediately.
- [ ] Editing the YAML file in `adb push` is picked up live.
- [ ] Project-level config overrides workspace-level config (e.g. `tabSize`).
- [ ] A malformed YAML doesn't crash the app; the previous valid config keeps working.

---

## Phase 7 — Termux + proot-distro

### Wizard
- [ ] Wizard detects missing Termux and links to F-Droid.
- [ ] After installing Termux and granting `RUN_COMMAND`, the wizard advances.
- [ ] Setting `allow-external-apps=true` and running the wizard's "Test connection" succeeds.
- [ ] Choosing Ubuntu or Debian installs the distro (progress visible, survives backgrounding).
- [ ] Toolchain bootstrap (`apt install ...`) completes; a non-root `jcode` user is created.
- [ ] Final smoke test (`clangd --version && node --version && echo ok`) returns `ok`.

### Distro use
- [ ] A new terminal tab lands at `/workspace` as user `jcode`.
- [ ] Files under `/storage/emulated/0/JCode/projects/` appear under `/workspace/` inside the distro; edits flow both ways immediately.
- [ ] Running `vim` or `htop` in the terminal renders without garbled output; resize follows the pane width.
- [ ] Opening 2-3 terminals each have independent cwd and history.
- [ ] A persistent foreground-service notification shows while sessions are active and disappears when all close.

### Recovery
- [ ] If proot fails with a seccomp error, the app retries automatically with `PROOT_NO_SECCOMP=1` and surfaces a notice.
- [ ] Uninstalling Termux is detected on next launch; user is offered to restore from a backup if one exists.

---

## Phase 8 — Language services

- [ ] Identifier completion (tree-sitter, Tier 0) works in every supported language without any LSP installed.
- [ ] After the ctags index builds, `Ctrl+T` finds workspace symbols and "Go To Definition" navigates across files.
- [ ] "Install clangd" from the SDK Manager runs `apt install clangd` inside the distro and returns success.
- [ ] After install, opening a C++ file produces hover popups and red squigglies for typos within a couple of seconds.
- [ ] The same flow works for at least one of: kotlin-language-server, typescript-language-server, pyright.
- [ ] Stopping a running LSP from the BackendService notification doesn't break the editor.

---

## Phase 9 — Source control

- [ ] Cloning a public repo over HTTPS (with a saved PAT) succeeds.
- [ ] Editing files marks them as changed in the SCM panel.
- [ ] Stage / commit / push round-trip works; the commit appears on the remote.
- [ ] Branch create / checkout / merge works.
- [ ] A merge conflict surfaces a conflict UI; resolving and committing completes the merge.
- [ ] Side-by-side diff renders for any changed file.
- [ ] An SSH key generated in J Code can be exported into the distro and used by CLI `git`.

---

## Phase 10 — Search

- [ ] "Find in files" streams results as they're found; a 200 kLOC fixture finishes in a few seconds.
- [ ] Regex, case-sensitive, whole-word, and include/exclude globs all behave correctly.
- [ ] Replace-all applies only the checked hits.
- [ ] `Ctrl+P` fuzzy-finds files; `Ctrl+T` finds symbols (ctags ∪ LSP).
- [ ] `Ctrl+F` in-file find + replace works.

---

## Phase 11 — Real-time errors

- [ ] Saving a broken JSON file shows an error in the Problems pane and a gutter squiggle.
- [ ] Introducing a typo with clangd running shows a red squiggle within ~2 s.
- [ ] Status-bar error/warning counts match the Problems pane.
- [ ] Clicking a problem jumps to file:line.

---

## Phase 12 — Refactoring

- [ ] LSP-backed rename updates all references across files; undo reverts everything.
- [ ] Without an LSP, rename only updates the local scope and warns about cross-file refs.
- [ ] Lightbulb code actions (e.g. "remove unused import") apply cleanly.
- [ ] "Format on save" runs the configured formatter inside the distro.

---

## Phase 13 — Debugging

- [ ] Launching an lldb session on a tiny C program stops at `main`.
- [ ] Step over / in / out moves the caret accordingly.
- [ ] Breakpoint set by gutter click is hit on next run; conditional breakpoint stops only when the condition holds.
- [ ] Variables panel shows locals; expanding a struct works.
- [ ] Evaluate expression returns the expected value.
- [ ] Breakpoints and launch configs survive a restart.
- [ ] The same flow works for at least one of: debugpy (Python), node `--inspect`.

---

## Phase 14 — Extensions

- [ ] Installing a theme extension makes the theme appear in the picker and apply.
- [ ] Installing a grammar extension makes files of that type highlight.
- [ ] A sample WASM "Hello World" command appears in the palette and runs.
- [ ] An extension without `fs.read` cannot read project files.
- [ ] An unsigned `.jcext` is rejected at install.
- [ ] Disabling / uninstalling an extension removes its contributions.

---

## Phase 15 — Themes

- [ ] All built-in themes apply immediately to UI and editor.
- [ ] The preview tile in Settings reflects each theme's actual colors.
- [ ] Theme choice persists across restart.

---

## Phase 16 — Keyboard / mouse

- [ ] All default shortcuts fire (Save, Find, Format, Toggle Terminal, Run, Debug, Comment, Indent, Multi-cursor add, etc.).
- [ ] Chord shortcuts (e.g. `Ctrl+K Ctrl+S`) work; partial chord times out after 1 s.
- [ ] On a Chromebook, `Meta+/` shows the registered shortcuts.
- [ ] Without a physical keyboard, list rows are touch-comfortable (≥40 dp); with one connected, they're dense.

---

## Phase 17 — State persistence

- [ ] `am force-stop` then relaunch restores open tabs, layout, cursor positions, and unsaved drafts.
- [ ] Saving clears the corresponding drafts.
- [ ] On startup with previously-open terminals, the app offers to restore them (and does, with the same cwd in the distro).
- [ ] Welcome screen lists recent workspaces; pinned ones stay at the top.

---

## Phase 18 — Polish & release

- [ ] Cold start on the phone feels under ~2 s.
- [ ] TalkBack pass: every interactive element is announced; navigation order is logical.
- [ ] System font scale 200%: nothing truncates.
- [ ] Forcing a crash uploads a symbolicated trace to Crashlytics (when enabled); opt-out actually stops uploads.
- [ ] AAB upload to Play Internal track has no policy warnings.

---

## Smoke suite (run before every release)

1. App launches on a fresh install.
2. Default workspace opens; a sample project is visible in the explorer.
3. Open a file, type something, save it, close and reopen it — content is preserved.
4. Open the command palette and run a command.
5. Open a terminal — lands inside the active distro at `/workspace`.
6. Tree-sitter highlights a TypeScript file correctly.
7. Find in files returns expected matches.
8. Git status shows the test edit; staging and committing works.
9. Settings change (`editor.fontSize`) takes effect immediately.
10. App survives 10 minutes of mixed exploratory use with no fatal logs.

---

## Failure-injection sanity (run once per phase that touches the relevant area)

- [ ] Network drops mid-clone / mid-`apt install`: shows an error, doesn't crash, retry works.
- [ ] Storage full: save surfaces an error; buffer contents not lost.
- [ ] Termux uninstalled mid-session: terminal/LSP degrade gracefully; wizard is reachable.
- [ ] Killing the app process during heavy editing: state restores on relaunch.
