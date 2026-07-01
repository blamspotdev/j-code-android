package dev.jcode.feature.marketplace

/**
 * Project templates aggregated from every installed extension that declares any
 * (downloaded under the app's install root by [ExtensionInstaller]) — a language/dev
 * pack may bundle templates too. Templates are not bundled with the app; they are
 * installed at runtime from the marketplace.
 */
class TemplateCatalog internal constructor(
    private val installer: ExtensionInstaller,
) {
    fun templates(): List<ProjectTemplate> =
        installer.installed().flatMap { it.templates }

    fun template(id: String): ProjectTemplate? = templates().firstOrNull { it.id == id }
}
