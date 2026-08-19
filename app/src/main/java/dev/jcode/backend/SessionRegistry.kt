package dev.jcode.backend

import android.content.Context
import dev.jcode.BackendService
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
    val name: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

data class SessionRegistryState(
    val sessions: List<BackendSessionRecord> = emptyList(),
) {
    val activeCount: Int
        get() = sessions.size

    val isEmpty: Boolean
        get() = sessions.isEmpty()
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
    ): BackendSessionHandle {
        val appContext = context.applicationContext
        val record = BackendSessionRecord(
            id = UUID.randomUUID().toString(),
            kind = kind,
            name = name?.trim()?.takeIf { it.isNotEmpty() },
        )

        val shouldStartService = synchronized(lock) {
            _state.value = SessionRegistryState(_state.value.sessions + record)
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
                _state.value = SessionRegistryState(updatedSessions)
            }
            removed && updatedSessions.isEmpty() && serviceState != ServiceState.STOPPED
        }

        if (shouldStopService) {
            BackendService.stop(appContext)
        }

        return removed
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
