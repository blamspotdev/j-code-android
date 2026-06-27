package dev.jcode.feature.marketplace

import android.content.Context
import java.io.InputStreamReader
import java.util.LinkedHashMap
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Loads the bundled template extension from assets (mirrors core/distro's SdkCatalogLoader).
 * `extension.yaml` lists the template ids; each `templates/<id>/template.yaml` describes one
 * template. Bundled via the module's external assets srcDir (`../../marketplace`).
 */
internal class TemplateCatalogLoader(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val loader = Load(
        LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .build(),
    )

    fun load(): TemplateExtension {
        val document = readMapping(EXTENSION_ASSET_PATH)
        val templateIds = document.list("templates").mapNotNull { raw ->
            raw?.toString()?.takeIf { it.isNotBlank() }
        }
        val templates = templateIds.mapNotNull { loadTemplate(it) }
        return TemplateExtension(
            id = document.string("id") ?: "jcode.templates",
            name = document.string("name") ?: "Templates",
            publisher = document.string("publisher"),
            version = document.string("version"),
            description = document.string("description") ?: "",
            templates = templates,
        )
    }

    private fun loadTemplate(id: String): ProjectTemplate? {
        val document = runCatching { readMapping("templates/$id/template.yaml") }.getOrNull() ?: return null
        val recipe = document.list("recipe").mapNotNull { raw ->
            val step = (raw as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
            val run = step.string("run") ?: return@mapNotNull null
            TemplateRecipeStep(
                label = step.string("label") ?: "Run",
                run = run.trim(),
                workdir = step.string("workdir"),
            )
        }
        return ProjectTemplate(
            id = document.string("id") ?: id,
            name = document.string("name") ?: id,
            description = document.string("description") ?: "",
            requires = document.list("requires").mapNotNull { raw ->
                raw?.toString()?.takeIf { it.isNotBlank() }
            },
            recipe = recipe,
        )
    }

    private fun readMapping(path: String): Map<String, Any?> {
        return appContext.assets.open(path).use { stream ->
            InputStreamReader(stream).use { reader ->
                val loaded = loader.loadFromReader(reader) ?: emptyMap<String, Any?>()
                require(loaded is Map<*, *>) { "$path must start with a YAML mapping." }
                loaded.toStringKeyMap()
            }
        }
    }

    private fun Map<*, *>.toStringKeyMap(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in this) {
            result[key?.toString() ?: continue] = value
        }
        return result
    }

    private fun Map<String, Any?>.string(key: String): String? {
        return when (val value = this[key]) {
            null -> null
            is String -> value.takeIf { it.isNotBlank() }
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun Map<String, Any?>.list(key: String): List<Any?> {
        return this[key] as? List<Any?> ?: emptyList()
    }

    private companion object {
        private const val EXTENSION_ASSET_PATH = "extension.yaml"
    }
}
