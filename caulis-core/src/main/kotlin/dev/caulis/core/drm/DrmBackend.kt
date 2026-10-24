package dev.caulis.core.drm

import dev.caulis.core.libinput.InputDevices
import dev.caulis.core.session.Session
import dev.caulis.event.EventLoop
import dev.caulis.ffi.CStrings
import dev.caulis.ffi.Fd
import dev.caulis.ffi.c.drm.Drm
import dev.caulis.ffi.c.drm._drmModeConnector
import dev.caulis.ffi.c.drm._drmModeModeInfo
import dev.caulis.ffi.c.drm._drmModePlane
import dev.caulis.ffi.c.drm._drmModePlaneRes
import dev.caulis.ffi.c.drm._drmModeRes
import dev.caulis.ffi.c.input.Input
import dev.caulis.render.GpuDevices
import dev.caulis.render.Renderer
import dev.caulis.render.gles.GlesRenderer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path

/**
 * Running directly on the screen (no other compositor): the login [session], the GPU's
 * display side ([device]), monitors ([outputs]) and input devices ([input]).
 *
 * Monitors can be plugged in and out at any time ([onOutputAdded], [onOutputRemoved]);
 * switching to another virtual terminal and back is handled here (everything pauses and
 * then comes back with a full redraw).
 *
 * ```
 * DrmBackend.open(loop).use { backend ->
 *     backend.onOutputAdded = { output -> output.onFrame = { output.present { target -> ... } } }
 *     backend.start()
 *     loop.run()
 * }
 * ```
 */
class DrmBackend private constructor(
    private val loop: EventLoop,
    val session: Session,
    val device: DrmDevice,
    val renderer: Renderer,
) : AutoCloseable {

    /** Keyboards, mice, touchpads. */
    val input = InputDevices(loop, session)

    private val active = LinkedHashMap<Int, DrmOutput>() // by connector id

    /** Monitors in use right now. */
    val outputs: Collection<DrmOutput> get() = active.values

    var onOutputAdded: (DrmOutput) -> Unit = {}
    var onOutputRemoved: (DrmOutput) -> Unit = {}

    private val log = System.getLogger("caulis.drm")
    private val arena = Arena.ofShared()
    private val udev: MemorySegment = Input.udev_new()
    private var hotplug: MemorySegment = MemorySegment.NULL
    private var hotplugFd: Fd? = null
    private var rescanTimer: EventLoop.Timer? = null

    init {
        session.onActiveChanged { nowActive ->
            if (nowActive) {
                // Another program may have changed the monitors meanwhile: look again, redraw all.
                rescan()
                active.values.forEach { it.resume() }
            }
        }
        watchHotplug()
    }

    /** Finds the connected monitors and turns them on. Set the callbacks before calling this. */
    fun start() = rescan()

    // --- monitors -----------------------------------------------------------------------

    private fun rescan() {
        val connected = connectedConnectors()
        // Gone (or a different monitor in the same socket): remove.
        for ((connectorId, output) in active.toList()) {
            val now = connected[connectorId]
            if (now == null || now.edid != output.edid) removeOutput(connectorId)
        }
        // New: find a CRTC and plane for it and turn it on.
        for ((connectorId, info) in connected) {
            if (connectorId in active) continue
            val output = createOutput(info) ?: continue
            if (!output.start()) {
                log.log(System.Logger.Level.WARNING, "No mode of ${info.name} works; leaving it off")
                output.close()
                continue
            }
            active[connectorId] = output
            log.log(System.Logger.Level.INFO, "Monitor ${output.name} (${output.edid?.model ?: "unknown"}) at ${output.mode}")
            onOutputAdded(output)
        }
    }

    private fun removeOutput(connectorId: Int) {
        val output = active.remove(connectorId) ?: return
        log.log(System.Logger.Level.INFO, "Monitor ${output.name} disconnected")
        onOutputRemoved(output)
        output.stop()
        output.close()
    }

    private class ConnectorInfo(
        val id: Int,
        val name: String,
        val edid: Edid?,
        val widthMm: Int,
        val heightMm: Int,
        val modes: List<DrmOutput.Mode>,
        val possibleCrtcs: Int,
    )

    private fun connectedConnectors(): Map<Int, ConnectorInfo> {
        val fd = device.fd
        val resources = Drm.drmModeGetResources(fd)
        if (resources == MemorySegment.NULL) return emptyMap()
        try {
            val count = _drmModeRes.count_connectors(resources)
            val ids = _drmModeRes.connectors(resources).reinterpret(count * 4L)
            val result = LinkedHashMap<Int, ConnectorInfo>()
            for (i in 0 until count) {
                val connector = Drm.drmModeGetConnector(fd, ids.getAtIndex(JAVA_INT, i.toLong()))
                if (connector == MemorySegment.NULL) continue
                try {
                    if (_drmModeConnector.connection(connector) != Drm.DRM_MODE_CONNECTED()) continue
                    val modes = readModes(connector)
                    if (modes.isEmpty()) continue
                    val id = _drmModeConnector.connector_id(connector)
                    val edidBlob = device.properties(id, Drm.DRM_MODE_OBJECT_CONNECTOR())["EDID"]?.value ?: 0
                    result[id] = ConnectorInfo(
                        id = id,
                        name = "${connectorTypeName(_drmModeConnector.connector_type(connector))}-${_drmModeConnector.connector_type_id(connector)}",
                        edid = device.blob(edidBlob)?.let(Edid::parse),
                        widthMm = _drmModeConnector.mmWidth(connector),
                        heightMm = _drmModeConnector.mmHeight(connector),
                        modes = modes,
                        possibleCrtcs = Drm.drmModeConnectorGetPossibleCrtcs(fd, connector),
                    )
                } finally {
                    Drm.drmModeFreeConnector(connector)
                }
            }
            return result
        } finally {
            Drm.drmModeFreeResources(resources)
        }
    }

    // The monitor's modes: preferred first, then the largest and fastest.
    private fun readModes(connector: MemorySegment): List<DrmOutput.Mode> {
        val count = _drmModeConnector.count_modes(connector)
        val size = _drmModeModeInfo.layout().byteSize()
        val all = _drmModeConnector.modes(connector).reinterpret(count * size)
        return (0 until count).map { i ->
            val info = all.asSlice(i * size, size)
            val clock = _drmModeModeInfo.clock(info).toLong() // kHz
            val htotal = _drmModeModeInfo.htotal(info).toLong() and 0xffff
            val vtotal = _drmModeModeInfo.vtotal(info).toLong() and 0xffff
            val refresh = if (htotal > 0 && vtotal > 0) (clock * 1_000_000 / (htotal * vtotal)).toInt() else _drmModeModeInfo.vrefresh(info) * 1000
            DrmOutput.Mode(
                width = _drmModeModeInfo.hdisplay(info).toInt() and 0xffff,
                height = _drmModeModeInfo.vdisplay(info).toInt() and 0xffff,
                refreshMillihertz = refresh,
                preferred = _drmModeModeInfo.type(info) and Drm.DRM_MODE_TYPE_PREFERRED() != 0,
                info = info.toArray(JAVA_BYTE),
            )
        }.filter { it.width > 0 && it.height > 0 }
            .sortedWith(compareByDescending<DrmOutput.Mode> { it.preferred }
                .thenByDescending { it.width.toLong() * it.height }
                .thenByDescending { it.refreshMillihertz })
    }

    private fun createOutput(info: ConnectorInfo): DrmOutput? {
        val fd = device.fd
        val resources = Drm.drmModeGetResources(fd)
        if (resources == MemorySegment.NULL) return null
        val crtcIds = try {
            val count = _drmModeRes.count_crtcs(resources)
            val ids = _drmModeRes.crtcs(resources).reinterpret(count * 4L)
            (0 until count).map { ids.getAtIndex(JAVA_INT, it.toLong()) }
        } finally {
            Drm.drmModeFreeResources(resources)
        }
        val used = active.values.map { it.crtcId }.toSet()
        val index = crtcIds.indices.firstOrNull { info.possibleCrtcs and (1 shl it) != 0 && crtcIds[it] !in used }
        if (index == null) {
            log.log(System.Logger.Level.WARNING, "No free display controller (CRTC) for ${info.name}")
            return null
        }
        val crtc = crtcIds[index]
        val planes = planes()
        val primary = planes.firstOrNull { it.type == Drm.DRM_PLANE_TYPE_PRIMARY().toLong() && it.possibleCrtcs and (1 shl index) != 0 && it.id !in usedPlanes() }
            ?: return null.also { log.log(System.Logger.Level.WARNING, "No primary plane for ${info.name}") }
        val stray = planes.filter { it.currentCrtc == crtc && it.id != primary.id }.map { it.id }
        return DrmOutput(
            loop, device, renderer, { session.active }, info.id, crtc, primary.id, info.name, info.edid,
            info.widthMm, info.heightMm, info.modes, stray,
        )
    }

    private class PlaneInfo(val id: Int, val type: Long, val possibleCrtcs: Int, val currentCrtc: Int)

    private fun planes(): List<PlaneInfo> {
        val fd = device.fd
        val resources = Drm.drmModeGetPlaneResources(fd)
        if (resources == MemorySegment.NULL) return emptyList()
        try {
            val count = _drmModePlaneRes.count_planes(resources)
            val ids = _drmModePlaneRes.planes(resources).reinterpret(count * 4L)
            return (0 until count).mapNotNull { i ->
                val id = ids.getAtIndex(JAVA_INT, i.toLong())
                val plane = Drm.drmModeGetPlane(fd, id)
                if (plane == MemorySegment.NULL) return@mapNotNull null
                try {
                    val props = device.properties(id, Drm.DRM_MODE_OBJECT_PLANE())
                    PlaneInfo(id, props["type"]?.value ?: -1, _drmModePlane.possible_crtcs(plane), props["CRTC_ID"]?.value?.toInt() ?: 0)
                } finally {
                    Drm.drmModeFreePlane(plane)
                }
            }
        } finally {
            Drm.drmModeFreePlaneResources(resources)
        }
    }

    private fun usedPlanes(): Set<Int> = active.values.map { it.planeId }.toSet()

    // --- hotplug ------------------------------------------------------------------------

    // The kernel announces "something about the monitors changed" through udev.
    private fun watchHotplug() {
        if (udev == MemorySegment.NULL) return
        hotplug = Input.udev_monitor_new_from_netlink(udev, arena.allocateFrom("udev"))
        if (hotplug == MemorySegment.NULL) return
        Input.udev_monitor_filter_add_match_subsystem_devtype(hotplug, arena.allocateFrom("drm"), MemorySegment.NULL)
        Input.udev_monitor_enable_receiving(hotplug)
        val fd = Fd.borrow(Input.udev_monitor_get_fd(hotplug))
        hotplugFd = fd
        val ours = GpuDevices.number(device.path)
        val hotplugKey = arena.allocateFrom("HOTPLUG")
        loop.add(fd) {
            while (true) {
                val event = Input.udev_monitor_receive_device(hotplug)
                if (event == MemorySegment.NULL) break
                val isOurs = Input.udev_device_get_devnum(event) == ours
                val isHotplug = CStrings.borrowed(Input.udev_device_get_property_value(event, hotplugKey)) == "1"
                Input.udev_device_unref(event)
                if (isOurs && isHotplug) scheduleRescan()
            }
        }
    }

    // A loose cable sends many events in a row: wait until things calm down, then look once.
    private fun scheduleRescan() {
        rescanTimer?.cancel()
        rescanTimer = loop.after(HOTPLUG_SETTLE_MILLIS) {
            rescanTimer = null
            if (session.active) rescan()
        }
    }

    override fun close() {
        rescanTimer?.cancel()
        for (connectorId in active.keys.toList()) {
            val output = active.remove(connectorId) ?: continue
            output.close()
        }
        hotplugFd?.let(loop::remove)
        if (hotplug != MemorySegment.NULL) Input.udev_monitor_unref(hotplug)
        if (udev != MemorySegment.NULL) Input.udev_unref(udev)
        input.close()
        renderer.close()
        device.close()
        session.close()
        arena.close()
    }

    companion object {
        private const val HOTPLUG_SETTLE_MILLIS = 500L

        /**
         * Opens the session, the GPU and the input devices. Throws with a readable message
         * if that is impossible (e.g. not started from a text console).
         */
        fun open(
            loop: EventLoop,
            createRenderer: (Fd) -> Renderer = { GlesRenderer.createOn(it) },
        ): DrmBackend {
            val session = Session.open(loop)
            try {
                var lastError: Exception? = null
                for (card in gpuCandidates()) {
                    val device = try {
                        DrmDevice(loop, session, card)
                    } catch (e: Exception) {
                        lastError = e
                        continue
                    }
                    try {
                        val renderer = createRenderer(Fd.borrow(device.fd))
                        return DrmBackend(loop, session, device, renderer)
                    } catch (e: Exception) {
                        device.close()
                        lastError = e
                    }
                }
                throw IllegalStateException("No usable GPU found in /dev/dri", lastError)
            } catch (e: Exception) {
                session.close()
                throw e
            }
        }

        /** /dev/dri/card* — the one the firmware used for the boot screen first. */
        fun gpuCandidates(): List<Path> {
            val cards = Files.list(Path.of("/dev/dri")).use { files ->
                files.filter { it.fileName.toString().matches(Regex("card\\d+")) }.sorted().toList()
            }
            return cards.sortedByDescending { card ->
                runCatching { Files.readString(Path.of("/sys/class/drm/${card.fileName}/device/boot_vga")).trim() == "1" }
                    .getOrDefault(false)
            }
        }

        /** Linux's names for connector types (from drm_mode.h). */
        fun connectorTypeName(type: Int): String = when (type) {
            1 -> "VGA"; 2 -> "DVI-I"; 3 -> "DVI-D"; 4 -> "DVI-A"; 5 -> "Composite"; 6 -> "SVIDEO"
            7 -> "LVDS"; 8 -> "Component"; 9 -> "DIN"; 10 -> "DP"; 11 -> "HDMI-A"; 12 -> "HDMI-B"
            13 -> "TV"; 14 -> "eDP"; 15 -> "Virtual"; 16 -> "DSI"; 17 -> "DPI"; 18 -> "Writeback"
            19 -> "SPI"; 20 -> "USB"
            else -> "Unknown"
        }
    }
}
