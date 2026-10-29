package dev.caulis.core

import dev.caulis.ffi.Fd
import dev.caulis.log.Logger
import dev.caulis.protocol.Display
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wlrgammacontrolunstablev1.ZwlrGammaControlManagerV1
import dev.caulis.protocol.wlrgammacontrolunstablev1.ZwlrGammaControlV1
import dev.caulis.protocol.wlroutputpowermanagementunstablev1.ZwlrOutputPowerManagerV1
import dev.caulis.protocol.wlroutputpowermanagementunstablev1.ZwlrOutputPowerV1
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

/**
 * wlr-output-power-management: programs switch screens off and on (swayidle: "after 300 s,
 * screens off"). The monitors' own [MonitorControl.setPower] does it.
 */
class OutputPower(display: Display, private val desktop: Desktop) {

    private val powers = ArrayList<Pair<ZwlrOutputPowerV1, Monitor>>()

    init {
        display.addPrivilegedGlobal(ZwlrOutputPowerManagerV1, 1, "monitors") { manager ->
            manager.requests = object : ZwlrOutputPowerManagerV1.Requests {
                override fun getOutputPower(resource: ZwlrOutputPowerManagerV1, id: ZwlrOutputPowerV1, output: WlOutput) {
                    id.requests = object : ZwlrOutputPowerV1.Requests {
                        override fun setMode(resource: ZwlrOutputPowerV1, mode: Int) {
                            if (mode != ZwlrOutputPowerV1.Mode.ON && mode != ZwlrOutputPowerV1.Mode.OFF) {
                                resource.postError(ZwlrOutputPowerV1.Error.INVALID_MODE, "power mode $mode does not exist (0 off, 1 on)")
                                return
                            }
                            val monitor = monitorOf(output) ?: return resource.sendFailed()
                            if (!desktop.setPower(monitor, mode == ZwlrOutputPowerV1.Mode.ON)) resource.sendFailed()
                        }

                        override fun destroy(resource: ZwlrOutputPowerV1) {}
                    }
                    val monitor = monitorOf(output)
                    if (monitor?.control == null) {
                        id.sendFailed()
                        return
                    }
                    val entry = id to monitor
                    powers += entry
                    id.onDestroy { powers -= entry }
                    id.sendMode(if (monitor.powered) ZwlrOutputPowerV1.Mode.ON else ZwlrOutputPowerV1.Mode.OFF)
                }

                override fun destroy(resource: ZwlrOutputPowerManagerV1) {}
            }
        }
        desktop.onPowerChanged { monitor ->
            for ((power, of) in powers) if (of === monitor && power.isAlive) power.sendMode(if (monitor.powered) ZwlrOutputPowerV1.Mode.ON else ZwlrOutputPowerV1.Mode.OFF)
        }
    }

    private fun monitorOf(output: WlOutput): Monitor? = output.dataAs<Output>()?.let { o -> desktop.monitors.firstOrNull { it.output === o } }
}

/**
 * wlr-gamma-control: night-light programs (wlsunset, gammastep) set the monitors' colours
 * warmer; when such a program goes, the monitor's own colours come back. One program per
 * monitor at a time.
 */
class GammaControl(display: Display, private val desktop: Desktop) {

    private val log = Logger("drm")
    private val owners = HashMap<Monitor, ZwlrGammaControlV1>()

    init {
        display.addPrivilegedGlobal(ZwlrGammaControlManagerV1, 1, "gamma") { manager ->
            manager.requests = object : ZwlrGammaControlManagerV1.Requests {
                override fun getGammaControl(resource: ZwlrGammaControlManagerV1, id: ZwlrGammaControlV1, output: WlOutput) {
                    val monitor = output.dataAs<Output>()?.let { o -> desktop.monitors.firstOrNull { it.output === o } }
                    val size = monitor?.control?.gammaSize ?: 0
                    id.requests = object : ZwlrGammaControlV1.Requests {
                        override fun setGamma(resource: ZwlrGammaControlV1, fd: Fd) {
                            fd.use { file ->
                                val tables = if (monitor != null && owners[monitor] === resource) read(file, size) else null
                                if (tables == null || !monitor!!.control!!.setGamma(tables[0], tables[1], tables[2])) {
                                    resource.sendFailed()
                                    release(monitor, resource)
                                }
                            }
                        }

                        override fun destroy(resource: ZwlrGammaControlV1) {}
                    }
                    if (monitor == null || size == 0 || owners[monitor]?.isAlive == true) {
                        id.sendFailed()
                        return
                    }
                    owners[monitor] = id
                    id.onDestroy { release(monitor, id) }
                    id.sendGammaSize(size)
                    log.info { "${id.client.name} sets the colours of ${monitor.name}" }
                }

                override fun destroy(resource: ZwlrGammaControlManagerV1) {}
            }
        }
    }

    // Its program is gone: the monitor's own colours again.
    private fun release(monitor: Monitor?, control: ZwlrGammaControlV1) {
        if (monitor == null || owners[monitor] !== control) return
        owners.remove(monitor)
        monitor.control?.setGamma(null, null, null)
    }

    // The three tables (red, green, blue: [size] 16-bit values each) from [fd].
    // The tables must be there already: a pipe nobody writes to never stops the compositor (it
    // only reads what is there, as wlroots does, and a short table fails).
    private fun read(fd: Fd, size: Int): List<ShortArray>? = Arena.ofConfined().use { temp ->
        val bytes = size * 3L * 2
        val buffer = temp.allocate(bytes)
        var at = 0L
        try {
            fd.setNonBlocking()
            while (at < bytes) {
                val count = fd.read(buffer.asSlice(at))
                if (count <= 0) return null
                at += count
            }
        } catch (e: dev.caulis.ffi.NativeException) {
            return null // (EAGAIN: not all of it was written yet)
        }
        (0 until 3).map { table -> ShortArray(size) { i -> buffer.getAtIndex(ValueLayout.JAVA_SHORT, table * size.toLong() + i) } }
    }
}
