package dev.jcode.core.distro

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeoutOrNull

internal class TermuxOneShot(
    private val context: Context,
) {
    private val executionIds = AtomicInteger(1_000)

    suspend fun run(
        commandPath: String,
        arguments: List<String>,
        workdir: String = TermuxRunCommandContract.TermuxHomePath,
        stdin: String? = null,
        background: Boolean = true,
        label: String? = null,
        description: String? = null,
        timeoutMs: Long = 30_000L,
    ): TermuxRunResult {
        val executionId = executionIds.incrementAndGet()
        val deferred = TermuxResultRegistry.register(executionId)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            executionId,
            Intent(context, TermuxRunResultReceiver::class.java).putExtra(
                TermuxRunCommandContract.PendingExecutionId,
                executionId,
            ),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlag(),
        )

        val intent = Intent().apply {
            setClassName(
                TermuxRunCommandContract.TermuxPackageName,
                TermuxRunCommandContract.RunCommandServiceClass,
            )
            action = TermuxRunCommandContract.RunCommandAction
            putExtra(TermuxRunCommandContract.ExtraCommandPath, commandPath)
            putExtra(TermuxRunCommandContract.ExtraArguments, arguments.toTypedArray())
            putExtra(TermuxRunCommandContract.ExtraWorkdir, workdir)
            putExtra(TermuxRunCommandContract.ExtraBackground, background)
            putExtra(TermuxRunCommandContract.ExtraPendingIntent, pendingIntent)
            if (!stdin.isNullOrBlank()) {
                putExtra(TermuxRunCommandContract.ExtraStdin, stdin)
            }
            if (!label.isNullOrBlank()) {
                putExtra(TermuxRunCommandContract.ExtraCommandLabel, label)
            }
            if (!description.isNullOrBlank()) {
                putExtra(TermuxRunCommandContract.ExtraCommandDescription, description)
            }
        }

        val component = try {
            context.startService(intent)
        } catch (error: Exception) {
            TermuxResultRegistry.cancel(executionId)
            return TermuxRunResult(internalError = error.message ?: "Failed to start Termux service.")
        }

        if (component == null) {
            TermuxResultRegistry.cancel(executionId)
            return TermuxRunResult(internalError = "Termux refused to start the RUN_COMMAND service.")
        }

        val bundle = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (bundle == null) {
            TermuxResultRegistry.cancel(executionId)
            return TermuxRunResult(internalError = "Timed out waiting for Termux command result.")
        }

        val stdout = bundle.getString(TermuxRunCommandContract.ExtraPluginStdout).orEmpty()
        val stderr = bundle.getString(TermuxRunCommandContract.ExtraPluginStderr).orEmpty()
        val stdoutOriginalLength = bundle.getInt(
            TermuxRunCommandContract.ExtraPluginStdoutOriginalLength,
            stdout.length,
        )
        val stderrOriginalLength = bundle.getInt(
            TermuxRunCommandContract.ExtraPluginStderrOriginalLength,
            stderr.length,
        )
        val errCode = bundle.getInt(
            TermuxRunCommandContract.ExtraPluginErr,
            Activity.RESULT_OK,
        )
        val errMessage = bundle.getString(TermuxRunCommandContract.ExtraPluginErrMsg)

        return TermuxRunResult(
            stdout = stdout,
            stderr = stderr,
            exitCode = bundle.getInt(TermuxRunCommandContract.ExtraPluginExitCode, -1).takeIf { it >= 0 },
            truncated = stdoutOriginalLength > stdout.length || stderrOriginalLength > stderr.length,
            internalError = if (errCode == Activity.RESULT_OK) errMessage?.takeIf(String::isNotBlank) else {
                errMessage?.ifBlank { "Termux reported an internal command failure." }
                    ?: "Termux reported an internal command failure."
            },
        )
    }

    private fun pendingIntentMutabilityFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
    }
}
