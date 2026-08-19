package dev.jcode.feature.marketplace

import dev.jcode.core.distro.DistroService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live state of an on-device template scaffold (mirrors core/distro's SdkCatalogState shape). */
data class ScaffoldState(
    val templateId: String? = null,
    val projectName: String? = null,
    val running: Boolean = false,
    val totalSteps: Int = 0,
    /** 1-based index of the running/last step. */
    val currentStep: Int = 0,
    val currentLabel: String? = null,
    val logLines: List<String> = emptyList(),
    val errorMessage: String? = null,
    val finished: Boolean = false,
    val succeeded: Boolean = false,
)

/**
 * Runs a [ProjectTemplate] recipe inside the embedded runtime. Front-end package managers run in
 * the runtime's ext4 home (`{{hostStaging}}`) because FUSE `/workspace` has no symlinks; only the
 * build output / editable source lands in `{{projectDir}}`.
 *
 * The caller (app layer) is responsible for holding a foreground session for the duration so
 * Android does not kill the long-running `npm install` / `ng build` steps.
 */
class TemplateScaffolder internal constructor(
    private val distroService: DistroService,
) {
    private val _state = MutableStateFlow(ScaffoldState())
    val state: StateFlow<ScaffoldState> = _state.asStateFlow()

    /**
     * App-provided runner that executes a recipe step inside the shared Setup terminal (visible in
     * the right drawer) instead of a silent in-process exec. Returns null to decline (e.g. no
     * session slot free), in which case the step falls back to [DistroService.exec]. Output then
     * lives in the terminal, so [ScaffoldState.logLines] only carries step markers and errors.
     */
    @Volatile
    var interactiveExec: (suspend (label: String, command: String, workdir: String, timeoutMs: Long) -> dev.jcode.core.distro.ExecResult?)? = null

    private suspend fun execStep(label: String, command: String, workdir: String): dev.jcode.core.distro.ExecResult {
        interactiveExec?.invoke(label, command, workdir, STEP_TIMEOUT_MS)?.let { return it }
        return distroService.exec(
            command = command,
            workdir = workdir,
            timeoutMs = STEP_TIMEOUT_MS,
            onLine = ::appendLog,
        )
    }

    data class Request(
        val template: ProjectTemplate,
        /** Sanitized project name (also the staging-dir name). */
        val projectName: String,
        /** Project root inside the runtime, e.g. `/workspace/<name>`. */
        val projectDir: String,
        /** User-picked template inputs, keyed by input id; each fills `{{id}}` in the recipe. */
        val inputs: Map<String, String> = emptyMap(),
    )

    fun reset() {
        _state.value = ScaffoldState()
    }

    /** Run [request]'s recipe step by step. Returns true only if every step succeeded. */
    suspend fun scaffold(request: Request): Boolean {
        val template = request.template
        if (!distroService.isRuntimeReady()) {
            _state.value = ScaffoldState(
                templateId = template.id,
                projectName = request.projectName,
                finished = true,
                succeeded = false,
                errorMessage = "The Linux runtime isn't ready yet. Finish environment setup first.",
            )
            return false
        }

        val staging = "\$HOME/.jcode-staging/${request.projectName}"
        // Each declared input becomes `{{id}}`, taking the user's pick or the template default.
        val inputTokens = template.inputs.associate { input ->
            "{{${input.id}}}" to (request.inputs[input.id] ?: input.defaultValue)
        }
        val replacements = inputTokens + mapOf(
            "{{name}}" to request.projectName,
            "{{projectDir}}" to request.projectDir,
            "{{hostStaging}}" to staging,
        )

        val steps = template.recipe
        _state.value = ScaffoldState(
            templateId = template.id,
            projectName = request.projectName,
            running = true,
            totalSteps = steps.size,
            currentStep = 0,
            currentLabel = if (steps.isEmpty()) "Creating folder" else null,
            logLines = listOf("== ${template.name} → ${request.projectDir} =="),
        )

        if (steps.isEmpty()) {
            _state.value = _state.value.copy(running = false, finished = true, succeeded = true)
            return true
        }

        val prep = "rm -rf \"$staging\" && mkdir -p \"$staging\" && mkdir -p \"${request.projectDir}\""
        val prepResult = execStep("Prepare ${request.projectName}", prep, request.projectDir)
        if (!prepResult.succeeded) {
            return fail(prepResult.internalError ?: "Failed to prepare staging directory.")
        }

        steps.forEachIndexed { index, step ->
            val resolvedRun = step.run.resolve(replacements)
            val stepWorkdir = step.workdir?.resolve(replacements)
            // proot's working-directory flag is a literal path and does not shell-expand `$HOME`,
            // so embed the `cd` in the command (where the shell expands it) and keep proot's cwd at
            // the real project dir. Otherwise node's getcwd() fails with ENOENT.
            val command = if (stepWorkdir != null) "cd \"$stepWorkdir\" && $resolvedRun" else resolvedRun
            _state.value = _state.value.copy(
                currentStep = index + 1,
                currentLabel = step.label,
                logLines = (_state.value.logLines + "== ${step.label} ==").takeLast(LOG_LIMIT),
            )
            val result = execStep(step.label, command, request.projectDir)
            if (!result.succeeded) {
                val reason = result.internalError
                    ?: result.stderr.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: result.stdout.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: "${step.label} failed (exit ${result.exitCode ?: "?"})."
                return fail(reason)
            }
        }

        // Best-effort cleanup of the ext4 staging copy; failure here does not fail the scaffold.
        execStep("Clean up", "rm -rf \"$staging\"", request.projectDir)

        _state.value = _state.value.copy(
            running = false,
            finished = true,
            succeeded = true,
            currentLabel = "Done",
        )
        return true
    }

    private fun fail(message: String): Boolean {
        _state.value = _state.value.copy(
            running = false,
            finished = true,
            succeeded = false,
            errorMessage = message,
            logLines = (_state.value.logLines + "[error] $message").takeLast(LOG_LIMIT),
        )
        return false
    }

    private fun appendLog(line: String) {
        _state.value = _state.value.copy(
            logLines = (_state.value.logLines + line).takeLast(LOG_LIMIT),
        )
    }

    private fun String.resolve(replacements: Map<String, String>): String {
        var result = this
        for ((token, value) in replacements) {
            result = result.replace(token, value)
        }
        return result
    }

    private companion object {
        private const val STEP_TIMEOUT_MS = 1_800_000L
        private const val LOG_LIMIT = 400
    }
}
