package dev.caulis.core.drm

import dev.caulis.ffi.c.drm.Drm
import java.lang.foreign.Arena

/**
 * The monitor's gamma table.
 */
internal class DrmGamma(private val drmOutput: DrmOutput) {

    fun setGamma(red: ShortArray?, green: ShortArray?, blue: ShortArray?): Boolean {
        if (drmOutput.crtcProps["GAMMA_LUT"] == null) return false
        val blob = if (red == null || green == null || blue == null) {
            0
        } else {
            val size = red.size
            if (size != drmOutput.gammaSize || green.size != size || blue.size != size) return false
            // struct drm_color_lut { u16 red, green, blue, reserved }
            Arena.ofConfined().use { temp ->
                val table = temp.allocate(size * 8L)
                for (i in 0 until size) {
                    table.set(java.lang.foreign.ValueLayout.JAVA_SHORT, i * 8L, red[i])
                    table.set(java.lang.foreign.ValueLayout.JAVA_SHORT, i * 8L + 2, green[i])
                    table.set(java.lang.foreign.ValueLayout.JAVA_SHORT, i * 8L + 4, blue[i])
                    table.set(java.lang.foreign.ValueLayout.JAVA_SHORT, i * 8L + 6, 0)
                }
                drmOutput.device.createBlob(table)
            }
        }
        if (drmOutput.isSessionActive()) {
            val result = drmOutput.device.commit(Drm.DRM_MODE_ATOMIC_TEST_ONLY()) { request -> request.set(drmOutput.crtcId, drmOutput.crtcProps, "GAMMA_LUT", blob.toLong()) }
            if (result != 0) {
                drmOutput.log.warn("$drmOutput.name: the gamma table was refused (${drmOutput.errno(result)})")
                if (blob != 0) drmOutput.device.destroyBlob(blob)
                return false
            }
        }
        pendingGamma?.let { if (it != 0 && it != gammaBlob) drmOutput.device.destroyBlob(it) }
        pendingGamma = blob
        drmOutput.redrawAll = true
        drmOutput.flips.scheduleFrame(0)
        return true
    }

    // The gamma table on screen, and one waiting for the next picture.
    internal var gammaBlob = 0

    internal var pendingGamma: Int? = null

    // [gamma] reached the screen with a picture: the one before is no longer needed.
    internal fun gammaApplied(gamma: Int?) {
        if (gamma == null || pendingGamma != gamma) return
        if (gammaBlob != 0 && gammaBlob != gamma) drmOutput.device.destroyBlob(gammaBlob)
        gammaBlob = gamma
        pendingGamma = null
    }
}
