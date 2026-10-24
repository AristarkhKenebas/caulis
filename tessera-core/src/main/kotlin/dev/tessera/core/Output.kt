package dev.tessera.core

import dev.tessera.protocol.Display
import dev.tessera.protocol.wayland.WlOutput

/**
 * wl_output: tells clients about one monitor — its size, refresh rate and scale.
 * The size can change (e.g. Tessera's window in nested mode is resized).
 */
class Output(
    display: Display,
    /** Connector name, e.g. "eDP-1" or "NESTED-1". */
    val name: String,
    val description: String,
    width: Int,
    height: Int,
    /** Refresh rate in millihertz (60 Hz = 60000). */
    val refreshMillihertz: Int,
    val scale: Int = 1,
    val physicalWidthMm: Int = 0,
    val physicalHeightMm: Int = 0,
    val make: String = "Tessera",
    val model: String = name,
) {
    var width = width
        private set
    var height = height
        private set

    private val bound = ArrayList<WlOutput>()

    init {
        display.addGlobal(WlOutput, VERSION) { output ->
            output.requests = object : WlOutput.Requests {
                override fun release(resource: WlOutput) {}
            }
            bound += output
            output.onDestroy { bound -= output }
            output.sendGeometry(0, 0, physicalWidthMm, physicalHeightMm,
                WlOutput.Subpixel.UNKNOWN, make, model, WlOutput.Transform.NORMAL)
            sendMode(output)
            output.sendScale(scale)
            output.sendName(name)
            output.sendDescription(description)
            output.sendDone()
        }
    }

    /** Changes the size and tells every client that uses this output. */
    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        width = newWidth
        height = newHeight
        for (output in bound) {
            sendMode(output)
            output.sendDone()
        }
    }

    private fun sendMode(output: WlOutput) =
        output.sendMode(WlOutput.Mode.CURRENT or WlOutput.Mode.PREFERRED, width, height, refreshMillihertz)

    companion object {
        const val VERSION = 4
    }
}
