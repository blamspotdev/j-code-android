package dev.blamspot.jcode.backend

import android.content.Context
import dev.blamspot.jcode.BackendService
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackendSessionKind {
    TERMINAL,
    LANGUAGE_SERVER,
    DEBUG_ADAPTER,
    JOB,
}

data class BackendSessionRecord(
    val id: String,
    val kind: BackendSessionKind,
    /** Stable identifier for logs, e.g. `sdk:install:node`. Not shown to anyone. */
    val name: String? = null,
    /** What to call this in the notification. Falls back to [displayLabel]'s reading of [name]. */
    val label: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    /**
     * The line a person should see for this session.
     *
     * Job names are structured (`<area>:<action>:<id>`) because they were written for logs, so they
     * are turned into something readable here rather than at fourteen call sites. A caller that can
     * say it better passes [label] and this is not consulted.
     */
    fun displayLabel(): String {
        label?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = name?.trim().orEmpty()
        if (raw.isEmpty()) return kindLabel()
        val parts = raw.split(':')
        if (parts.size < 2) return if (raw.equals("terminal", ignoreCase = true)) kindLabel() else raw
        val subject = parts.drop(2).joinToString(":").ifBlank { parts.getOrNull(1).orEmpty() }
        return when {
            parts[1] == "install" -> "Installing $subject"
            parts[1] == "uninstall" || parts[1] == "remove" -> "Removing $subject"
            parts[0] == "environment" && parts[1] == "create" -> "Creating $subject"
            parts[0] == "environment" -> "Setting up the environment"
            else -> raw
        }
    }

    private fun kindLabel(): String = when (kind) {
        BackendSessionKind.TERMINAL -> "Terminal"
        BackendSessionKind.LANGUAGE_SERVER -> "Language server"
        BackendSessionKind.DEBUG_ADAPTER -> "Debugger"
        BackendSessionKind.JOB -> "Background job"
    }
}

/** A long-running catalog task reporting itself through OSC 7716, if one is running. */
data class BackendTask(val label: String, val percent: Int?)

data class SessionRegistryState(
    val sessions: List<BackendSessionRecord> = emptyList(),
    val task: BackendTask? = null,
) {
    val activeCount: Int
        get() = sessions.size

    val isEmpty: Boolean
        get() = sessions.isEmpty()

    val terminals: List<BackendSessionRecord>
        get() = sessions.filter { it.kind == BackendSessionKind.TERMINAL }
}

class BackendSessionHandle internal constructor(
    private val appContext: Context,
    val sessionId: String,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            SessionRegistry.unregisterSession(appContext, sessionId)
        }
    }
}

/**
 * Tracks interactive backend work that must keep the foreground service alive.
 * Future batch-only work should use WorkManager instead of registering here.
 */
object SessionRegistry {
    private enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
    }

    private val lock = Any()
    private val _state = MutableStateFlow(SessionRegistryState())
    private var serviceState = ServiceState.STOPPED

    val state: StateFlow<SessionRegistryState> = _state.asStateFlow()

    fun registerSession(
        context: Context,
        kind: BackendSessionKind,
        name: String? = null,
        label: String? = null,
    ): BackendSessionHandle {
        val appContext = context.applicationContext
        val record = BackendSessionRecord(
            id = UUID.randomUUID().toString(),
            kind = kind,
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            label = label?.trim()?.takeIf { it.isNotEmpty() },
        )

        val shouldStartService = synchronized(lock) {
            _state.value = _state.value.copy(sessions = _state.value.sessions + record)
            if (serviceState == ServiceState.STOPPED) {
                serviceState = ServiceState.STARTING
                true
            } else {
                false
            }
        }

        if (shouldStartService) {
            try {
                BackendService.start(appContext)
            } catch (error: RuntimeException) {
                synchronized(lock) {
                    serviceState = ServiceState.STOPPED
                    _state.value = SessionRegistryState(
                        _state.value.sessions.filterNot { it.id == record.id },
                    )
                }
                throw error
            }
        }

        return BackendSessionHandle(
            appContext = appContext,
            sessionId = record.id,
        )
    }

    fun unregisterSession(context: Context, sessionId: String): Boolean {
        val appContext = context.applicationContext
        var removed = false
        val shouldStopService = synchronized(lock) {
            val updatedSessions = _state.value.sessions.filterNot { session ->
                val matches = session.id == sessionId
                removed = removed || matches
                matches
            }
            if (removed) {
                _state.value = _state.value.copy(sessions = updatedSessions)
            }
            removed && updatedSessions.isEmpty() && serviceState != ServiceState.STOPPED
        }

        if (shouldStopService) {
            BackendService.stop(appContext)
        }

        return removed
    }

    /** Rename a live session — a terminal reporting the program it is running, say. */
    fun relabelSession(sessionId: String, label: String?) {
        val clean = label?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            val sessions = _state.value.sessions
            val index = sessions.indexOfFirst { it.id == sessionId }
            if (index < 0 || sessions[index].label == clean) return
            _state.value = _state.value.copy(
                sessions = sessions.toMutableList().apply { this[index] = this[index].copy(label = clean) },
            )
        }
    }

    /** The catalog task now running, with its percentage when it has reported one. */
    fun setTask(label: String, percent: Int? = null) {
        val clean = label.trim().ifEmpty { return }
        synchronized(lock) {
            val task = BackendTask(clean, percent?.coerceIn(0, 100))
            if (_state.value.task != task) _state.value = _state.value.copy(task = task)
        }
    }

    fun clearTask() {
        synchronized(lock) {
            if (_state.value.task != null) _state.value = _state.value.copy(task = null)
        }
    }

    internal fun onServiceCreated() {
        synchronized(lock) {
            serviceState = ServiceState.RUNNING
        }
    }

    internal fun onServiceDestroyed() {
        synchronized(lock) {
            serviceState = ServiceState.STOPPED
        }
    }
}
