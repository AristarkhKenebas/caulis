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
        sendGeometry(output)
        sendMode(output)
        output.sendScale(integerScale)
        output.sendName(name)
        output.sendDescription(description)
        output.sendDone()
    }

    init {
        outputsOf(display) += this
    }

    /** The monitor is gone: clients stop seeing it (their wl_output objects stay valid but idle). */
    fun remove() {
        display.removeGlobal(global)
        bound.clear()
        outputsOf(display) -= this
    }

    /** This monitor's wl_output objects that [client] has. */
    fun resourcesOf(client: dev.caulis.protocol.Client): List<WlOutput> = bound.filter { it.client === client && it.isAlive }

    /** Where the monitor is on the desktop, in desktop units (xdg-output tells programs). */
    var x = 0
        private set
    var y = 0
        private set

    /** Moves the monitor on the desktop and tells the programs that ask (xdg-output). */
    fun position(newX: Int, newY: Int) {
        if (newX == x && newY == y) return
        x = newX
        y = newY
        for ((xdg, output) in xdgOutputs.toList()) sendLogical(xdg, output)
        for (output in bound) output.sendDone()
    }

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
        // Its name once, from version 2 on (Qt waits for it: without it, it never takes the
        // monitor for one and its windows get no cursor of their own, no scale, ...).
        if (xdg.version >= 2) {
            xdg.sendName(name)
            xdg.sendDescription(description)
        }
        sendLogical(xdg, output)
        // Since version 3 the monitor's own "done" ends the description.
        if (xdg.version >= 3) output.sendDone()
    }

    private fun sendLogical(xdg: dev.caulis.protocol.xdgoutputunstablev1.ZxdgOutputV1, output: WlOutput) {
        if (!xdg.isAlive) return
        // The desktop's own units: the pixels divided by the (fractional) scale — but in pixels
        // of their own for programs that work in pixels (Xwayland, see reportScaledTo).
        val perUnit = scaledFor(display)(xdg.client, this)
        val units = (width / scale) to (height / scale)
        if (perUnit != null) {
            xdg.sendLogicalPosition(Math.round(x * perUnit).toInt(), Math.round(y * perUnit).toInt())
            xdg.sendLogicalSize(Math.round(units.first * perUnit).toInt(), Math.round(units.second * perUnit).toInt())
        } else {
            xdg.sendLogicalPosition(x, y)
            xdg.sendLogicalSize(Math.round(units.first).toInt(), Math.round(units.second).toInt())
        }
        if (xdg.version < 3) xdg.sendDone()
    }

    /** How the monitor is turned; programs are told (they may draw turned themselves). */
    var transform: Transform = Transform.NORMAL
        private set

    /** Turns the monitor: [width] and [height] are the turned size from now on (see [resize]). */
    fun setTransform(newTransform: Transform) {
        if (newTransform == transform) return
        transform = newTransform
        for (output in bound) {
            sendGeometry(output)
            sendMode(output)
        }
        for (output in bound) output.sendDone()
    }

    private fun sendGeometry(output: WlOutput) = output.sendGeometry(0, 0, physicalWidthMm, physicalHeightMm, WlOutput.Subpixel.UNKNOWN, make, model, transform.ordinal)

    // The mode is the screen's own (not turned) size.
    private fun sendMode(output: WlOutput) =
        if (transform.sideways) output.sendMode(WlOutput.Mode.CURRENT or WlOutput.Mode.PREFERRED, height, width, refreshMillihertz)
        else output.sendMode(WlOutput.Mode.CURRENT or WlOutput.Mode.PREFERRED, width, height, refreshMillihertz)

    companion object {
        const val VERSION = 4

        // What a display keeps for us (see Display.attachments): its monitors, and who gets pixels.
        private object Monitors
        private object Scaled

        @Suppress("UNCHECKED_CAST")
        private fun outputsOf(display: Display): MutableList<Output> =
            display.attachments.getOrPut(Monitors) { java.util.concurrent.CopyOnWriteArrayList<Output>() } as MutableList<Output>

        /**
         * Tells some clients the monitors' size in pixels of their own, not in desktop units:
         * [pixelsPerUnit] gives how many for a client and a monitor, or null for an ordinary
         * client. Xwayland works in pixels — the screen's real ones (the monitor's [scale]) or,
         * for programs that only know whole scales, a whole number of them.
         */
        @JvmStatic
        fun reportScaledTo(display: Display, pixelsPerUnit: (dev.caulis.protocol.Client, Output) -> Double?) {
            display.attachments[Scaled] = pixelsPerUnit
        }

        /** Tells the clients [pixels] accepts (Xwayland) the monitors' size in the screen's real pixels. */
        @JvmStatic
        fun reportPixelsTo(display: Display, pixels: (dev.caulis.protocol.Client) -> Boolean) =
            reportScaledTo(display) { client, output -> if (pixels(client)) output.scale else null }

        @Suppress("UNCHECKED_CAST")
        private fun scaledFor(display: Display): (dev.caulis.protocol.Client, Output) -> Double? =
            display.attachments[Scaled] as ((dev.caulis.protocol.Client, Output) -> Double?)? ?: { _, _ -> null }

        /** The monitors [display] has now. */
        @JvmStatic
        fun of(display: Display): List<Output> = outputsOf(display).toList()
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
