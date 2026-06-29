package dev.jcode.core.distro

import android.content.Context
import java.io.InputStreamReader
import java.util.LinkedHashMap
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

internal class SdkCatalogLoader(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val loader = Load(
        LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .build(),
    )

    fun load(): List<SdkCatalogEntry> {
        val document = appContext.assets.open(CATALOG_ASSET_PATH).use { stream ->
            InputStreamReader(stream).use { reader ->
                val loaded = loader.loadFromReader(reader) ?: emptyMap<String, Any?>()
                require(loaded is Map<*, *>) { "SDK catalog must start with a YAML mapping." }
                loaded.toStringKeyMap()
            }
        }

        return document.list("entries").mapIndexedNotNull { index, rawEntry ->
            val entry = (rawEntry as? Map<*, *>)?.toStringKeyMap() ?: return@mapIndexedNotNull null
            val id = entry.string("id") ?: return@mapIndexedNotNull null
            val category = SdkCatalogCategory.fromSerialized(entry.string("category"))
                ?: error("Entry '$id' has an unknown category at index $index.")
            val name = entry.string("name") ?: error("Entry '$id' is missing a name.")
            val description = entry.string("description") ?: ""
            val installScript = entry.string("installScript") ?: error("Entry '$id' is missing installScript.")
            val verifyScript = entry.string("verifyScript") ?: error("Entry '$id' is missing verifyScript.")
            val uninstallScript = entry.string("uninstallScript") ?: error("Entry '$id' is missing uninstallScript.")
            val updateCheckScript = entry.string("updateCheckScript")?.trim().orEmpty()
            SdkCatalogEntry(
                id = id,
                category = category,
                name = name,
                description = description,
                installScript = installScript.trim(),
                verifyScript = verifyScript.trim(),
                uninstallScript = uninstallScript.trim(),
                updateCheckScript = updateCheckScript,
            )
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
        private const val CATALOG_ASSET_PATH = "distro/catalog.yaml"
    }
}
