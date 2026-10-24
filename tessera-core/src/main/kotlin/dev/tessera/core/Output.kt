package dev.tessera.core

import dev.tessera.protocol.Display
import dev.tessera.protocol.wayland.WlOutput

/**
 * wl_output: tells clients about one monitor — its size, refresh rate and scale.
 */
class Output(
    display: Display,
    /** Connector name, e.g. "eDP-1" or "HEADLESS-1". */
    val name: String,
    val description: String,
    val widthPixels: Int,
    val heightPixels: Int,
    /** Refresh rate in millihertz (60 Hz = 60000). */
    val refreshMillihertz: Int,
    val scale: Int = 1,
    val physicalWidthMm: Int = 0,
    val physicalHeightMm: Int = 0,
    val make: String = "Tessera",
    val model: String = name,
) {
    init {
        display.addGlobal(WlOutput, VERSION) { output ->
            output.requests = object : WlOutput.Requests {
                override fun release(resource: WlOutput) {}
            }
            output.sendGeometry(0, 0, physicalWidthMm, physicalHeightMm,
                WlOutput.Subpixel.UNKNOWN, make, model, WlOutput.Transform.NORMAL)
            output.sendMode(WlOutput.Mode.CURRENT or WlOutput.Mode.PREFERRED, widthPixels, heightPixels, refreshMillihertz)
            output.sendScale(scale)
            output.sendName(name)
            output.sendDescription(description)
            output.sendDone()
        }
    }

    companion object {
        const val VERSION = 4
    }
}
