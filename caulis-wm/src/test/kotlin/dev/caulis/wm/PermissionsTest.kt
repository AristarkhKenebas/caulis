package dev.caulis.wm

import dev.caulis.wm.config.ConfigFile
import dev.caulis.wm.config.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Permissions by program: the first rule decides, then the feature's default, then the privileged rule. */
class PermissionsTest {

    private val harness = CaulisTest()

    @Test
    fun `the first matching rule decides, by path or by name`() {
        val p = Permissions().apply {
            rule("/usr/bin/grim", "screencopy", "allow")
            rule("wf-.*", "screencopy", "deny")
            default("screencopy", "ask")
        }
        assertEquals(PermissionMode.ALLOW, p.modeOf("/usr/bin/grim", "grim", "screencopy"))
        assertEquals(PermissionMode.DENY, p.modeOf("/usr/bin/wf-recorder", "wf-recorder", "screencopy"))
        assertEquals(PermissionMode.ASK, p.modeOf("/usr/bin/obs", "obs", "screencopy"), "no rule: the default")
        assertNull(p.modeOf("/usr/bin/grim", "grim", "clipboard"), "nothing said: the privileged rule")
    }

    @Test
    fun `unknown features and asking where it cannot wait are refused`() {
        val p = Permissions()
        assertFailsWith<IllegalArgumentException> { p.rule(".*", "plugin", "deny") }
        assertFailsWith<IllegalArgumentException> { p.rule(".*", "clipboard", "ask") }
        assertFailsWith<IllegalArgumentException> { p.rule(".*", "clipboard", "maybe") }
    }

    @Test
    fun `config json's permissions replace those of before`() {
        val caulis = Caulis()
        caulis.permissions { rule("x", "gamma", "deny") }
        val read = ConfigFile.read("""{ "permissions": { "rules": [ { "program": "grim", "feature": "screencopy", "mode": "allow" } ], "defaults": { "clipboard": "deny" } } }""")
        assertTrue(read.problems.isEmpty(), read.problems.toString())
        assertTrue(Settings.applyEach(caulis, read.values).isEmpty())
        assertEquals(listOf("grim screencopy allow"), caulis.permissions.rules.map { it.toString() })
        assertEquals(PermissionMode.DENY, caulis.permissions.defaults["clipboard"])
    }

    @Test
    fun `a denied feature is not offered to the program`() = with(harness) {
        withCaulis({ permissions { rule(".*", "panels", "deny") } }, headless = true) { _, client ->
            assertNull(client.layerShell, "no panels for it")
            assertNotNull(client.sessionLock, "the rest as before")
        }
    }
}
