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

    fun clear() {
        commands.clear()
        version++
    }
}
