package dev.caulis.core

import dev.caulis.log.Logger
import dev.caulis.protocol.Display
import dev.caulis.protocol.wlroutputmanagementunstablev1.ZwlrOutputConfigurationHeadV1
import dev.caulis.protocol.wlroutputmanagementunstablev1.ZwlrOutputConfigurationV1
import dev.caulis.protocol.wlroutputmanagementunstablev1.ZwlrOutputHeadV1
import dev.caulis.protocol.wlroutputmanagementunstablev1.ZwlrOutputManagerV1
import dev.caulis.protocol.wlroutputmanagementunstablev1.ZwlrOutputModeV1

/** A monitor plugged into the computer, used or not ([monitor] null: switched off by the user or a program). */
class Head(
    val name: String,
    val description: String,
    val make: String,
    val model: String,
    val serial: String,
    val widthMm: Int,
    val heightMm: Int,
    val modes: List<MonitorMode>,
    val monitor: Monitor?,
)

/**
 * The monitors there are, used or not, for programs that arrange them; the backend that can
 * switch monitors on and off gives one (the screen does), else only the monitors in use.
 */
interface Heads {
    fun heads(): List<Head>

    /** Uses the monitor [name] again (true) or stops using it; false if that cannot be. */
    fun setEnabled(name: String, on: Boolean): Boolean = false
}

/**
 * wlr-output-management: kanshi, wlr-randr, nwg-displays and other programs see every
 * monitor plugged in — on or off, its modes, place, scale and turn — and change them: a
 * configuration is tested or applied as a whole, and refused if the monitors changed since
 * it was made.
 */
class OutputManagement(display: Display, private val desktop: Desktop) {

    private val log = Logger("layout")

    /** Where the monitors come from (the backend's own when it has one). */
    var heads: Heads = object : Heads {
        override fun heads() = desktop.monitors.filter { it.name != "default" }.map { monitorHead(it) }
    }

    private var serial = 1

    // Each manager's head and mode objects, by monitor name.
    private inner class Client(val manager: ZwlrOutputManagerV1) {
        val heads = LinkedHashMap<String, Pair<ZwlrOutputHeadV1, List<Pair<ZwlrOutputModeV1, MonitorMode>>>>()
        val sent = HashMap<String, String>()
    }

    private val clients = ArrayList<Client>()

    init {
        display.addGlobal(ZwlrOutputManagerV1, VERSION) { manager ->
            val client = Client(manager)
            clients += client
            manager.onDestroy { clients -= client }
            manager.requests = object : ZwlrOutputManagerV1.Requests {
                override fun createConfiguration(resource: ZwlrOutputManagerV1, id: ZwlrOutputConfigurationV1, serial: Int) = configure(client, id, serial)

                override fun stop(resource: ZwlrOutputManagerV1) {
                    clients -= client
                    resource.sendFinished()
                }
            }
            describe(client)
        }
    }

    /** The monitors changed (came, went, moved, scaled, turned, another mode): programs hear it. */
    fun changed() {
        serial++
        for (client in clients.toList()) describe(client)
    }

    private fun monitorHead(monitor: Monitor): Head {
        val output = monitor.output
        return Head(
            monitor.name, output?.description ?: monitor.name, output?.make ?: "Unknown", output?.model ?: monitor.name, "",
            output?.physicalWidthMm ?: 0, output?.physicalHeightMm ?: 0,
            monitor.control?.modes ?: listOf(MonitorMode(monitor.pixelWidth, monitor.pixelHeight, output?.refreshMillihertz ?: 60_000, true)),
            monitor,
        )
    }

    // Tells one program how the monitors are now (only what changed for heads it knows).
    private fun describe(client: Client) {
        val manager = client.manager
        if (!manager.isAlive) return
        val now = heads.heads()
        for (gone in client.heads.keys - now.map { it.name }.toSet()) {
            val (head, modes) = client.heads.remove(gone)!!
            modes.forEach { (mode, _) -> if (mode.isAlive) mode.sendFinished() }
            if (head.isAlive) head.sendFinished()
            client.sent.remove(gone)
        }
        for (info in now) {
            val known = client.heads[info.name]
            val (head, modes) = known ?: run {
                val head = manager.client.createServerResource(ZwlrOutputHeadV1, manager.version)
                head.requests = object : ZwlrOutputHeadV1.Requests {
                    override fun release(resource: ZwlrOutputHeadV1) {}
                }
                head.data = info.name
                manager.sendHead(head)
                head.sendName(info.name)
                head.sendDescription(info.description)
                if (info.widthMm > 0 && info.heightMm > 0) head.sendPhysicalSize(info.widthMm, info.heightMm)
                val modes = info.modes.map { mode ->
                    val m = manager.client.createServerResource(ZwlrOutputModeV1, minOf(manager.version, 3))
                    m.requests = object : ZwlrOutputModeV1.Requests {
                        override fun release(resource: ZwlrOutputModeV1) {}
                    }
                    head.sendMode(m)
                    m.sendSize(mode.width, mode.height)
                    m.sendRefresh(mode.refreshMillihertz)
                    if (mode.preferred) m.sendPreferred()
                    m to mode
                }
                if (head.version >= 2) {
                    head.sendMake(info.make)
                    head.sendModel(info.model)
                    if (info.serial.isNotEmpty()) head.sendSerialNumber(info.serial)
                }
                (head to modes).also { client.heads[info.name] = it }
            }
            // Its state now, sent when it differs from what this program knows.
            val monitor = info.monitor
            val state = if (monitor == null) "off" else "${monitor.control?.currentMode}|${monitor.area.x},${monitor.area.y}|${monitor.transform}|${monitor.scale}"
            if (client.sent[info.name] == state) continue
            client.sent[info.name] = state
            head.sendEnabled(if (monitor != null) 1 else 0)
            if (monitor != null) {
                val current = monitor.control?.currentMode
                (modes.firstOrNull { it.second == current } ?: modes.firstOrNull())?.let { head.sendCurrentMode(it.first) }
                head.sendPosition(monitor.area.x, monitor.area.y)
                head.sendTransform(monitor.transform.ordinal)
                head.sendScale(monitor.scale)
            }
        }
        manager.sendDone(serial)
    }

    // What a program wants for one monitor.
    private class Wish(val name: String) {
        var mode: MonitorMode? = null
        var x: Int? = null
        var y: Int? = null
        var transform: Transform? = null
        var scale: Double? = null
    }

    private fun configure(client: Client, configuration: ZwlrOutputConfigurationV1, madeAt: Int) {
        val enable = LinkedHashMap<String, Wish>()
        val disable = LinkedHashSet<String>()
        var used = false
        fun nameOf(head: ZwlrOutputHeadV1) = head.data as? String
        configuration.requests = object : ZwlrOutputConfigurationV1.Requests {
            override fun enableHead(resource: ZwlrOutputConfigurationV1, id: ZwlrOutputConfigurationHeadV1, head: ZwlrOutputHeadV1) {
                val name = nameOf(head) ?: return
                if (name in enable || name in disable) {
                    resource.postError(ZwlrOutputConfigurationV1.Error.ALREADY_CONFIGURED_HEAD, "$name is configured twice")
                    return
                }
                val wish = Wish(name)
                enable[name] = wish
                id.requests = object : ZwlrOutputConfigurationHeadV1.Requests {
                    override fun setMode(resource: ZwlrOutputConfigurationHeadV1, mode: ZwlrOutputModeV1) {
                        wish.mode = client.heads[name]?.second?.firstOrNull { it.first === mode }?.second
                    }

                    override fun setCustomMode(resource: ZwlrOutputConfigurationHeadV1, width: Int, height: Int, refresh: Int) {
                        wish.mode = MonitorMode(width, height, refresh)
                    }

                    override fun setPosition(resource: ZwlrOutputConfigurationHeadV1, x: Int, y: Int) {
                        wish.x = x
                        wish.y = y
                    }

                    override fun setTransform(resource: ZwlrOutputConfigurationHeadV1, transform: Int) {
                        wish.transform = Transform.of(transform)
                    }

                    override fun setScale(resource: ZwlrOutputConfigurationHeadV1, scale: Double) {
                        wish.scale = scale
                    }

                    override fun setAdaptiveSync(resource: ZwlrOutputConfigurationHeadV1, state: Int) {}
                }
            }

            override fun disableHead(resource: ZwlrOutputConfigurationV1, head: ZwlrOutputHeadV1) {
                val name = nameOf(head) ?: return
                if (name in enable || name in disable) {
                    resource.postError(ZwlrOutputConfigurationV1.Error.ALREADY_CONFIGURED_HEAD, "$name is configured twice")
                    return
                }
                disable += name
            }

            override fun apply(resource: ZwlrOutputConfigurationV1) = finish(resource, test = false)
            override fun test(resource: ZwlrOutputConfigurationV1) = finish(resource, test = true)
            override fun destroy(resource: ZwlrOutputConfigurationV1) {}

            fun finish(resource: ZwlrOutputConfigurationV1, test: Boolean) {
                if (used) {
                    resource.postError(ZwlrOutputConfigurationV1.Error.ALREADY_USED, "this configuration was applied or tested already")
                    return
                }
                used = true
                when {
                    madeAt != serial -> resource.sendCancelled()
                    problem(enable, disable) != null -> {
                        log.info { "${resource.client.name}: monitor configuration refused: ${problem(enable, disable)}" }
                        resource.sendFailed()
                    }
                    test -> resource.sendSucceeded()
                    else -> if (apply(enable, disable, resource.client.name)) resource.sendSucceeded() else resource.sendFailed()
                }
            }
        }
    }

    // What is wrong with a configuration, or null.
    private fun problem(enable: Map<String, Wish>, disable: Set<String>): String? {
        val all = heads.heads().associateBy { it.name }
        if (enable.isEmpty()) return "it would switch every monitor off"
        for ((name, wish) in enable) {
            val head = all[name] ?: return "$name is not plugged in"
            wish.scale?.let { if (it !in 0.5..4.0) return "scale $it (0.5 to 4)" }
            wish.mode?.let { mode ->
                val listed = head.modes.any { it.width == mode.width && it.height == mode.height }
                if (!listed && head.monitor?.control?.customModes != true) return "$name has no mode $mode"
            }
            val control = head.monitor?.control
            if (wish.transform != null && wish.transform != Transform.NORMAL && control != null && !control.canTransform) return "$name cannot be turned"
        }
        return null
    }

    // Applies a configuration that passed; false if a part of it did not work.
    private fun apply(enable: Map<String, Wish>, disable: Set<String>, by: String): Boolean {
        var ok = true
        log.info { "$by arranges the monitors: ${enable.keys.joinToString { "$it on" }}${disable.joinToString("") { ", $it off" }}" }
        for (name in disable) ok = heads.setEnabled(name, false) && ok
        for ((name, wish) in enable) {
            var monitor = desktop.monitors.firstOrNull { it.name == name }
            if (monitor == null) {
                ok = heads.setEnabled(name, true) && ok
                monitor = desktop.monitors.firstOrNull { it.name == name } ?: continue
            }
            wish.mode?.let { mode -> if (mode != monitor.control?.currentMode) ok = (monitor.control?.setMode(mode) ?: false) && ok }
            wish.transform?.let { desktop.setTransform(monitor, it) }
            wish.scale?.let { desktop.setScale(monitor, it) }
            if (wish.x != null && wish.y != null) desktop.moveMonitor(monitor, wish.x, wish.y)
        }
        changed()
        return ok
    }

    companion object {
        const val VERSION = 4
    }
}
