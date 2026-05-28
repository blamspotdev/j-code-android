package dev.jcode.core.distro

internal object TermuxRunCommandContract {
    const val TermuxPackageName: String = "com.termux"
    const val TermuxApiPackageName: String = "com.termux.api"
    const val RunCommandPermission: String = "com.termux.permission.RUN_COMMAND"

    const val RunCommandServiceClass: String = "com.termux.app.RunCommandService"
    const val RunCommandAction: String = "com.termux.RUN_COMMAND"

    const val ExtraCommandPath: String = "com.termux.RUN_COMMAND_PATH"
    const val ExtraArguments: String = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val ExtraStdin: String = "com.termux.RUN_COMMAND_STDIN"
    const val ExtraWorkdir: String = "com.termux.RUN_COMMAND_WORKDIR"
    const val ExtraBackground: String = "com.termux.RUN_COMMAND_BACKGROUND"
    const val ExtraCommandLabel: String = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val ExtraCommandDescription: String = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    const val ExtraPendingIntent: String = "com.termux.RUN_COMMAND_PENDING_INTENT"

    const val ExtraPluginResultBundle: String = "result"
    const val ExtraPluginStdout: String = "stdout"
    const val ExtraPluginStdoutOriginalLength: String = "stdout_original_length"
    const val ExtraPluginStderr: String = "stderr"
    const val ExtraPluginStderrOriginalLength: String = "stderr_original_length"
    const val ExtraPluginExitCode: String = "exitCode"
    const val ExtraPluginErr: String = "err"
    const val ExtraPluginErrMsg: String = "errmsg"

    const val PrefixPath: String = "/data/data/com.termux/files/usr"
    const val PrefixBinPath: String = "$PrefixPath/bin"
    const val TermuxHomePath: String = "/data/data/com.termux/files/home"

    const val BashPath: String = "$PrefixBinPath/bash"
    const val ProotDistroPath: String = "$PrefixBinPath/proot-distro"

    const val PendingExecutionId: String = "dev.jcode.core.distro.EXECUTION_ID"
}
