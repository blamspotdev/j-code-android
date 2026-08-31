package dev.blamspot.jcode.core.distro.adb

import kotlinx.coroutines.Dispatchers

/*
 * What answers adb, and what it is handed -- the contract, not the daemon.
 *
 * It lives here because two sides need it and neither owns the other: `ext-api` puts an
 * [AdbServiceHandler] on the virtual device interface, and the extension that provides the device
 * implements it -- along with the daemon that serves it, which is the pack's, because everything
 * the device is is the pack's.
 */

/** One open adb stream, seen from the service that is answering it. */
interface AdbStream {
    /** The service string the client asked for, e.g. `shell:getprop ro.build.version.sdk`. */
    val service: String

    suspend fun write(payload: ByteArray)

    suspend fun write(text: String)

    /** The next chunk the client wrote, or null once it has finished writing. */
    suspend fun read(): ByteArray?
}

/**
 * Answers one adb service. Called on [Dispatchers.IO]; the daemon closes the stream when this
 * returns, so a handler that writes nothing produces an empty, successfully-opened service.
 */
fun interface AdbServiceHandler {
    suspend fun handle(stream: AdbStream)
}

/** The one line every service this daemon does not implement answers with. */
fun unsupportedService(service: String): String = "(jcode virtual device) unsupported: $service\n"

/**
 * Splits an adb command line the way the shell that would normally run it would: on whitespace, but
 * honouring single and double quotes. `adb install` sends `cmd package 'install' -S <n>` with the
 * subcommand quoted, so a plain `split(' ')` looks for a subcommand literally named `'install'`.
 */
fun adbCommandArgs(command: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var quoted = false
    for (char in command) {
        when {
            quote != null && char == quote -> quote = null
            quote != null -> current.append(char)
            char == '\'' || char == '"' -> {
                quote = char
                quoted = true
            }

            char.isWhitespace() -> {
                if (current.isNotEmpty() || quoted) args += current.toString()
                current.setLength(0)
                quoted = false
            }

            else -> current.append(char)
        }
    }
    if (current.isNotEmpty() || quoted) args += current.toString()
    return args
}
