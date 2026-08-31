package dev.blamspot.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

@Immutable
data class CommandSpec(
    val id: String,
    val title: String,
    val group: String,
    val action: () -> Unit,
    val isEnabled: () -> Boolean = { true },
    val icon: JCodeIcon? = null,
)

object CommandRegistry {
    private val commands = linkedMapOf<String, CommandSpec>()

    /** Bumped on every mutation so a composed palette (which reads it as Compose state) recomposes
     *  when the shell re-registers commands — including the first population after a process-restore
     *  that reopened the palette from saved state. */
    var version by mutableIntStateOf(0)
        private set

    fun register(
        id: String,
        title: String,
        group: String,
        action: () -> Unit,
        whenPredicate: () -> Boolean = { true },
        icon: JCodeIcon? = null,
    ) {
        commands[id] = CommandSpec(
            id = id,
            title = title,
            group = group,
            action = action,
            isEnabled = whenPredicate,
            icon = icon,
        )
        version++
    }

    fun all(): List<CommandSpec> = commands.values.toList()

    /**
     * Runs a command by id, if there is one.
     *
     * For a caller that has a *name* rather than a lambda -- an extension whose manifest declares
     * a `type: action` setting, which says which command its button runs and nothing about what
     * that command does. Silently does nothing when the id is unknown: a manifest naming a
     * command this JCode does not have is a button that should do nothing, not a crash.
     */
    fun run(id: String): Boolean = commands[id]?.also { it.action() } != null

    fun clear() {
        commands.clear()
        version++
    }
}
