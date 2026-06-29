package dev.jcode.feature.marketplace

/**
 * Project templates aggregated from all installed `type: templates` extensions
 * (downloaded under the app's install root by [ExtensionInstaller]). Templates are
 * no longer bundled with the app — they are installed at runtime from the marketplace.
 */
class TemplateCatalog internal constructor(
    private val installer: ExtensionInstaller,
) {
    fun templates(): List<ProjectTemplate> =
        installer.installed()
            .filter { it.type == ExtensionType.Templates }
            .flatMap { it.templates }

    fun template(id: String): ProjectTemplate? = templates().firstOrNull { it.id == id }
}
