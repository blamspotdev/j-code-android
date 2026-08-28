# Manifest reference — `extension.yaml`

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:feature:marketplace` |
| **Primary sources** | feature/marketplace/src/main/java/dev/blamspot/jcode/feature/marketplace/ExtensionInstaller.kt (`loadInstalled`, `headerMap`, `parseDeps`, `parseContributions`, `parseLanguages`, `parseSamples`, `loadTemplate`), feature/marketplace/src/main/java/dev/blamspot/jcode/feature/marketplace/ExtensionManifestValidator.kt, feature/marketplace/src/main/java/dev/blamspot/jcode/feature/marketplace/MarketplaceModels.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The complete, key-by-key reference for `extension.yaml` — every key the installer reads and every
contribution point the host honours.

Unrecognized top-level keys are **ignored with a warning**, so a typo silently drops a whole
section. `ExtensionManifestValidator.KNOWN_TOP_LEVEL` is the authoritative list; keep both it and
this document in step when adding a key.

---

## 2. Top-level keys

```
id, name, publisher, author, authors, type, version, description,
longDescription, shortDescription, samples, templates, language, languages,
settings, api, requires, suggests, contributes, entry, images,
minJCodeVersion, targetJCodeVersion, maxJCodeVersion, category, subcategory
```

| Key | Type | Required | Notes |
|---|---|---|---|
| `id` | string | **Yes** | Reverse-DNS install id. Without it `loadInstalled` returns `null` and the package is not an extension |
| `name` | string | No | Defaults to `id`. Equalling `id` raises a warning |
| `publisher` / `author` | string | No | `publisher` wins; `author` is the back-compat spelling |
| `authors` | string list | No | Ordered; first is primary. Empty falls back to `author` |
| `type` | string | No | See [Extension model §2](01-extension-model-and-lifecycle.md#2-extension-types). Unrecognized → `Unknown` + warning |
| `version` | string | No (warned) | Authoritative for update comparison |
| `description` | string | No | Defaults to `""` |
| `longDescription` | string | No | |
| `shortDescription` | string | No | Accepted; not read by `loadInstalled` |
| `samples` | list | No | See §3 |
| `templates` | string list | No | Template **ids**; each resolves to `templates/<id>/template.yaml` |
| `language` | map | No | A single Dev Pack (legacy form) |
| `languages` | list of maps | No | Several Dev Packs. **Takes precedence over `language`** |
| `settings` | list | No | See §4 |
| `api` | map | No | `minApiVersion`, `capabilities` |
| `requires` | map | No | Installed with the extension |
| `suggests` | map | No | Offered, not forced |
| `contributes` | map | No | See §6 |
| `entry` | map | No | `ui`: relative path to the web-UI HTML |
| `images` | map | No | `icon`: relative path |
| `minJCodeVersion` | string | No | Install refused below this |
| `maxJCodeVersion` | string | No | Install refused above this; absent means no ceiling |
| `targetJCodeVersion` | string | No | Accepted; informational |
| `category` / `subcategory` | string | No | Marketplace classification |

### 2.1 `api`

```yaml
api:
  minApiVersion: 1
  capabilities: [exec, fs, workbench]
```

`minApiVersion` defaults to `0` (the legacy exec-only bridge). Greater than the host's API version is
a **validation error**.

Known capabilities (`KNOWN_CAPABILITIES`): `api`, `exec`, `fs`, `config`, `workbench`, `service`.
Anything else warns.

### 2.2 `entry` and `images`

```yaml
entry:
  ui: www/index.html
images:
  icon: media/icon.png
```

`entry.ui` is honoured only if the file **exists on disk**; a typo makes `webUiEntry` null, which is
why the validator re-checks the raw value against disk and reports a hard error.

`images.icon` falls back to the conventional locations `media/icon.png` then `icon.png`.

Both are read from a **merged header**: a legacy `extension.jehm` frontmatter overlaid by
`extension.yaml`, with YAML winning, so pre-merge and post-merge installs both resolve.

### 2.2a `entry.native` — an extension that ships code

```yaml
entry:
  native:
    apk: lib/android-pack.apk      # or `dex:` for a plugin that owns no resources
    class: dev.jcode.ext.android.AndroidPackExtension
    guest: dev.jcode.ext.android.vdevice.VirtualDeviceGuest   # optional
    abi: 8
    claims:
      - fileTypes: [xml]
        pathContains: res/layout
        opensInPreview: openLayoutsInDesigner
        label: Show Designer
        icon: image
```

A native extension is loaded into **JCode's own process** by `NativeExtensionLoader`, with JCode's
class loader as its parent, so it shares one Compose runtime with the workbench.

| Key | Meaning |
|---|---|
| `apk` / `dex` | the payload, relative to the install directory. `dex` is enough for a plugin that resolves no resources; `apk` is required for one with its own drawables, ids or assets, because a resource table needs an archive for `addAssetPath` to attach |
| `class` | the entry class, implementing `JCodeNativeExtension`. **One per extension** — a plugin that draws several surfaces dispatches on `Params.VIEW` rather than declaring several entries |
| `guest` | *(added at ABI 8)* a class implementing `JCodeVirtualDeviceGuest`, loaded into the `:guest` process by the app's `GuestSessionService` stub. Declaring it is also **how JCode knows an installed pack provides the virtual device** — see [App sandbox §1a](../08-virtual-device/01-app-sandbox-architecture.md#1a-where-this-lives-and-why-it-is-split) |
| `abi` | must equal the host's `JCODE_EXT_ABI` exactly; a mismatch is refused with a message naming both numbers |
| `claims` | which files this plugin draws. A file type alone is too broad, so a rule may also require a `pathContains` fragment or a `contains` substring of the file's own text |

**Only officially signed extensions load native code**, because loading it means running it inside
JCode. The one way past that is `Settings → Developer options`, which also permits unsigned
sideloading — it exists so a pack that ships the virtual device can be worked on at all, since
otherwise every test round would need the package signed first.

### 2.3 `requires` / `suggests`

```yaml
requires:
  sdks: [nodejs]
  lsps: [typescript-language-server]
  dbg: [debugpy]          # `debuggers:` is also accepted and merged
  extensions: [dev.example.other]
```

Ids are the catalog ids from
[Toolchain catalog and onboarding](../03-runtime/04-toolchain-catalog-and-onboarding.md).

Prefer `suggests.sdks` for a Dev Pack, so installing a language pack does not force a large toolchain
download.

---

## 3. `samples`

```yaml
samples:
  - title: Hello
    description: A minimal example
    code: |
      print("hi")
    language: python
```

`code` is required; a sample without it is dropped. `title` defaults to `"Sample"`.

---

## 4. `settings`

```yaml
settings:
  - key: myext.mode
    label: Mode
    type: enum
    options: [fast, thorough]
    default: fast
    description: How hard to try
```

`key` is required. `label` defaults to `key`.

```kotlin
enum class SettingType { Bool, Enum, Int, Str }
```

| `type` value accepted | Resolves to |
|---|---|
| `bool`, `boolean`, `toggle` | `Bool` |
| `enum`, `select`, `choice` | `Enum` |
| `int`, `integer`, `number` | `Int` |
| anything else, or missing | `Str` |

These appear generically in the app's Settings screen.

---

## 5. `language` / `languages` — Dev Packs

```yaml
languages:
  - languageId: html
    fileExtensions: [.html, .htm]
    lineComment: null
    blockCommentStart: "<!--"
    blockCommentEnd: "-->"
    stringDelimiters: ["\"", "'"]
    keywords: [...]
    types: [...]
    indent: 2
    trimTrailingWhitespace: true
    insertFinalNewline: true
    formatterCommand: prettier --write {{file}}
    completions:
      - label: div
        detail: HTML element
        insert: <div>$0</div>
    helpers:
      - title: Boilerplate
        snippet: "<!doctype html>…"
```

```kotlin
data class LanguagePack(
    val languageId: String, val fileExtensions: List<String>,
    val lineComment: String?, val blockCommentStart: String?, val blockCommentEnd: String?,
    val stringDelimiters: List<String>, val keywords: Set<String>, val types: Set<String>,
    val indent: Int?, val trimTrailingWhitespace: Boolean, val insertFinalNewline: Boolean,
    val formatterCommand: String?,
    val completions: List<CompletionItem>, val helpers: List<HelperSnippet>,
) {
    fun matchesFile(name: String): Boolean   // case-insensitive suffix match on fileExtensions
}
```

The syntax fields feed `NativeHighlighter.createProfile`; the formatting fields feed
`CodeFormatter`. See
[Syntax highlighting and completion](../02-editor/05-syntax-highlighting-and-completion.md).

> `formatterCommand` (`{{file}}` is the guest path) is parsed and **not executed**. Only the built-in
> formatter runs.

A pack of any `type` may declare both `languages` and `templates`; missing sections resolve to empty.

---

## 6. `contributes`

Exactly six contribution points are read (`parseContributions`):

```yaml
contributes:
  editorStartActions:    [ … ]
  drawerActions:         [ … ]
  editorContextActions:  [ … ]
  explorerContextActions:[ … ]
  explorerDecorations:   true
  runConfigPresets:      [ … ]
```

### 6.1 Action lists

The four action lists share one shape:

```yaml
- id: myext.doThing          # required; the entry is dropped without it
  label: Do the thing        # defaults to id
  icon: Run                  # optional JCodeIcon name
  fileExtensions: [ts, tsx]  # optional; leading "." stripped, lowercased
  targets: [file, directory] # optional; only "file"/"directory" survive
```

| List | Surface |
|---|---|
| `editorStartActions` | The empty-editor start screen |
| `drawerActions` | Drawer header actions |
| `editorContextActions` | The editor's long-press menu |
| `explorerContextActions` | The Explorer's file/folder long-press menu |

Dispatch keys are `"<extensionId>:<actionId>"`. Visibility is decided by
`explorerActionAppliesTo(action, name, isDirectory)`.

### 6.2 `explorerDecorations`

A boolean (also accepted as the string `"true"`). When set, the extension may push VCS status badges
into the Explorer through `ExplorerScmUi` — see
[Search and source control](../04-language-services/03-search-and-source-control.md).

### 6.3 `runConfigPresets`

```yaml
runConfigPresets:
  - id: vite-dev
    label: Vite dev server
    kind: run                                     # run (default) | build
    requires: ["vite.config.*", "package.json"]   # ALL globs must be present
    # or: match: "package.json"                    (single-glob shorthand)
    terminals:
      - label: Web
        command: npm run dev
    # or: command: npm run dev  +  terminalLabel: Web   (one-terminal shorthand)
    readyPort: 5173
```

Parsing rules:

- `id` is required.
- `requires` is the list of globs that must **all** be present; `match` is shorthand for a
  single-glob preset and is merged into `requires` (then deduplicated). A preset with an empty
  `requires` is **dropped**.
- `terminals` entries need a non-blank `command`; `label` defaults to `"Run"`. If `terminals` yields
  nothing, a top-level `command` is used with `terminalLabel` (default `"Run"`). A preset with no
  terminals is **dropped**.
- `readyPort` tolerates a YAML integer, a quoted integer, and a float (`5173.0`); anything
  unparseable becomes `0`.
- `kind` is `run` (the default) or `build`, and decides which of the Run panel's two segments offers
  the preset. Anything else parses as `run`.

### 6.3.1 `kind: build`

A build task is **one command that produces an artifact and exits**, so a `build` preset is narrowed
to fit `BuildConfig(name, command)`:

| Field | Under `kind: build` |
|---|---|
| `terminals` | Only the **first** is used; the rest are dropped (the validator warns) |
| `readyPort` | Ignored — nothing is polled (the validator warns) |
| `requires`, substitutions | Unchanged |

Presets are evaluated **before** built-in probes in both segments, so an extension's suggestion is not
crowded out by generic detection on a large repository. In the Build segment a built-in probe whose
Gradle tasks a preset already covers is then dropped, so installing a pack that ships
`gradlew assembleDebug` does not show that task twice. See
[Run and build configurations](../05-workspace/03-run-and-build-configurations.md).

---

## 7. `templates`

`templates:` lists template **ids**. Each resolves to `templates/<id>/template.yaml` inside the
package; the file's own schema is specified in
[Templates and scaffolding](05-templates-and-scaffolding.md).

---

## 8. Worked example

```yaml
id: dev.example.webpack
name: Web Dev Pack
publisher: Example
authors: [Ada Lovelace, Grace Hopper]
type: language
version: 1.2.0
description: HTML, CSS and JSON support with project templates.
minJCodeVersion: "1.3.5"

api:
  minApiVersion: 1
  capabilities: [exec, workbench]

suggests:
  sdks: [nodejs]
  lsps: [vscode-html-language-server]

languages:
  - languageId: html
    fileExtensions: [.html, .htm]
    blockCommentStart: "<!--"
    blockCommentEnd: "-->"
    indent: 2

templates: [vite-app]

contributes:
  explorerContextActions:
    - id: formatAll
      label: Format all HTML
      fileExtensions: [html]
      targets: [file]
  runConfigPresets:
    - id: vite-dev
      label: Vite dev server
      requires: ["vite.config.*"]
      command: npm run dev
      terminalLabel: Web
      readyPort: 5173

entry:
  ui: www/index.html
images:
  icon: media/icon.png
```

---

## 9. Invariants and constraints

1. `id` is mandatory and permanent.
2. Unknown top-level keys are ignored with a warning — validate before publishing.
3. `languages` beats `language` when both are present.
4. `entry.ui` must exist on disk.
5. Contribution entries without their required field (`id`, `command`, `requires`) are silently
   dropped, not errors.
6. Adding a key means updating `KNOWN_TOP_LEVEL`, the parser, and this document.
7. `formatterCommand` is currently inert.
8. `entry.native.abi` must equal the host's `JCODE_EXT_ABI` exactly — not "at least".
9. An extension declaring `entry.native.guest` is offering to provide the virtual device; at most one
   installed extension should, and JCode takes the first it finds.

---

## 10. References

- [Extension model and lifecycle](01-extension-model-and-lifecycle.md)
- [`.jext` package format](02-jext-package-format.md)
- [Templates and scaffolding](05-templates-and-scaffolding.md)
- [Run and build configurations](../05-workspace/03-run-and-build-configurations.md)
- [Syntax highlighting and completion](../02-editor/05-syntax-highlighting-and-completion.md)
