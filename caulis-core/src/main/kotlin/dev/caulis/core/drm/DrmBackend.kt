package dev.caulis.core.drm

import dev.caulis.log.Logger
import dev.caulis.core.libinput.InputDevices
import dev.caulis.core.session.Session
import dev.caulis.event.EventLoop
import dev.caulis.ffi.CStrings
import dev.caulis.ffi.Device
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

    /** Whether the monitor at a connector (e.g. "HDMI-A-1") is used at all. */
    var enabled: (name: String) -> Boolean = { true }

    /**
     * Monitors were plugged in or out (each one plugged in now, used or not): called before
     * they are turned on, so [enabled] and [preferredMode] can answer for the new set.
     */
    var onPluggedChanged: (List<Plugged>) -> Unit = {}
    private var pluggedBefore: Set<String>? = null

    /** The mode a monitor should use first, if it has it (else its preferred one). */
    var preferredMode: (name: String, modes: List<DrmOutput.Mode>) -> DrmOutput.Mode? = { _, _ -> null }

    private val log = dev.caulis.log.Logger("drm")
    private val arena = Arena.ofShared()
    private val udev: MemorySegment = Input.udev_new()
    private var hotplug: MemorySegment = MemorySegment.NULL
    private var hotplugFd: Fd? = null
    private var rescanTimer: EventLoop.Timer? = null

    init {
        session.onActiveChanged { nowActive ->
            // (Another program may have changed the monitors meanwhile.)
            if (nowActive) wake()
        }
        watchHotplug()
    }

    /**
     * The monitors are set up anew and drawn whole: back on this console, or after the
     * computer slept (a monitor may have been reset, unplugged or plugged in meanwhile).
     */
    fun wake() {
        // The monitors we had first (whatever the rescan meets), then look again.
        active.values.toList().forEach { output ->
            try {
                output.resume()
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) throw e
                log.error("${output.name} could not resume: $e", e)
            }
        }
        rescan()
    }

    /** Finds the connected monitors and turns them on. Set the callbacks before calling this. */
    fun start() = rescan()

    // Monitors a program or the user switched off while running (by name).
    private val switchedOff = HashSet<String>()

    /** A monitor plugged in, used or not. */
    class Plugged(val name: String, val edid: Edid?, val widthMm: Int, val heightMm: Int, val modes: List<DrmOutput.Mode>, val output: DrmOutput?)

    /** Every monitor plugged in now, used or not. */
    fun plugged(): List<Plugged> = connectedConnectors().map { (id, info) -> Plugged(info.name, info.edid, info.widthMm, info.heightMm, info.modes, active[id]) }

    /** Uses the monitor [name] again, or stops using it (it stays plugged in, dark); false if it is not plugged in. */
    fun setEnabled(name: String, on: Boolean): Boolean {
        val connected = connectedConnectors().entries.firstOrNull { it.value.name == name } ?: return false
        if (on) {
            switchedOff -= name
            rescan()
            return connected.key in active
        }
        switchedOff += name
        removeOutput(connected.key)
        return true
    }

    // --- monitors -----------------------------------------------------------------------

    private fun rescan() {
        val connected = connectedConnectors()
        val names = connected.values.map { "${it.name} ${it.edid?.make}/${it.edid?.model}/${it.edid?.serial}" }.toSet()
        if (names != pluggedBefore) {
            pluggedBefore = names
            onPluggedChanged(connected.map { (id, info) -> Plugged(info.name, info.edid, info.widthMm, info.heightMm, info.modes, active[id]) })
        }
        // Gone (or a different monitor in the same socket): remove.
        for ((connectorId, output) in active.toList()) {
            val now = connected[connectorId]
            // Gone, another monitor, or the same one with other modes (its menu switched DP 1.2/1.4).
            if (now == null || now.edid != output.edid || now.modes.map { it.toString() } != output.modes.map { it.toString() }) {
                removeOutput(connectorId)
            } else if (now.linkBad) {
                log.info("Monitor ${output.name}: the link failed; setting it up anew")
                output.relink()
            }
        }
        // New: find a CRTC and plane for it and turn it on.
        for ((connectorId, info) in connected) {
            if (connectorId in active) continue
            if (!enabled(info.name) || info.name in switchedOff) {
                log.info("Monitor ${info.name} is turned off by the settings")
                continue
            }
            val output = createOutput(info) ?: continue
            if (!output.start(preferredMode(info.name, info.modes))) {
                log.warn("No mode of ${info.name} works; leaving it off")
                output.close()
                continue
            }
            active[connectorId] = output
            log.info("Monitor ${output.name} (${output.edid?.model ?: "unknown"}) at ${output.mode}")
            onOutputAdded(output)
        }
        switchOffOthers()
    }

    // CRTCs, planes and connectors we do not drive are switched off: what the firmware or
    // another session left must not stay lit (and hold a CRTC).
    private fun switchOffOthers() {
        if (!session.active) return
        val resources = Drm.drmModeGetResources(device.fd)
        if (resources == MemorySegment.NULL) return
        val (crtcs, connectors) = try {
            val crtcCount = _drmModeRes.count_crtcs(resources)
            val crtcIds = _drmModeRes.crtcs(resources).reinterpret(crtcCount * 4L)
            val connectorCount = _drmModeRes.count_connectors(resources)
            val connectorIds = _drmModeRes.connectors(resources).reinterpret(connectorCount * 4L)
            (0 until crtcCount).map { crtcIds.getAtIndex(JAVA_INT, it.toLong()) } to
                (0 until connectorCount).map { connectorIds.getAtIndex(JAVA_INT, it.toLong()) }
        } finally {
            Drm.drmModeFreeResources(resources)
        }
        val ourCrtcs = active.values.map { it.crtcId }.toSet()
        val ourPlanes = usedPlanes()
        val result = device.commit(Drm.DRM_MODE_ATOMIC_ALLOW_MODESET()) { request ->
            for (connector in connectors) {
                if (connector in active) continue
                val props = device.properties(connector, Drm.DRM_MODE_OBJECT_CONNECTOR())
                if ((props["CRTC_ID"]?.value ?: 0L) != 0L) request.set(connector, props, "CRTC_ID", 0)
            }
            for (crtc in crtcs) {
                if (crtc in ourCrtcs) continue
                val props = device.properties(crtc, Drm.DRM_MODE_OBJECT_CRTC())
                if ((props["ACTIVE"]?.value ?: 0L) == 0L) continue
                request.set(crtc, props, "ACTIVE", 0)
                request.set(crtc, props, "MODE_ID", 0)
            }
            for (plane in planes()) {
                if (plane.id in ourPlanes || plane.currentCrtc == 0 || plane.currentCrtc in ourCrtcs) continue
                val props = device.properties(plane.id, Drm.DRM_MODE_OBJECT_PLANE())
                request.set(plane.id, props, "FB_ID", 0)
                request.set(plane.id, props, "CRTC_ID", 0)
            }
        }
        if (result != 0) log.info { "Switching off what others left lit was refused ($result)" }
    }

    private fun removeOutput(connectorId: Int) {
        val output = active.remove(connectorId) ?: return
        log.info("Monitor ${output.name} disconnected")
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
        /** The link failed (DisplayPort training, a dock): it needs a mode set. */
        val linkBad: Boolean = false,
        /** The CRTC it is on now (the firmware's, another session's), 0: none. */
        val currentCrtc: Int = 0,
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
                    val props = device.properties(id, Drm.DRM_MODE_OBJECT_CONNECTOR())
                    // A VR headset is not a monitor of the desktop (it is lent to its program).
                    if (props["non-desktop"]?.value == 1L) continue
                    val edidBlob = props["EDID"]?.value ?: 0
                    result[id] = ConnectorInfo(
                        id = id,
                        name = "${connectorTypeName(_drmModeConnector.connector_type(connector))}-${_drmModeConnector.connector_type_id(connector)}",
                        edid = device.blob(edidBlob)?.let(Edid::parse),
                        widthMm = _drmModeConnector.mmWidth(connector),
                        heightMm = _drmModeConnector.mmHeight(connector),
                        modes = modes,
                        possibleCrtcs = Drm.drmModeConnectorGetPossibleCrtcs(fd, connector),
                        linkBad = props["link-status"]?.value == LINK_STATUS_BAD,
                        currentCrtc = props["CRTC_ID"]?.value?.toInt() ?: 0,
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
            var vtotal = _drmModeModeInfo.vtotal(info).toLong() and 0xffff
            // As wlroots: interlaced modes refresh twice, double-scanned ones half as often; rounded.
            val flags = _drmModeModeInfo.flags(info)
            if (flags and DRM_MODE_FLAG_INTERLACE != 0) vtotal /= 2
            if (flags and DRM_MODE_FLAG_DBLSCAN != 0) vtotal *= 2
            val vscan = (_drmModeModeInfo.vscan(info).toInt() and 0xffff).toLong()
            if (vscan > 1) vtotal *= vscan
            val refresh = if (htotal > 0 && vtotal > 0) ((clock * 1_000_000 + htotal * vtotal / 2) / (htotal * vtotal)).toInt() else _drmModeModeInfo.vrefresh(info) * 1000
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
        fun usable(i: Int) = info.possibleCrtcs and (1 shl i) != 0 && crtcIds[i] !in used
        // The CRTC it is on already, if it is free (no flicker, nothing to take from another
        // monitor); else the first free one it can use.
        // A free one no other connector still shows on (one lit by the firmware for a monitor we
        // do not use would refuse the first picture) before one that is merely ours to take.
        val heldByOthers = connectedConnectors().values.filter { it.id != info.id }.map { it.currentCrtc }.toSet()
        val index = crtcIds.indexOf(info.currentCrtc).takeIf { it >= 0 && usable(it) }
            ?: crtcIds.indices.firstOrNull { usable(it) && crtcIds[it] !in heldByOthers }
            ?: crtcIds.indices.firstOrNull(::usable)
        if (index == null) {
            log.warn("No free display controller (CRTC) for ${info.name}")
            return null
        }
        val crtc = crtcIds[index]
        val planes = planes()
        val primary = planes.firstOrNull { it.type == Drm.DRM_PLANE_TYPE_PRIMARY().toLong() && it.possibleCrtcs and (1 shl index) != 0 && it.id !in usedPlanes() }
            ?: return null.also { log.warn("No primary plane for ${info.name}") }
        // A cursor plane of its own, if the display has one for this CRTC (the pointer then moves without frames).
        val cursorPlane = planes.firstOrNull { it.type == Drm.DRM_PLANE_TYPE_CURSOR().toLong() && it.possibleCrtcs and (1 shl index) != 0 && it.id !in usedPlanes() }?.id ?: 0
        // Planes still on this CRTC (another session's cursor or overlays): asked at every mode set.
        val stray = { planes().filter { it.currentCrtc == crtc && it.id != primary.id && it.id != cursorPlane }.map { it.id } }
        return DrmOutput(
            loop, device, renderer, { session.active }, info.id, crtc, primary.id, info.name, info.edid,
            info.widthMm, info.heightMm, info.modes, stray, cursorPlane,
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

    private fun usedPlanes(): Set<Int> = active.values.flatMap { listOf(it.planeId, it.cursorPlaneId) }.filter { it != 0 }.toSet()

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
        private const val LINK_STATUS_BAD = 1L
        private const val DRM_MODE_FLAG_INTERLACE = 1 shl 4
        private const val DRM_MODE_FLAG_DBLSCAN = 1 shl 5

        /**
         * Opens the session, the GPU and the input devices. Throws with a readable message
         * if that is impossible (e.g. not started from a text console).
         */
        fun open(
            loop: EventLoop,
            createRenderer: (Fd) -> Renderer = { GlesRenderer.createOn(it, owned = true) },
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
                        // The renderer (GBM, EGL) gets a descriptor of its own for the same GPU, as
                        // in wlroots: buffer handles are counted per descriptor, so the display's
                        // imports (direct scanout) never close a handle the GPU driver still uses.
                        val own = rendererDevice(card, device)
                        device.sharedWithRenderer = own == null
                        val renderer = try {
                            createRenderer(own ?: Fd.borrow(device.fd))
                        } catch (e: Exception) {
                            own?.close()
                            throw e
                        }
                        // Said once: on the processor everything is slow (the pointer lags, animations stutter).
                        val about = renderer.description
                        if (Regex("llvmpipe|softpipe|swrast|software", RegexOption.IGNORE_CASE).containsMatchIn(about)) {
                            Logger("render").warn("Drawing on the processor ($about): the GPU's driver did not start, everything will be slow")
                        } else {
                            Logger("render").info { "Drawing with $about" }
                        }
                        return try {
                            DrmBackend(loop, session, device, renderer)
                        } catch (e: Exception) {
                            renderer.close()
                            throw e
                        }
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

        // A second descriptor of the GPU behind [device]: its card node opened once more, else
        // its render node; null if neither opens (then the renderer shares the display's).
        private fun rendererDevice(card: Path, device: DrmDevice): Fd? {
            val log = dev.caulis.log.Logger("drm")
            try {
                return Device.open(card).also { log.info { "Drawing on a descriptor of its own ($card)" } }
            } catch (e: Exception) {
                // (No rights to the card node itself: seatd without the video group.)
            }
            val renderNode = CStrings.takeOwned(Drm.drmGetRenderDeviceNameFromFd(device.fd))
            if (renderNode != null) {
                try {
                    return Device.open(Path.of(renderNode)).also { log.info { "Drawing on $renderNode" } }
                } catch (e: Exception) {
                    log.warn("$renderNode cannot be opened: ${e.message}")
                }
            }
            log.warn("Drawing on the display's own descriptor (buffer handles of direct scanout are kept then)")
            return null
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
