package dev.jcode.core.distro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

class TermuxRunResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val executionId = intent.getIntExtra(TermuxRunCommandContract.PendingExecutionId, -1)
        val bundle = intent.getBundleExtra(TermuxRunCommandContract.ExtraPluginResultBundle)
        if (executionId < 0 || bundle == null) return
        TermuxResultRegistry.complete(executionId, bundle)
    }
}

internal object TermuxResultRegistry {
    private val pending = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.CompletableDeferred<Bundle>>()

    fun register(executionId: Int): kotlinx.coroutines.CompletableDeferred<Bundle> {
        return kotlinx.coroutines.CompletableDeferred<Bundle>().also { pending[executionId] = it }
    }

    fun complete(
        executionId: Int,
        payload: Bundle,
    ) {
        pending.remove(executionId)?.complete(payload)
    }

    fun cancel(executionId: Int) {
        pending.remove(executionId)?.cancel()
    }
}
