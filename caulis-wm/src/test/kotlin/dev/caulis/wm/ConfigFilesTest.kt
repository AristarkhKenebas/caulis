package dev.caulis.wm

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Where your files are: next to the configuration, in your home, or where you say. */
class ConfigFilesTest {

    private val home = Path.of(System.getProperty("user.home"))

    @Test
    fun `names are found next to the configuration, in the home folder, or as written`() {
        val caulis = Caulis().apply { configName = "iriswm" }
        val usual = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") }?.let { Path.of(it) } ?: home.resolve(".config")
        assertEquals(usual.resolve("iriswm"), caulis.configFolder)
        assertEquals(usual.resolve("iriswm/shaders/aurora.glsl"), caulis.file("shaders/aurora.glsl"))
        assertEquals(home.resolve("walls/sea.jpg"), caulis.file("~/walls/sea.jpg"))
        assertEquals(Path.of("/usr/share/backgrounds/x.jpg"), caulis.file("/usr/share/backgrounds/x.jpg"))

        caulis.configDir = Path.of("/tmp/elsewhere")
        assertEquals(Path.of("/tmp/elsewhere/aurora.glsl"), caulis.file("aurora.glsl"))
        assertFailsWith<IllegalArgumentException> { caulis.configName = "a/b" }
    }
}
