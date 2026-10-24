package dev.caulis.wm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreflightTest {

    @Test
    fun `a missing terminal is a warning, not a reason to stop`() {
        val config = Caulis().apply {
            terminal = "surely-not-an-installed-terminal"
            nestedIn = existingSocket()
        }
        val problems = Preflight.check(config, nested = true)
        val terminal = problems.single { "surely-not-an-installed-terminal" in it.message }
        assertFalse(terminal.fatal)
        assertTrue("terminal =" in terminal.fix, "and it says how to choose another one")
    }

    @Test
    fun `no session to open a window in stops it with an explanation`() {
        val config = Caulis().apply { nestedIn = Path.of("/nonexistent/wayland-socket") }
        val problems = Preflight.check(config, nested = true)
        assertTrue(problems.any { it.fatal && "Wayland session" in it.message })
        assertFalse(Preflight.report(problems, "Test"))
    }

    @Test
    fun `an installed terminal raises nothing`() {
        val config = Caulis().apply {
            terminal = "sh" // exists everywhere
            nestedIn = existingSocket()
        }
        assertTrue(Preflight.check(config, nested = true).none { "terminal" in it.message })
    }

    // Any existing file stands in for a session socket here.
    private fun existingSocket(): Path = Files.createTempFile("socket", "").also { it.toFile().deleteOnExit() }
}
