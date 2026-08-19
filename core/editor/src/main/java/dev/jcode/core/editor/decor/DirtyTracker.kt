package dev.jcode.core.editor.decor

import android.graphics.Rect

/**
 * Tracks dirty regions per layer for efficient incremental rendering.
 */
class DirtyTracker {

    private val dirtyRegions = mutableMapOf<Int, MutableList<Rect>>()
    private var fullRedrawNeeded = false

    /** Mark a region as dirty at a specific layer. */
    fun markDirty(layer: Int, rect: Rect) {
        dirtyRegions.getOrPut(layer) { mutableListOf() }.add(rect)
    }

    /** Mark entire viewport as needing redraw. */
    fun markFullRedraw() {
        fullRedrawNeeded = true
    }

    /** Mark a line range as dirty across text-affecting layers. */
    fun markLinesDirty(startLine: Int, endLine: Int, lineHeightPx: Int, widthPx: Int, heightPx: Int, gutterWidthPx: Int) {
        val top = startLine * lineHeightPx
        val bottom = ((endLine + 1) * lineHeightPx).coerceAtMost(heightPx)
        val rect = Rect(0, top, widthPx, bottom)

        markDirty(Layer.BACKGROUND, rect)
        markDirty(Layer.SQUIGGLY, rect)
        markDirty(Layer.GLYPH_COLOR, rect)
        markDirty(Layer.GLYPH, rect)
        markDirty(Layer.GUTTER, Rect(0, top, gutterWidthPx, bottom))
    }

    /** Get all dirty rects merged into a single union rect. Returns null for full redraw. */
    fun getUnionDirtyRect(): Rect? {
        if (fullRedrawNeeded) return null
        if (dirtyRegions.isEmpty()) return null

        val union = Rect()
        var hasAny = false
        for ((_, rects) in dirtyRegions) {
            for (rect in rects) {
                if (!hasAny) {
                    union.set(rect)
                    hasAny = true
                } else {
                    union.union(rect)
                }
            }
        }
        return if (hasAny) union else null
    }

    /** Check if a specific layer has dirty regions. */
    fun isLayerDirty(layer: Int): Boolean {
        return fullRedrawNeeded || dirtyRegions.containsKey(layer)
    }

    /** Check if a full redraw is needed. */
    fun needsFullRedraw(): Boolean = fullRedrawNeeded

    /** Clear all dirty regions after rendering. */
    fun clear() {
        dirtyRegions.clear()
        fullRedrawNeeded = false
    }
}
