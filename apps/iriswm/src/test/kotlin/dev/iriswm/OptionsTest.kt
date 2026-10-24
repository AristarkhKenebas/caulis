package dev.iriswm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OptionsTest {

    @Test
    fun `the mode follows the environment unless chosen`() {
        assertEquals(Options.Mode.NESTED, Options.parse(arrayOf(), mapOf("WAYLAND_DISPLAY" to "wayland-0")).mode)
        assertEquals(Options.Mode.SCREEN, Options.parse(arrayOf(), emptyMap()).mode)
        assertEquals(Options.Mode.SCREEN, Options.parse(arrayOf("--screen"), mapOf("WAYLAND_DISPLAY" to "wayland-0")).mode)
    }

    @Test
    fun `values are read`() {
        val options = Options.parse(arrayOf("--exit-after", "30", "--run", "foot", "--run", "gtk4-demo"), emptyMap())
        assertEquals(30L, options.exitAfterSeconds)
        assertEquals(listOf("foot", "gtk4-demo"), options.run)
    }

    @Test
    fun `mistakes give readable errors`() {
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--exit-after", "soon"), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--exit-after"), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--fly"), emptyMap()) }
    }
}
