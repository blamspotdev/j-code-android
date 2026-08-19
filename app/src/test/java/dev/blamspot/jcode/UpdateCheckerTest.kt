package dev.blamspot.jcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Semantic Versioning precedence, which is what decides whether a user is offered an update.
 *
 * The cases that motivated these: the previous comparator split a version on `.`, `-` and `+` and
 * read every non-numeric part as 0, so `1.4.10-beta` compared **equal** to `1.4.10` — a beta tester
 * was never told the version they were testing had shipped — and `rc.1` sorted *below* `beta.2`,
 * because all that survived the parse was the number beside the label.
 */
class UpdateCheckerTest {

    @Test
    fun `a release outranks its own pre-releases`() {
        assertTrue(UpdateChecker.isNewer("1.4.10", "1.4.10-beta"))
        assertTrue(UpdateChecker.isNewer("1.5.0", "1.5.0-rc.1"))
        assertFalse(UpdateChecker.isNewer("1.5.0-rc.1", "1.5.0"))
    }

    @Test
    fun `pre-release tiers order the way SemVer says`() {
        // alpha < beta < rc, and numbered steps within a tier.
        assertTrue(UpdateChecker.isNewer("1.5.0-beta.1", "1.5.0-alpha.9"))
        assertTrue(UpdateChecker.isNewer("1.5.0-beta.2", "1.5.0-beta.1"))
        assertTrue(UpdateChecker.isNewer("1.5.0-rc.1", "1.5.0-beta.2"))
        assertFalse(UpdateChecker.isNewer("1.5.0-beta.2", "1.5.0-rc.1"))
    }

    @Test
    fun `numeric identifiers compare numerically, not as text`() {
        assertTrue(UpdateChecker.isNewer("1.5.0-beta.10", "1.5.0-beta.9"))
    }

    @Test
    fun `more identifiers outrank fewer when the prefix is equal`() {
        assertTrue(UpdateChecker.isNewer("1.5.0-beta.1", "1.5.0-beta"))
        assertFalse(UpdateChecker.isNewer("1.5.0-beta", "1.5.0-beta.1"))
    }

    @Test
    fun `the numeric core still wins over any label`() {
        assertTrue(UpdateChecker.isNewer("1.5.0-alpha.1", "1.4.10"))
        assertTrue(UpdateChecker.isNewer("1.4.11", "1.4.10-rc.9"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `a v prefix and build metadata are not part of the comparison`() {
        assertEquals(0, UpdateChecker.compare("v1.5.0", "1.5.0"))
        assertEquals(0, UpdateChecker.compare("1.5.0+abc123", "1.5.0"))
        assertTrue(UpdateChecker.isNewer("v1.5.0", "v1.4.10"))
    }

    @Test
    fun `the same version is never an update`() {
        assertFalse(UpdateChecker.isNewer("1.4.10", "1.4.10"))
        assertFalse(UpdateChecker.isNewer("1.5.0-beta.1", "1.5.0-beta.1"))
        assertFalse(UpdateChecker.isNewer("1.4.9", "1.4.10"))
    }
}
