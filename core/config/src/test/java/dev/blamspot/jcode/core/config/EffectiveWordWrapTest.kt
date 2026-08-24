package dev.blamspot.jcode.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Global editor toggles must sit *beneath* the `.jcode` scopes in the merge, not beside them.
 *
 * `wordWrap` bottomed out at a literal `false` until 1.6.2, so [EffectiveEditorConfig.wordWrap] was
 * unreadable — using it would have forced wrap off for everyone who had the Global toggle on and no
 * `.jcode` key. Open editors were fed the Global setting *around* the config instead, which left a
 * `wordWrap` override at workspace or project scope parsed, merged, written back, and never applied.
 *
 * Re-hardcoding that fallback is a silent regression: nothing fails to compile and the Global toggle
 * still appears to work, because the value reaching the editor would come from the other path.
 */
class EffectiveWordWrapTest {

    @Test
    fun globalWordWrapIsTheBaseOfTheMerge() {
        val service = ConfigService()
        assertEquals(false, service.effectiveConfig.value.editor.wordWrap, "default is off")

        service.setGlobalEditorWordWrap(true)
        assertEquals(
            true,
            service.effectiveConfig.value.editor.wordWrap,
            "Global ON with no .jcode override must reach the effective config",
        )

        service.setGlobalEditorWordWrap(false)
        assertEquals(false, service.effectiveConfig.value.editor.wordWrap, "and back off again")
    }

    /** The same shape for font size, which has had the layer since before 1.6.2 — kept beside it so
     *  the two stay symmetrical rather than drifting apart again. */
    @Test
    fun globalFontSizeIsTheBaseOfTheMerge() {
        val service = ConfigService()
        service.setGlobalEditorFontSize(21f)
        assertEquals(21f, service.effectiveConfig.value.editor.fontSize)
    }
}
