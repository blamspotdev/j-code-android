package dev.blamspot.jcode.design

import org.junit.Assert.assertEquals
import org.junit.Test

class DescriptionTextTest {

    @Test
    fun `folds the soft breaks an editor left behind`() {
        val wrapped = """
            A compact, VS Code-style git source-control sidebar in the left drawer, over the git
            installed in the Linux runtime. Authored in TypeScript and built for production.
        """.trimIndent()
        assertEquals(
            "A compact, VS Code-style git source-control sidebar in the left drawer, over the git " +
                "installed in the Linux runtime. Authored in TypeScript and built for production.",
            reflowDescription(wrapped),
        )
    }

    @Test
    fun `keeps the breaks between paragraphs`() {
        val text = "First paragraph, wrapped\nacross two lines.\n\nSecond paragraph."
        assertEquals("First paragraph, wrapped across two lines.\n\nSecond paragraph.", reflowDescription(text))
    }

    /** A blank line carrying trailing spaces is still a paragraph break to a reader. */
    @Test
    fun `treats a whitespace-only line as a paragraph break`() {
        assertEquals("One.\n\nTwo.", reflowDescription("One.\n   \nTwo."))
    }

    @Test
    fun `leaves list items on their own lines`() {
        val text = "What it does:\n- stage and unstage\n- commit\n- push"
        assertEquals("What it does:\n- stage and unstage\n- commit\n- push", reflowDescription(text))
    }

    @Test
    fun `folds prose that follows a list back into a paragraph`() {
        val text = "- one\n- two\nTrailing prose that wrapped\nover two lines."
        assertEquals("- one\n- two\nTrailing prose that wrapped over two lines.", reflowDescription(text))
    }

    @Test
    fun `keeps headings, quotes and numbered items`() {
        val text = "# Title\nProse under it.\n> quoted\n1. first\n2. second"
        assertEquals("# Title\nProse under it.\n> quoted\n1. first\n2. second", reflowDescription(text))
    }

    @Test
    fun `keeps indented blocks verbatim`() {
        val text = "Run it:\n    git status\nthen read the output."
        assertEquals("Run it:\n    git status\nthen read the output.", reflowDescription(text))
    }

    @Test
    fun `handles CRLF, and text that needs no change`() {
        assertEquals("One line.", reflowDescription("One line."))
        assertEquals("A B", reflowDescription("A\r\nB"))
        assertEquals("", reflowDescription("   \n  \n"))
    }
}
