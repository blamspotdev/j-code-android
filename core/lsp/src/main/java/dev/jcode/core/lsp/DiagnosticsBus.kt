package dev.jcode.core.lsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Central bus for collecting diagnostics from all sources (LSP, tree-sitter, YAML schema).
 * Consumers (editor decorations, problems pane, status bar) observe the aggregated state.
 */
class DiagnosticsBus {

    private val diagnosticsBySource = ConcurrentHashMap<String, Map<String, List<Diagnostic>>>()

    private val _allDiagnostics = MutableStateFlow<Map<String, List<Diagnostic>>>(emptyMap())
    val allDiagnostics: StateFlow<Map<String, List<Diagnostic>>> = _allDiagnostics.asStateFlow()

    private val _totalCount = MutableStateFlow(DiagnosticCount(0, 0, 0))
    val totalCount: StateFlow<DiagnosticCount> = _totalCount.asStateFlow()

    /**
     * Update diagnostics from a specific source for a specific file.
     */
    fun updateDiagnostics(source: String, fileUri: String, diagnostics: List<Diagnostic>) {
        val sourceMap = diagnosticsBySource.getOrPut(source) { ConcurrentHashMap() }
        (sourceMap as ConcurrentHashMap)[fileUri] = diagnostics
        recompute()
    }

    /**
     * Update all diagnostics from a specific source (e.g., from LSP session).
     */
    fun updateSourceDiagnostics(source: String, diagnostics: Map<String, List<Diagnostic>>) {
        diagnosticsBySource[source] = diagnostics
        recompute()
    }

    /**
     * Clear diagnostics from a specific source.
     */
    fun clearSource(source: String) {
        diagnosticsBySource.remove(source)
        recompute()
    }

    /**
     * Clear diagnostics for a specific file from all sources.
     */
    fun clearFile(fileUri: String) {
        diagnosticsBySource.values.forEach { sourceMap ->
            (sourceMap as? ConcurrentHashMap)?.remove(fileUri)
        }
        recompute()
    }

    /**
     * Get diagnostics for a specific file from all sources.
     */
    fun getDiagnosticsForFile(fileUri: String): List<Diagnostic> {
        return diagnosticsBySource.values
            .flatMap { sourceMap -> sourceMap[fileUri] ?: emptyList() }
            .sortedWith(compareBy({ it.severity.value }, { it.startLine }, { it.startCol }))
    }

    /**
     * Get total counts across all files and sources.
     */
    fun getCounts(): DiagnosticCount {
        var errors = 0
        var warnings = 0
        var infos = 0

        diagnosticsBySource.values.forEach { sourceMap ->
            sourceMap.values.forEach { diags ->
                diags.forEach { diag ->
                    when (diag.severity) {
                        DiagnosticSeverity.ERROR -> errors++
                        DiagnosticSeverity.WARNING -> warnings++
                        DiagnosticSeverity.INFORMATION, DiagnosticSeverity.HINT -> infos++
                    }
                }
            }
        }

        return DiagnosticCount(errors, warnings, infos)
    }

    private fun recompute() {
        // Merge all sources
        val merged = mutableMapOf<String, MutableList<Diagnostic>>()
        diagnosticsBySource.values.forEach { sourceMap ->
            sourceMap.forEach { (uri, diags) ->
                merged.getOrPut(uri) { mutableListOf() }.addAll(diags)
            }
        }

        // Sort each file's diagnostics
        merged.forEach { (_, diags) ->
            diags.sortWith(compareBy({ it.severity.value }, { it.startLine }, { it.startCol }))
        }

        _allDiagnostics.value = merged

        // Update counts
        var errors = 0
        var warnings = 0
        var infos = 0
        merged.values.forEach { diags ->
            diags.forEach { diag ->
                when (diag.severity) {
                    DiagnosticSeverity.ERROR -> errors++
                    DiagnosticSeverity.WARNING -> warnings++
                    DiagnosticSeverity.INFORMATION, DiagnosticSeverity.HINT -> infos++
                }
            }
        }
        _totalCount.value = DiagnosticCount(errors, warnings, infos)
    }
}

/**
 * Aggregate diagnostic counts.
 */
data class DiagnosticCount(
    val errors: Int,
    val warnings: Int,
    val infos: Int,
) {
    val total: Int get() = errors + warnings + infos
    val hasErrors: Boolean get() = errors > 0
    val hasWarnings: Boolean get() = warnings > 0
}
