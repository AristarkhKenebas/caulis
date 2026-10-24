package dev.caulis.core

import dev.caulis.protocol.Display
import dev.caulis.protocol.wayland.WlOutput

/**
 * wl_output: tells clients about one monitor — its size, refresh rate and scale.
 * The size can change (e.g. Caulis's window in nested mode is resized).
 */
class Output(
    private val display: Display,
    /** Connector name, e.g. "eDP-1" or "NESTED-1". */
    val name: String,
    val description: String,
    width: Int,
    height: Int,
    /** Refresh rate in millihertz (60 Hz = 60000). */
    val refreshMillihertz: Int,
    scale: Double = 1.0,
    val physicalWidthMm: Int = 0,
    val physicalHeightMm: Int = 0,
    val make: String = "Caulis",
    val model: String = name,
) {
    var width = width
        private set
    var height = height
        private set

    private val bound = ArrayList<WlOutput>()

    /** How many pixels one desktop unit is on this monitor (1.0, 1.25, 2.0, ...). */
    var scale: Double = scale
        private set

    /** The whole-number scale wl_output tells programs (the next one up). */
    val integerScale: Int get() = Math.ceil(scale - 1e-9).toInt().coerceAtLeast(1)

    /** Changes the scale and tells every client that uses this output. */
    fun rescale(newScale: Double) {
        if (newScale == scale) return
        scale = newScale
        for (output in bound) output.sendScale(integerScale)
        for ((xdg, output) in xdgOutputs.toList()) sendLogical(xdg, output)
        for (output in bound) output.sendDone()
    }

    private val global = display.addGlobal(WlOutput, VERSION) { output ->
        output.requests = object : WlOutput.Requests {
            override fun release(resource: WlOutput) {}
        }
        output.data = this
        bound += output
        output.onDestroy { bound -= output }
        output.sendGeometry(0, 0, physicalWidthMm, physicalHeightMm,
            WlOutput.Subpixel.UNKNOWN, make, model, WlOutput.Transform.NORMAL)
        sendMode(output)
        output.sendScale(integerScale)
        output.sendName(name)
        output.sendDescription(description)
        output.sendDone()
    }

    init {
        synchronized(all) { all.getOrPut(display) { ArrayList() } += this }
    }

    /** The monitor is gone: clients stop seeing it (their wl_output objects stay valid but idle). */
    fun remove() {
        display.removeGlobal(global)
        bound.clear()
        synchronized(all) { all[display]?.remove(this) }
    }

    /** This monitor's wl_output objects that [client] has. */
    fun resourcesOf(client: dev.caulis.protocol.Client): List<WlOutput> = bound.filter { it.client === client && it.isAlive }

    /** Changes the size and tells every client that uses this output. */
    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        width = newWidth
        height = newHeight
        for (output in bound) sendMode(output)
        for ((xdg, output) in xdgOutputs.toList()) sendLogical(xdg, output)
        for (output in bound) output.sendDone()
    }

    // xdg-output: where the monitor is and how big, in the desktop's own (logical) pixels.
    private val xdgOutputs = ArrayList<Pair<dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputV1, WlOutput>>()

    internal fun addXdgOutput(xdg: dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputV1, output: WlOutput) {
        xdg.requests = object : dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputV1.Requests {
            override fun destroy(resource: dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputV1) {}
        }
        val entry = xdg to output
        xdgOutputs += entry
        xdg.onDestroy { xdgOutputs -= entry }
        sendLogical(xdg, output)
        // Since version 3 the monitor's own "done" ends the description.
        if (xdg.version >= 3) output.sendDone()
    }

    private fun sendLogical(xdg: dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputV1, output: WlOutput) {
        if (!xdg.isAlive) return
        xdg.sendLogicalPosition(0, 0)
        // The desktop's own units: the pixels divided by the (fractional) scale — but in pixels
        // for programs that work in pixels (Xwayland, see pixelsFor).
        if (pixelsFor(display)(xdg.client)) {
            xdg.sendLogicalSize(width, height)
        } else {
            xdg.sendLogicalSize(Math.round(width / scale).toInt(), Math.round(height / scale).toInt())
        }
        if (xdg.version == 2) {
            xdg.sendName(name)
            xdg.sendDescription(description)
        }
        if (xdg.version < 3) xdg.sendDone()
    }

    private fun sendMode(output: WlOutput) =
        output.sendMode(WlOutput.Mode.CURRENT or WlOutput.Mode.PREFERRED, width, height, refreshMillihertz)

    companion object {
        const val VERSION = 4

        // The monitors of each display.
        private val all = java.util.WeakHashMap<Display, MutableList<Output>>()

        private val pixelClients = java.util.WeakHashMap<Display, (dev.caulis.protocol.Client) -> Boolean>()

        /**
         * Tells the clients [pixels] accepts (Xwayland) the monitors' size in pixels, not in
         * desktop units: they work in the screen's real pixels.
         */
        @JvmStatic
        fun reportPixelsTo(display: Display, pixels: (dev.caulis.protocol.Client) -> Boolean) {
            synchronized(pixelClients) { pixelClients[display] = pixels }
        }

        private fun pixelsFor(display: Display): (dev.caulis.protocol.Client) -> Boolean =
            synchronized(pixelClients) { pixelClients[display] } ?: { false }

        /** The monitors [display] has now. */
        @JvmStatic
        fun of(display: Display): List<Output> = synchronized(all) { all[display]?.toList() ?: emptyList() }
    }
}

/** xdg-output: monitors' place and size in the desktop (grim, slurp, Waybar and portals need it). */
class XdgOutputManager(display: Display) {
    init {
        display.addGlobal(dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputManagerV1, 3) { manager ->
            manager.requests = object : dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputManagerV1.Requests {
                override fun getXdgOutput(resource: dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputManagerV1, id: dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputV1, output: WlOutput) {
                    output.dataAs<Output>()?.addXdgOutput(id, output)
                }

                override fun destroy(resource: dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputManagerV1) {}
            }
        }
    }
}
