package dev.jcode.core.treesitter

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jcode.core.editor.LanguageDescriptor
import javax.inject.Singleton

/**
 * Registry of supported languages, their extensions, and tree-sitter grammars.
 * Acts as the single source of truth for language detection by file extension.
 */
class LanguageRegistry {

    private data class LanguageEntry(
        val descriptor: LanguageDescriptor,
        val tsLanguage: TsLanguage?,
        val queryResources: QueryResources,
    )

    data class QueryResources(
        val highlightsScm: String? = null,
        val localsScm: String? = null,
        val foldsScm: String? = null,
        val tagsScm: String? = null,
    )

    private val entries = mutableMapOf<String, LanguageEntry>()
    private val extensionMap = mutableMapOf<String, String>() // ext -> language id

    init {
        registerDefaultLanguages()
    }

    /** Find a language descriptor by file extension (without the dot). */
    fun findByExtension(ext: String): LanguageDescriptor? {
        val normalized = ext.lowercase().trimStart('.')
        val langId = extensionMap[normalized] ?: return null
        return entries[langId]?.descriptor
    }

    /** Find a language descriptor by language ID. */
    fun findById(id: String): LanguageDescriptor? = entries[id]?.descriptor

    /** Get all registered language descriptors. */
    fun allDescriptors(): List<LanguageDescriptor> = entries.values.map { it.descriptor }

    /** Get the tree-sitter language for a given extension. */
    fun findTsLanguage(ext: String): TsLanguage? {
        val normalized = ext.lowercase().trimStart('.')
        val langId = extensionMap[normalized] ?: return null
        return entries[langId]?.tsLanguage
    }

    /** Get query resources for a language. */
    fun findQueryResources(ext: String): QueryResources? {
        val normalized = ext.lowercase().trimStart('.')
        val langId = extensionMap[normalized] ?: return null
        return entries[langId]?.queryResources
    }

    /** Register a language with its extensions and resources. */
    fun register(
        id: String,
        name: String,
        extensions: List<String>,
        tsLanguage: TsLanguage? = null,
        queries: QueryResources = QueryResources(),
    ) {
        val descriptor = LanguageDescriptor(id, name, extensions)
        val entry = LanguageEntry(descriptor, tsLanguage, queries)
        entries[id] = entry
        for (ext in extensions) {
            extensionMap[ext.lowercase().trimStart('.')] = id
        }
    }

    private fun registerDefaultLanguages() {
        // Register all planned languages with their extensions
        val languages = listOf(
            "c" to listOf("c", "h"),
            "cpp" to listOf("cpp", "cc", "cxx", "hpp", "hxx", "c++", "h++"),
            "csharp" to listOf("cs"),
            "typescript" to listOf("ts", "tsx"),
            "javascript" to listOf("js", "jsx", "mjs", "cjs"),
            "json" to listOf("json", "jsonc"),
            "yaml" to listOf("yaml", "yml"),
            "kotlin" to listOf("kt", "kts"),
            "java" to listOf("java"),
            "python" to listOf("py", "pyw"),
            "rust" to listOf("rs"),
            "markdown" to listOf("md", "markdown"),
            "html" to listOf("html", "htm"),
            "css" to listOf("css"),
        )

        for ((id, extensions) in languages) {
            register(
                id = id,
                name = id.replaceFirstChar { it.uppercase() }.replace("csharp", "C#").replace("cpp", "C++"),
                extensions = extensions,
                tsLanguage = TsLanguage.create(id, extensions),
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LanguageRegistryModule {
    @Provides
    @Singleton
    fun provideLanguageRegistry(): LanguageRegistry = LanguageRegistry()
}
