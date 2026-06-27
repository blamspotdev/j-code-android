package dev.jcode.core.distro

data class SdkCatalogEntry(
    val id: String,
    val category: SdkCatalogCategory,
    val name: String,
    val description: String,
    val installScript: String,
    val verifyScript: String,
    val uninstallScript: String,
)

enum class SdkCatalogCategory(val label: String) {
    Languages("Languages"),
    BuildTools("Build Tools"),
    Android("Android"),
    DotNet(".NET"),
    Embedded("Embedded"),
    Databases("Databases");

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
    val runningEntryId: String? = null,
    val runningAction: SdkCatalogAction? = null,
    val executionLabel: String? = null,
    val logLines: List<String> = emptyList(),
    val selectedDistroId: String? = null,
    val errorMessage: String? = null,
)
