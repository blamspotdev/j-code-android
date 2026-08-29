package dev.blamspot.jcode.feature.marketplace

import java.io.File

/** Kind of extension a marketplace entry / installed package provides. */
enum class ExtensionType {
    Templates,
    Language,
    Formatter,
    /** Ships a web frontend ("Manage" UI), e.g. a runtime/tool manager like the VM Manager. */
    App,
    /** Like [App], but its UI is a database manager surfaced under the "DB Managers" drawer. */
    DbManager,
    /** Like [App], but its UI is a source-control manager surfaced in the left-drawer "SCM" panel. */
    Scm,
    /** Like [App], but its UI is a virtual-machine manager surfaced in the left-drawer "VM" panel. */
    Vm,
    Unknown;

    companion object {
        fun from(raw: String?): ExtensionType = when (raw?.lowercase()) {
            "templates" -> Templates
            "language" -> Language
            "formatter" -> Formatter
            "app", "tool", "runtime" -> App
            "dbmanager", "db-manager", "database" -> DbManager
            "scm", "source-control", "sourcecontrol", "vcs" -> Scm
            "vm", "vmmanager", "vm-manager", "virtualmachine", "virtualization" -> Vm
            else -> Unknown
        }
    }
}

/**
 * When an installed extension's contributions (e.g. a language pack's highlighting, completions, and
 * formatting) are allowed to turn on. [OnDemand] is the default; [Manual] disables the extension.
 */
enum class ExtensionActivation {
    /** Active from launch — always on. */
    AutoStart,
    /** Active when relevant (e.g. a file the extension supports is open). The default. */
    OnDemand,
    /** Disabled — the extension's features stay off until the mode is changed. */
    Manual;

    companion object {
        val Default = OnDemand

        fun from(raw: String?): ExtensionActivation = when (raw?.lowercase()) {
            "autostart", "auto", "auto-start" -> AutoStart
            "manual" -> Manual
            "ondemand", "on-demand" -> OnDemand
            else -> Default
        }
    }
}

/**
 * Whether this kind of extension lets the user say when it runs.
 *
 * The kinds that put a surface in the workbench — a manager panel, a source-control view — can be
 * left running from launch, woken when they are needed, or switched off entirely, and which of those
 * you want depends on what the extension costs you while it sits there.
 *
 * The rest contribute to files: highlighting, completions, formatting, templates. There is nothing
 * for them to do until a file they understand is open and nothing they cost while none is, so the
 * question has one sensible answer. Asking it anyway is not a choice, it is only a way to get it
 * wrong — and a Dev Pack switched off by mistake looks like the editor forgetting how to colour
 * Kotlin, not like a setting.
 *
 * [ExtensionType.Unknown] counts as one of the latter. A package whose `type` is missing or
 * unrecognised gets no workbench surface either, since every surface is keyed on the same field.
 */
val ExtensionType.choosesActivation: Boolean
    get() = when (this) {
        ExtensionType.App, ExtensionType.DbManager, ExtensionType.Scm, ExtensionType.Vm -> true
        ExtensionType.Templates, ExtensionType.Language, ExtensionType.Formatter,
        ExtensionType.Unknown,
        -> false
    }

/**
 * The mode actually in force, which is not always the one on disk.
 *
 * An extension that cannot choose reads as [ExtensionActivation.Default] whatever is stored against
 * it. Without that, a mode saved before this rule existed — or by a package that has since changed
 * type — would leave a Dev Pack switched off with nothing in the UI to switch it back on.
 */
fun InstalledExtension.activationIn(modes: Map<String, ExtensionActivation>): ExtensionActivation =
    if (type.choosesActivation) modes[id] ?: ExtensionActivation.Default else ExtensionActivation.Default

/** Whether this extension's contributions are allowed to apply at all. */
fun InstalledExtension.enabledIn(modes: Map<String, ExtensionActivation>): Boolean =
    activationIn(modes) != ExtensionActivation.Manual

/** Things an extension requires or suggests be installed (ids): toolchains, language servers, debug
 *  engines (dbg), and other extensions. */
data class ExtensionDeps(
    val sdks: List<String> = emptyList(),
    val lsps: List<String> = emptyList(),
    val dbg: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = sdks.isEmpty() && lsps.isEmpty() && dbg.isEmpty() && extensions.isEmpty()

    companion object {
        val EMPTY = ExtensionDeps()
    }
}

/** A UI action an extension contributes to a host surface (start-editor screen, drawer header,
 *  editor or explorer context menu). [fileExtensions] optionally limits a context-menu action to
 *  files with a matching extension (lowercase, no dot); empty means every file. [targets] limits an
 *  explorer action to "file" and/or "directory" rows; empty means both. */
data class ContributedAction(
    val id: String,
    val label: String,
    val icon: String? = null,
    val fileExtensions: List<String> = emptyList(),
    val targets: List<String> = emptyList(),
)

/** One terminal of a [RunConfigPreset]: a tab label and the bash command to run in it. In [command],
 *  `{{projectDir}}` expands to the project's guest root; `{{file}}`/`{{dir}}` to the guest path/dir of
 *  the FIRST required file (the anchor); and `{{fileN}}`/`{{dirN}}` (1-based) to each of the preset's
 *  [RunConfigPreset.requires] globs' first match, so a two-file preset can reference both. */
data class RunPresetTerminal(
    val label: String,
    val command: String,
)

/** Which of the Run panel's two lists a [RunConfigPreset] belongs in. A preset that starts something
 *  the user then interacts with is a [Run]; one that produces an artifact and exits is a [Build]. */
enum class RunPresetKind {
    Run,
    Build;

    companion object {
        val Default = Run

        fun from(raw: String?): RunPresetKind = when (raw?.lowercase()) {
            "build", "buildtask", "build-task" -> Build
            else -> Default
        }
    }
}

/** An extension-contributed build/run **preset**, offered in the Run panel's Add picker when the
 *  project contains ALL of [requires] (each a glob; with a slash it matches the file's project-relative
 *  path, without one just the file name — e.g. `*.csproj`, or a package.json anywhere via a globstar
 *  path). [kind] chooses which list it is offered in. A run preset may drive several terminals (e.g. an
 *  ASP.NET server + a Vite client), which is why it needs its own required-file list rather than a
 *  single match; a build task is one command, so a [Build] preset keeps only its first terminal. */
data class RunConfigPreset(
    val id: String,
    val label: String,
    val requires: List<String>,
    val terminals: List<RunPresetTerminal>,
    val readyPort: Int = 0,
    val kind: RunPresetKind = RunPresetKind.Default,
)

/** Actions an extension contributes to host surfaces. Rendered when the extension is active and its
 *  required toolchains are installed; the host routes known action ids (e.g. clone, remoteRepo).
 *  [explorerDecorations] opts the extension into pushing per-file VCS decorations into the Explorer
 *  (`workbench.setExplorerDecorations`); the host keeps its web UI alive per project to feed them. */
data class ExtensionContributions(
    val editorStartActions: List<ContributedAction> = emptyList(),
    val drawerActions: List<ContributedAction> = emptyList(),
    val editorContextActions: List<ContributedAction> = emptyList(),
    val explorerContextActions: List<ContributedAction> = emptyList(),
    /**
     * Managers this extension brings to the **Toolchains** panel, listed under "Managers" and opened
     * as one of its own pages.
     *
     * For a toolchain that has a real manager of its own and loses too much as a catalog entry. The
     * Android SDK is the case this exists for: `sdkmanager` knows about platforms, build-tools, NDKs
     * and system images, each with a revision and a partially-installed state, and a single
     * "Android SDK · Installed" row can say none of it. The pack that owns that knowledge draws the
     * page; JCode only offers the row.
     */
    val toolchainActions: List<ContributedAction> = emptyList(),
    val explorerDecorations: Boolean = false,
    val runConfigPresets: List<RunConfigPreset> = emptyList(),
    /**
     * Debug adapters this extension brings with it, offered by the Debug Engine manager while the
     * extension is installed.
     *
     * A language's adapter belongs to that language's Dev Pack, not to JCode: the IDE is generic and
     * should not ship a JVM debugger to someone who only writes Python. Commands may use
     * `{{extensionDir}}` for the extension's own install directory, which the host substitutes — an
     * adapter the pack bundles installs by copying rather than downloading.
     */
    val debugEngines: List<dev.blamspot.jcode.core.distro.DebugEngineEntry> = emptyList(),
) {
    val isEmpty: Boolean
        get() = editorStartActions.isEmpty() && drawerActions.isEmpty() && editorContextActions.isEmpty() &&
            explorerContextActions.isEmpty() && toolchainActions.isEmpty() && !explorerDecorations &&
            runConfigPresets.isEmpty() && debugEngines.isEmpty()

    companion object {
        val EMPTY = ExtensionContributions()
    }
}

/** A code/config sample shown on an extension's detail page. */
data class CodeSample(
    val title: String,
    val description: String? = null,
    val code: String,
    val language: String? = null,
)

/** One extension listed in the remote marketplace index (marketplace_v2.yaml). */
data class MarketplaceEntry(
    /** Globally-unique reverse-DNS install id (the .jehm `uniqueName`). */
    val id: String,
    val name: String,
    /** Publisher / author / channel that published this extension (back-compat single author). */
    val author: String? = null,
    /** All authors, ordered; the first is the primary author, the rest are co-authors. Empty = fall back to [author]. */
    val authors: List<String> = emptyList(),
    val type: ExtensionType,
    val category: String?,
    val subcategory: String?,
    /** Latest published version, used to detect updates against an installed copy. */
    val version: String?,
    /** Path to the compiled .jext within the marketplace repo, e.g. "dist/jcode.lang.csharp-0.2.0.jext". */
    val jext: String?,
    /** Expected package fingerprint (sha256) from the index; verified against the downloaded .jext. */
    val fingerprint: String? = null,
    /** Lowest JCode app version that can run this extension. */
    val minJCodeVersion: String? = null,
    /** JCode version this extension was built/tested against. */
    val targetJCodeVersion: String? = null,
    /** Highest JCode app version that can run this extension. Null means no ceiling. */
    val maxJCodeVersion: String? = null,
    /** Absolute URL of the marketplace-published icon, shown before install. Null if none. */
    val iconUrl: String? = null,
    /** One-line summary shown in the compact row. */
    val description: String? = null,
    /** Full description shown on the detail page. */
    val longDescription: String? = null,
    /** Usage samples shown on the detail page. */
    val samples: List<CodeSample> = emptyList(),
    val requires: ExtensionDeps = ExtensionDeps.EMPTY,
    val suggests: ExtensionDeps = ExtensionDeps.EMPTY,
    /**
     * Absolute URL of a `.vsix` release asset to install/update from, for entries synthesized from a
     * user-added custom provider (a repo whose releases publish `.vsix` files). When set, install goes
     * through [ExtensionInstaller.installVsixFromUrl] instead of the marketplace `jext` path. Null for
     * normal marketplace (`.jext`) entries.
     */
    val vsixAssetUrl: String? = null,
)

/** Compare dotted versions ("0.2.0" vs "0.1.3"); missing parts count as 0. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().split('.')
    val pb = b.trim().split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val na = pa.getOrNull(i)?.toIntOrNull() ?: 0
        val nb = pb.getOrNull(i)?.toIntOrNull() ?: 0
        if (na != nb) return na - nb
    }
    return 0
}

/**
 * Why [appVersion] cannot run an extension declaring this floor and ceiling, or null when it can.
 * Both bounds include the version they name: `minJCodeVersion: 1.7.0` runs on 1.7.0, and
 * `maxJCodeVersion: 1.8.4` runs on 1.8.4 but not on 1.9. A blank bound is no bound.
 *
 * The installer refuses on this and the Extensions list explains it before anything is downloaded,
 * so both say the same thing for the same reason.
 */
fun jcodeVersionMismatch(
    minJCodeVersion: String?,
    maxJCodeVersion: String?,
    appVersion: String,
    name: String,
): String? {
    if (!minJCodeVersion.isNullOrBlank() && compareVersions(appVersion, minJCodeVersion) < 0) {
        return "$name requires JCode $minJCodeVersion or newer (you have $appVersion)"
    }
    if (!maxJCodeVersion.isNullOrBlank() && compareVersions(appVersion, maxJCodeVersion) > 0) {
        return "$name supports JCode up to $maxJCodeVersion (you have $appVersion)"
    }
    return null
}

/** True when [latest] is a strictly newer version than [installed]. */
fun isUpdateAvailable(latest: String?, installed: String?): Boolean {
    if (latest.isNullOrBlank()) return false
    if (installed.isNullOrBlank()) return false
    return compareVersions(latest, installed) > 0
}

/** The remote marketplace index. */
data class MarketplaceIndex(
    val name: String,
    val version: String?,
    val entries: List<MarketplaceEntry>,
)

/** A coding suggestion offered by a language pack. `$1`/`$0` mark tab stops. */
data class CompletionItem(
    val label: String,
    val detail: String,
    val insert: String,
)

/** A named helper snippet. */
data class HelperSnippet(
    val title: String,
    val snippet: String,
)

/** The editor support a `type: language` extension provides. */
data class LanguagePack(
    val languageId: String,
    val fileExtensions: List<String>,
    // Syntax (for highlighting):
    val lineComment: String?,
    val blockCommentStart: String?,
    val blockCommentEnd: String?,
    val stringDelimiters: List<String>,
    val keywords: Set<String>,
    val types: Set<String>,
    // Formatting (the "basic formatting definitions" the built-in formatter consumes):
    val indent: Int?,
    val trimTrailingWhitespace: Boolean,
    val insertFinalNewline: Boolean,
    /** Best-effort external formatter command; `{{file}}` is the guest path. */
    val formatterCommand: String?,
    val completions: List<CompletionItem>,
    val helpers: List<HelperSnippet>,
) {
    fun matchesFile(name: String): Boolean {
        val lower = name.lowercase()
        return fileExtensions.any { lower.endsWith(it.lowercase()) }
    }
}

/** The control type for a user-configurable extension setting (from the manifest `settings:` block). */
enum class SettingType {
    /** On/off switch. */
    Bool,
    /** One of a fixed set of [ExtensionSetting.options]. */
    Enum,
    /** Free-form integer. */
    Int,
    /** Free-form text, offered a list of suggestions the extension computes. */
    Autocomplete,
    /** Free-form text. The default when a type is missing or unrecognized. */
    Str;

    companion object {
        fun from(raw: String?): SettingType = when (raw?.lowercase()) {
            "bool", "boolean", "toggle" -> Bool
            "enum", "select", "choice" -> Enum
            "int", "integer", "number" -> Int
            "autocomplete", "suggest", "combo" -> Autocomplete
            else -> Str
        }
    }
}

/** One user-configurable option an extension declares in its manifest `settings:` block. */
data class ExtensionSetting(
    val key: String,
    val label: String,
    val type: SettingType,
    /** Default value (as a string); null when the manifest omits it. */
    val default: String? = null,
    /** Allowed values for [SettingType.Enum]. */
    val options: List<String> = emptyList(),
    val description: String? = null,
    /**
     * For [SettingType.Autocomplete]: a shell command whose output lines are offered as suggestions.
     *
     * Run in the Linux runtime when the field appears. `{{key}}` is replaced with the current value of
     * another setting of the same extension, and `{{extensionDir}}` with the extension's own
     * directory, so a manifest can point at a script it ships rather than spell the lookup out here —
     * which is the point: what a tool's models are called is the extension's business, not JCode's.
     */
    val suggestCommand: String? = null,
)

/** An extension that has been downloaded and unpacked under the app's install root. */
data class InstalledExtension(
    val id: String,
    val name: String,
    /** Publisher / author / channel that published this extension (back-compat single author). */
    val author: String? = null,
    /** All authors, ordered; the first is the primary author, the rest are co-authors. Empty = fall back to [author]. */
    val authors: List<String> = emptyList(),
    val type: ExtensionType,
    val version: String?,
    val description: String,
    val dir: File,
    val longDescription: String? = null,
    val samples: List<CodeSample> = emptyList(),
    val templates: List<ProjectTemplate> = emptyList(),
    /** Language packs this extension provides. A pack may bundle several (e.g. a markup pack). */
    val languages: List<LanguagePack> = emptyList(),
    /** The extension's icon file inside [dir], if it shipped one. */
    val iconFile: File? = null,
    /** Relative path (from [dir]) to the extension's web-frontend HTML entry, e.g. "www/index.html". */
    val webUiEntry: String? = null,
    /**
     * Relative path (from [dir]) to a **native** UI payload — an APK the extension ships, loaded
     * into JCode's own process on demand. Null for the ordinary WebView-frontend kind.
     *
     * An APK rather than a bare dex because a dex has no resource table: a plugin with its own
     * drawables or strings needs `addAssetPath`, and that takes an archive.
     */
    val nativeEntry: String? = null,
    /** Fully-qualified class in [nativeEntry] implementing `JCodeNativeExtension`. */
    val nativeClass: String? = null,
    /**
     * Fully-qualified class in [nativeEntry] implementing `JCodeVirtualDeviceGuest`, for a pack that
     * provides JCode's virtual device.
     *
     * Named separately from [nativeClass] because it is loaded into a different process — `:guest`,
     * where the container installs framework hooks the IDE could not survive — by the manifest stub
     * that owns that process rather than by the page loader. Declaring it is also how JCode knows an
     * installed pack *has* a device to offer: there is no device without one.
     */
    val nativeGuestClass: String? = null,
    /** Extension-API version [nativeEntry] was built against; must equal JCode's `JCODE_EXT_ABI`. */
    val nativeAbi: Int = 0,
    /** What this extension's native UI will draw. Any rule matching is enough. */
    val nativeClaims: List<NativeClaim> = emptyList(),
    /** Lowest JCode extension-API version this extension needs (0 = legacy exec-only bridge). */
    val apiMinVersion: Int = 0,
    /** Capability families this extension declares it uses (e.g. "exec", "fs", "workbench"). */
    val apiCapabilities: List<String> = emptyList(),
    /** User-configurable settings this extension declares (surfaced generically in app Settings). */
    val settings: List<ExtensionSetting> = emptyList(),
    /** Toolchains/extensions this extension requires (installed with it) or suggests. */
    val requires: ExtensionDeps = ExtensionDeps.EMPTY,
    val suggests: ExtensionDeps = ExtensionDeps.EMPTY,
    /** Actions this extension contributes to host surfaces (start-editor screen, drawer header). */
    val contributes: ExtensionContributions = ExtensionContributions.EMPTY,
    /** True for an UNSIGNED extension sideloaded via Developer options — the only kind that is
     *  "debuggable" (surfaced in the Extension Dev tools). Signed/marketplace extensions are false. */
    val dev: Boolean = false,
    /** Short name for a tab, when the extension declared one. See [InstalledExtension.tabName]. */
    val shortName: String? = null,
)

/**
 * What to put on this extension's tab.
 *
 * [name] is the marketplace display name and is written to sell the extension, not to fit beside
 * five other tabs — "Codex – OpenAI's coding agent" is wider than the rest of the strip. Where the
 * extension declared a short name for its own view container, that is used instead, matching what
 * VS Code labels the activity bar with.
 */
val InstalledExtension.tabName: String get() = shortName?.takeIf { it.isNotBlank() } ?: name

/** The first bundled language that claims [fileName] (by file extension), or null. */
fun InstalledExtension.languageFor(fileName: String): LanguagePack? =
    languages.firstOrNull { it.matchesFile(fileName) }

/**
 * True when this extension ships native UI that claims [file].
 *
 * Both a type and a path fragment, because "an .xml file" is far too broad: a layout designer wants
 * `res/layout/…` and would be actively wrong on `AndroidManifest.xml`.
 */
fun InstalledExtension.claimsNatively(file: File): Boolean = nativeClaimFor(file) != null

/** The rule by which this extension claims [file], or null when it does not. */
fun InstalledExtension.nativeClaimFor(file: File): NativeClaim? {
    if (nativeEntry == null || nativeClass == null) return null
    return nativeClaims.firstOrNull { it.matches(file) }
}

/**
 * One rule for a file a native extension will draw.
 *
 * A file type alone is far too broad: a layout designer wants `res/layout/…` and would be actively
 * wrong on `AndroidManifest.xml`. Where a *path* cannot separate them — a Kotlin file holding
 * composable UI looks exactly like one that does not — [contains] asks the file itself, which is the
 * only thing that actually knows.
 */
data class NativeClaim(
    /** Lower-case, dotless file extensions (e.g. "xml"). Empty matches any extension. */
    val fileTypes: List<String> = emptyList(),
    /** Path fragment the file must contain, with forward slashes. */
    val pathContains: String? = null,
    /** Text the file must contain. Read from the head of the file, never the whole of it. */
    val contains: String? = null,
    /**
     * The extension setting that governs opening these files in the extension's view straight away.
     *
     * Non-null means "this file type is *primarily* the thing my view shows" — an Android layout is
     * a layout before it is XML, so opening it as text first and making the user find a menu is the
     * wrong default. A `.kt` file is not primarily a composable, which is why nothing declares this
     * for Kotlin.
     *
     * Naming a setting is required rather than optional, so this cannot be used to take a file type
     * over with no way back. The named setting resolving to `false` turns it off; anything else,
     * including a manifest that forgot a default, leaves it on — the extension declaring the field
     * at all is the opt-in.
     */
    val opensInPreviewSetting: String? = null,
    /**
     * What the editor's menu calls the toggle into this view.
     *
     * "Preview" is right for a rendered Markdown document and wrong for a layout designer — you are
     * not previewing it, you are editing it somewhere else. The claim knows what its view is; the
     * menu does not, and should not have to.
     */
    val previewLabel: String? = null,
    /**
     * The icon that label wears, by the same names every other contributed action uses.
     *
     * With this the menu item is entirely the extension's: when it appears (the match), what it
     * says ([previewLabel]) and what it looks like. All the host still supplies is the toggle
     * itself, which flips `EditorTab.previewMode` and so cannot live anywhere but the host.
     */
    val previewIcon: String? = null,
) {
    fun matches(file: File): Boolean {
        if (fileTypes.isNotEmpty() && file.extension.lowercase() !in fileTypes) return false
        pathContains?.let {
            if (!file.path.replace(File.separatorChar, '/').contains(it)) return false
        }
        val needle = contains ?: return true
        // Bounded, because this runs when a tab is opened and the file could be anything. A
        // declaration that decides whether a file is UI is at the top of it or is not there.
        return runCatching {
            file.takeIf { it.isFile && it.length() > 0 }
                ?.inputStream()?.use { stream ->
                    val buffer = ByteArray(CLAIM_HEAD_BYTES)
                    val read = stream.read(buffer)
                    read > 0 && String(buffer, 0, read).contains(needle)
                } ?: false
        }.getOrDefault(false)
    }
}

private const val CLAIM_HEAD_BYTES = 64 * 1024

/** True if this extension ships a web-frontend ("Manage" / DB-manager) UI that resolves on disk. */
/** True when the extension has a UI to show. A `.vsix` builds its own at runtime, so it has one
 *  even though there is no HTML file on disk to point at. */
val InstalledExtension.hasWebUi: Boolean get() = webUiFile != null || isVsix

/** True for an extension imported from a `.vsix`, whose UI comes from its code rather than a file. */
val InstalledExtension.isVsix: Boolean get() = File(dir, VsixPackage.VSIX_MARKER).isFile

/** The extension's web-frontend HTML entry file inside [dir], or null if it doesn't ship one. */
val InstalledExtension.webUiFile: File?
    get() = webUiEntry?.let { File(dir, it) }?.takeIf { it.isFile }

/** The primary author: the first of [authors], or the legacy single [author], or "unknown". */
val MarketplaceEntry.primaryAuthor: String get() = authors.firstOrNull() ?: author ?: "unknown"

/** Co-authors beyond the primary one (empty for single-author / legacy extensions). */
val MarketplaceEntry.otherAuthors: List<String> get() = authors.drop(1)

/** The primary author: the first of [authors], or the legacy single [author], or "unknown". */
val InstalledExtension.primaryAuthor: String get() = authors.firstOrNull() ?: author ?: "unknown"

/** Co-authors beyond the primary one (empty for single-author / legacy extensions). */
val InstalledExtension.otherAuthors: List<String> get() = authors.drop(1)
