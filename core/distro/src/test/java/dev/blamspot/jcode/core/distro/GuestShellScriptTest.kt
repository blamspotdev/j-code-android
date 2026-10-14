package dev.blamspot.jcode.core.distro

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A non-root command enters through `su -`, which starts the user from an empty environment. Until
 * every exported name was listed for `-w`, the default user's language servers and commands ran with
 * no LANG, TMPDIR or process-tree tag — and nothing failed, the variables were simply absent.
 */
class GuestShellScriptTest {

    @Test
    fun aUserGetsEveryExportedVariableCarriedThroughSu() {
        val script = ProotManager.guestShellScript("echo hi", mapOf("JCODE_PROC_ID" to "ext:1"), "jcode")
        assertTrue(
            script.endsWith("exec su -w 'COLORTERM,JCODE,JCODE_PROC_ID,LANG,TMPDIR' - 'jcode' -c 'echo hi'"),
            script,
        )
    }

    /** su sets these itself and ignores them in -w; PATH in particular comes from the account's login. */
    @Test
    fun whatSuSetsItselfIsNotListed() {
        val script = ProotManager.guestShellScript(
            "true",
            mapOf("PATH" to "/opt/x:/usr/bin", "TERM" to "dumb"),
            "jcode",
        )
        val carried = script.substringAfter("su -w '").substringBefore("'").split(',')
        assertFalse(carried.any { it in setOf("TERM", "HOME", "USER", "PATH") }, carried.toString())
        assertTrue(script.contains("export TERM='dumb'"), "the caller's value still wins the export")
    }

    @Test
    fun rootRunsTheCommandDirectly() {
        val script = ProotManager.guestShellScript("echo 'hi'", emptyMap(), "root")
        assertTrue(script.contains("export LANG='C.UTF-8'"), script)
        assertTrue(script.endsWith("exec /bin/sh -c 'echo '\\''hi'\\'''"), script)
        assertFalse(script.contains(" su "), script)
    }
}
