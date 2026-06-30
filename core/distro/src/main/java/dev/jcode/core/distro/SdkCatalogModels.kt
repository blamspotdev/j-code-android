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
    /** Distro ids this entry supports. Empty = every distro. */
    val supportedDistros: List<String> = emptyList(),
    /** Arch keys (Arch.rootfsKey: "arm64"/"amd64") this entry supports. Empty = every arch. */
    val supportedArches: List<String> = emptyList(),
) {
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
    Virtualization("Virtualization");

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
                else -> null
            }
        }
    }
}

enum class SdkCatalogAction(val label: String) {
    Install("Install"),
    Verify("Verify"),
    Uninstall("Remove"),
}

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
)
