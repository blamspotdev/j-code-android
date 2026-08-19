package dev.jcode.core.config

import java.io.File
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle

/** One terminal of a run config: a tab label and the bash script run in it. */
data class RunConfigTerminal(
    val label: String,
    val command: String,
)

/**
 * One way to run a project. [terminals] run side by side (build + serve is baked into the bash);
 * [readyPort] > 0 is polled and opened in the browser. [debugEntry] is a guest path to the source
 * file the Debug action launches under the DAP debugger (blank = this config isn't debuggable).
 */
data class RunConfig(
    val name: String,
    val readyPort: Int,
    val debugEntry: String = "",
    val terminals: List<RunConfigTerminal>,
)

/** One way to build a project (e.g. `dotnet publish -c Release -o out`) — a name + a single bash command. */
data class BuildConfig(
    val name: String,
    val command: String,
)

/** Every build/run config for a project, persisted at `/{project}/.jcode/run.yaml`. */
data class ProjectConfigs(
    val runs: List<RunConfig>,
    val builds: List<BuildConfig>,
) {
    companion object {
        val EMPTY = ProjectConfigs(emptyList(), emptyList())
    }
}

/** Reads/writes the per-project build/run configs at `.jcode/run.yaml`. */
object RunConfigStore {
    private const val REL_PATH = ".jcode/run.yaml"

    fun configFile(projectDir: File): File = File(projectDir, REL_PATH)

    fun exists(projectDir: File): Boolean = configFile(projectDir).isFile

    fun load(projectDir: File): ProjectConfigs? {
        val file = configFile(projectDir)
        if (!file.isFile) return null
        return runCatching {
            val map = Load(LoadSettings.builder().build()).loadFromReader(file.reader()) as? Map<*, *> ?: return null
            // v2: `runs: [...]` + `builds: [...]`. Legacy v1 had a single config at the top level
            // (name/readyPort/terminals) — read that as a one-element runs list.
            val runsRaw = map["runs"] as? List<*>
            val runs = if (runsRaw != null) {
                runsRaw.mapNotNull { parseRun(it, projectDir.name) }
            } else {
                parseRun(map, projectDir.name)?.takeIf { it.terminals.isNotEmpty() }?.let { listOf(it) }.orEmpty()
            }
            val builds = (map["builds"] as? List<*>).orEmpty().mapNotNull { parseBuild(it) }
            ProjectConfigs(runs = runs, builds = builds)
        }.getOrNull()
    }

    private fun parseRun(raw: Any?, fallbackName: String): RunConfig? {
        val m = raw as? Map<*, *> ?: return null
        val terminals = (m["terminals"] as? List<*>).orEmpty().mapNotNull { t ->
            val tm = t as? Map<*, *> ?: return@mapNotNull null
            val label = tm["label"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RunConfigTerminal(label = label, command = tm["command"]?.toString().orEmpty())
        }
        return RunConfig(
            name = m["name"]?.toString()?.takeIf { it.isNotBlank() } ?: fallbackName,
            readyPort = (m["readyPort"] as? Number)?.toInt() ?: m["readyPort"]?.toString()?.toIntOrNull() ?: 0,
            debugEntry = m["debugEntry"]?.toString().orEmpty(),
            terminals = terminals,
        )
    }

    private fun parseBuild(raw: Any?): BuildConfig? {
        val m = raw as? Map<*, *> ?: return null
        val name = m["name"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return BuildConfig(name = name, command = m["command"]?.toString().orEmpty())
    }

    fun save(projectDir: File, configs: ProjectConfigs) {
        val dir = File(projectDir, ".jcode").apply { if (!exists()) mkdirs() }
        val document = linkedMapOf<String, Any?>(
            "version" to 2,
            "runs" to configs.runs.map { r ->
                linkedMapOf<String, Any?>(
                    "name" to r.name,
                    "readyPort" to r.readyPort,
                    "debugEntry" to r.debugEntry,
                    "terminals" to r.terminals.map { t ->
                        linkedMapOf<String, Any?>("label" to t.label, "command" to t.command)
                    },
                )
            },
            "builds" to configs.builds.map { b ->
                linkedMapOf<String, Any?>("name" to b.name, "command" to b.command)
            },
        )
        val dump = Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).build())
        File(dir, "run.yaml").writeText(dump.dumpToString(document))
    }
}
