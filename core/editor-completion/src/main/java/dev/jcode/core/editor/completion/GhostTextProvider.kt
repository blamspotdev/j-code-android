package dev.jcode.core.editor.completion

import dev.jcode.core.editor.decor.GhostTextDecoration
import dev.jcode.core.editor.decor.Layer

/**
 * Provider for AI ghost text (inline completions).
 * Registered into the decoration system as a transient InlineDecoration at the caret.
 * Tab accepts, Esc rejects.
 */
class GhostTextProvider {

    private var currentGhost: GhostTextDecoration? = null
    private var pendingText: String? = null

    /**
     * Propose ghost text at the given offset.
     */
    fun propose(offset: Int, text: String, color: Int = 0x80CDD6F4.toInt()): GhostTextDecoration {
        pendingText = text
        val ghost = GhostTextDecoration(
            offset = offset,
            text = text,
            color = color,
            alpha = 0.5f,
        )
        currentGhost = ghost
        return ghost
    }

    /**
     * Accept the current ghost text, returning the text to insert.
     */
    fun accept(): String? {
        val text = pendingText
        clear()
        return text
    }

    /**
     * Reject the current ghost text.
     */
    fun reject() {
        clear()
    }

    /**
     * Clear any active ghost text.
     */
    fun clear() {
        currentGhost = null
        pendingText = null
    }

    /**
     * Check if there's an active ghost text.
     */
    val hasActive: Boolean
        get() = currentGhost != null

    /**
     * Get the current ghost text decoration.
     */
    val active: GhostTextDecoration?
        get() = currentGhost
}
