package dev.jcode.feature.marketplace

import android.content.Context

/** Reads the bundled template extension once and caches it for the app session. */
class TemplateCatalog internal constructor(
    context: Context,
) {
    private val loader = TemplateCatalogLoader(context)

    @Volatile
    private var cached: TemplateExtension? = null

    fun extension(): TemplateExtension {
        return cached ?: synchronized(this) {
            cached ?: loader.load().also { cached = it }
        }
    }

    fun templates(): List<ProjectTemplate> = extension().templates

    fun template(id: String): ProjectTemplate? = templates().firstOrNull { it.id == id }
}
