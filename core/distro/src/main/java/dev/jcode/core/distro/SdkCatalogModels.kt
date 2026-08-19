package dev.jcode.core.distro

data class SdkCatalogEntry(
    val id: String,
    val category: SdkCatalogCategory,
    val name: String,
    val description: String,
    val installScript: String,
    val verifyScript: String,
    val uninstallScript: String,
    /** Optional: exits 0 when a newer version is available. Empty = update detection skipped. */
    val updateCheckScript: String = "",
    /** When true, several versions coexist; the newest installed one is the default (on PATH). When
     *  false, installing a version replaces the current one. */
    val multiVersion: Boolean = false,
    /** Optional: prints the installable versions, one per line, newest first (line 1 is treated as
     *  "latest"). Empty = no version picker; [installScript] resolves the latest version itself.
     *  The chosen version is substituted for the `{{version}}` placeholder in the install/uninstall
     *  scripts (and exported as `JCODE_VERSION`); "latest" is passed through so the script can resolve it. */
    val versionsScript: String = "",
    /** For [multiVersion]: prints the currently-installed versions, one per line, newest first. A line
     *  may mark the one currently on PATH as `version<TAB>active`; when none does, the newest is
     *  assumed active. Empty = fall back to the binary [verifyScript] (installed / not-installed only). */
    val installedVersionsScript: String = "",
    /** For [multiVersion]: makes an already-installed version the active one (the one on PATH), the
     *  equivalent of `nvm use` / `rustup default`. The version arrives as `JCODE_VERSION` and via the
     *  `{{version}}` placeholder. Empty = this tool has no single active version (Android SDK platforms
     *  and .NET SDK bands are all usable at once), and no Use action is offered. */
    val useVersionScript: String = "",
    /** Distro ids this entry supports. Empty = every distro. */
    val supportedDistros: List<String> = emptyList(),
    /** Arch keys (Arch.rootfsKey: "arm64"/"amd64") this entry supports. Empty = every arch. */
    val supportedArches: List<String> = emptyList(),
    /** Other SDK catalog ids that must be installed first (e.g. android-sdk requires android-prereqs). */
    val requiredSdks: List<String> = emptyList(),
    /** Floor for this entry's install timeout, in minutes. The user's "Toolchain install timeout"
     *  setting still applies when it is larger; this only stops an entry that genuinely needs longer
     *  (hundreds of MB over a phone connection) from being killed by a shorter global default.
     *  0 = no floor. */
    val minInstallTimeoutMinutes: Int = 0,
) {
    /** This entry's install budget given the user's global [globalTimeoutMs] setting. */
    fun installTimeoutMs(globalTimeoutMs: Long): Long =
        maxOf(globalTimeoutMs, minInstallTimeoutMinutes * 60_000L)

    /** Whether this entry should be offered on the given environment. */
    fun isSupportedOn(distroId: String, arch: Arch): Boolean =
        (supportedDistros.isEmpty() || distroId in supportedDistros) &&
            (supportedArches.isEmpty() || arch.rootfsKey in supportedArches)
}

enum class SdkCatalogCategory(val label: String) {
    Languages("Languages"),
    BuildTools("Build Tools"),
    Android("Android"),
    DotNet(".NET"),
    Embedded("Embedded"),
    Databases("Databases"),
    Virtualization("Virtualization"),
    Ai("AI");

    companion object {
        fun fromSerialized(raw: String?): SdkCatalogCategory? {
            val normalized = raw
                ?.trim()
                ?.lowercase()
                ?.replace("-", "")
                ?.replace("_", "")
                ?.replace(" ", "")
                ?: return null
            return when (normalized) {
                "languages", "language" -> Languages
                "buildtools", "buildtool", "tools" -> BuildTools
                "android" -> Android
                "dotnet", ".net", "net" -> DotNet
                "embedded" -> Embedded
                "databases", "database", "db" -> Databases
                "virtualization", "virtual", "vm", "emulation" -> Virtualization
                "ai", "agents", "agent" -> Ai
                else -> null
            }
        }
    }
}

enum class SdkCatalogAction(val label: String) {
    Install("Install"),
    Uninstall("Remove"),
    /** Switch an installed multi-version tool to a different one of its versions. */
    Use("Use"),
}

/** One installable version, with an optional presentational [tag] (e.g. "LTS Jod") emitted by a
 *  [SdkCatalogEntry.versionsScript] as a tab-separated second column. */
data class CatalogVersion(val version: String, val tag: String = "")

data class SdkCatalogState(
    val entries: List<SdkCatalogEntry> = emptyList(),
    val installedEntryIds: Set<String> = emptySet(),
    val updatableEntryIds: Set<String> = emptySet(),
    val checking: Boolean = false,
    val runningEntryId: String? = null,
    val runningAction: SdkCatalogAction? = null,
    val executionLabel: String? = null,
    val logLines: List<String> = emptyList(),
    val selectedDistroId: String? = null,
    val errorMessage: String? = null,
    /** Installable versions per entry id, newest first (index 0 is "latest"), each with an optional tag.
     *  Populated lazily when a detail page opens for an entry whose [SdkCatalogEntry.versionsScript] is set. */
    val availableVersions: Map<String, List<CatalogVersion>> = emptyMap(),
    /** Currently-installed versions per entry id, newest first. */
    val installedVersions: Map<String, List<String>> = emptyMap(),
    /** The version on PATH per entry id, when its listing script reports one. Absent = the newest
     *  installed is active, which is what every entry did before switching existed. */
    val activeVersions: Map<String, String> = emptyMap(),
    /** Entry id whose version list is currently being fetched (drives the picker spinner). */
    val versionsLoadingEntryId: String? = null,
)
