package dev.jcode.core.config

import java.io.File
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle

/** One terminal of a build/run config: a tab label and the bash script run in it. */
data class RunConfigTerminal(
    val label: String,
    val command: String,
)

/** A per-project build/run configuration, persisted at `/{project}/.jcode/run.yaml`. */
data class RunConfig(
    /** Display name shown in the Run panel, e.g. "ASP.NET Core + Vite React (dev)". */
    val name: String,
    /** Localhost port to poll for readiness + open in the browser; 0 = no server/URL. */
    val readyPort: Int,
    val terminals: List<RunConfigTerminal>,
)

/** Reads/writes the per-project build/run config at `.jcode/run.yaml`. */
object RunConfigStore {
    private const val REL_PATH = ".jcode/run.yaml"

    fun configFile(projectDir: File): File = File(projectDir, REL_PATH)

    fun exists(projectDir: File): Boolean = configFile(projectDir).isFile

    fun load(projectDir: File): RunConfig? {
        val file = configFile(projectDir)
        if (!file.isFile) return null
        return runCatching {
            val loaded = Load(LoadSettings.builder().build()).loadFromReader(file.reader())
            val map = loaded as? Map<*, *> ?: return null
            val terminals = (map["terminals"] as? List<*>).orEmpty().mapNotNull { raw ->
                val t = (raw as? Map<*, *>) ?: return@mapNotNull null
                val label = t["label"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RunConfigTerminal(label = label, command = t["command"]?.toString().orEmpty())
            }
            RunConfig(
                name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: projectDir.name,
                readyPort = (map["readyPort"] as? Number)?.toInt()
                    ?: map["readyPort"]?.toString()?.toIntOrNull() ?: 0,
                terminals = terminals,
            )
        }.getOrNull()
    }

    fun save(projectDir: File, config: RunConfig) {
        val dir = File(projectDir, ".jcode").apply { if (!exists()) mkdirs() }
        val document = linkedMapOf<String, Any?>(
            "version" to 1,
            "name" to config.name,
            "readyPort" to config.readyPort,
            "terminals" to config.terminals.map { terminal ->
                linkedMapOf<String, Any?>("label" to terminal.label, "command" to terminal.command)
            },
        )
        val dump = Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).build())
        File(dir, "run.yaml").writeText(dump.dumpToString(document))
    }
}
