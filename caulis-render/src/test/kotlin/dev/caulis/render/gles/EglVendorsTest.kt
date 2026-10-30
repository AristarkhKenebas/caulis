package dev.caulis.render.gles

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Only the EGL vendor for the GPU's kernel driver is chosen; nothing when unsure. */
class EglVendorsTest {
    private val nvidia = Path.of("/usr/share/glvnd/egl_vendor.d/10_nvidia.json") to "libEGL_nvidia.so.0"
    private val mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json") to "libEGL_mesa.so.0"
    private val both = listOf(nvidia, mesa)

    @Test
    fun `an AMD or Intel GPU loads Mesa's only`() {
        assertEquals(listOf(mesa.first), EglVendors.filesFor("amdgpu", both))
        assertEquals(listOf(mesa.first), EglVendors.filesFor("i915", both))
    }

    @Test
    fun `an NVIDIA GPU with its own driver loads NVIDIA's only`() {
        assertEquals(listOf(nvidia.first), EglVendors.filesFor("nvidia-drm", both))
    }

    @Test
    fun `no choice when unsure or when there is nothing to leave out`() {
        assertNull(EglVendors.filesFor(null, both), "driver unknown")
        assertNull(EglVendors.filesFor("some-blob", both), "a driver not known to be Mesa's")
        assertNull(EglVendors.filesFor("amdgpu", listOf(mesa)), "only Mesa installed: nothing to choose")
        assertNull(EglVendors.filesFor("nvidia-drm", listOf(mesa)), "no NVIDIA vendor file: leave libglvnd alone")
    }
}
