package dev.caulis.core.drm

import dev.caulis.core.DmabufBuffer
import dev.caulis.event.EventLoop
import dev.caulis.ffi.c.drm.Drm
import dev.caulis.render.Dmabuf
import dev.caulis.render.GpuBuffer
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_BYTE

/**
 * One monitor driven by Caulis: a connector (the socket the cable is in), a CRTC (the part
 * of the GPU that sends pictures to it) and its primary plane (the full-screen layer).
 *
 * Pictures are drawn into one of two buffers and then shown with a page flip; when the
 * monitor has started showing it, [onFrame] asks for the next one. The monitor's rhythm
 * (60, 144, ... Hz) paces everything.
 */
class DrmOutput internal constructor(
    internal val loop: EventLoop,
    internal val device: DrmDevice,
    internal val renderer: Renderer,
    internal val isSessionActive: () -> Boolean,
    internal val connectorId: Int,
    internal val crtcId: Int,
    internal val planeId: Int,
    /** Connector name as Linux calls it, e.g. "eDP-1", "HDMI-A-1", "DP-2". */
    val name: String,
    /** What the monitor told about itself, if anything. */
    val edid: Edid?,
    val physicalWidthMm: Int,
    val physicalHeightMm: Int,
    /** Modes the monitor supports, best first. */
    val modes: List<Mode>,
    // Planes of other programs attached to our CRTC (cursor, overlays) — asked anew at each
    // mode set (another session may have left some): turned off.
    private val strayPlanes: () -> List<Int>,
    /** Its cursor plane (0: none). */
    internal val cursorPlaneId: Int = 0,
) : AutoCloseable {
    internal val flips = DrmFlips(this)
    internal val cursorSide = DrmCursorPlane(this)
    internal val gammaSide = DrmGamma(this)
    internal val log = dev.caulis.log.Logger("drm")

    /** A resolution and refresh rate. [info] is the kernel's description of it. */
    class Mode internal constructor(
        val width: Int,
        val height: Int,
        val refreshMillihertz: Int,
        val preferred: Boolean,
        internal val info: ByteArray,
    ) {
        override fun toString() = "${width}x$height@${"%.2f".format(refreshMillihertz / 1000.0)}"

        // The same mode is the same timings (a mode read again after a rescan is equal).
        override fun equals(other: Any?) = other is Mode && info.contentEquals(other.info)
        override fun hashCode() = info.contentHashCode()
    }

    /** The mode in use (null until [start] found one that works). */
    @Volatile
    var mode: Mode? = null
        private set

    val width: Int get() = mode?.width ?: 0
    val height: Int get() = mode?.height ?: 0

    /** Called when it is time to draw the next picture: draw it with [present]. */
    var onFrame: () -> Unit = {}

    /**
     * Called when a picture from [present] reached the screen: the moment of that refresh
     * (`nanos`, monotonic clock), the time between refreshes and the monitor's frame number.
     */
    var onPresented: (nanos: Long, refreshNanos: Long, sequence: Long, zeroCopy: Boolean) -> Unit = { _, _, _, _ -> }

    /** Time between two refreshes of the current mode, in nanoseconds. */
    val refreshNanos: Long get() = 1_000_000_000_000L / (mode?.refreshMillihertz ?: 60_000).coerceAtLeast(1_000)

    private val connectorProps = device.properties(connectorId, Drm.DRM_MODE_OBJECT_CONNECTOR())
    internal val crtcProps = device.properties(crtcId, Drm.DRM_MODE_OBJECT_CRTC())
    internal val planeProps = device.properties(planeId, Drm.DRM_MODE_OBJECT_PLANE())

    internal class Slot(val buffer: GpuBuffer, val framebuffer: Int) {
        /** The frame this buffer was last drawn in (0 = never). */
        var drawnInFrame = 0L
    }

    internal var frameNumber = 0L
    internal var lastFlipNanos = 0L

    internal var slots: List<Slot> = emptyList()
    internal var modeBlob = 0
    internal var shown: Slot? = null
    internal var queued: Slot? = null

    // A program's own buffer on screen instead of one of ours (direct scanout).
    internal var shownDirect: DmabufBuffer? = null
    internal var queuedDirect: DmabufBuffer? = null

    // A picture (or only the cursor's new place) is on its way to the screen; the next one waits for the flip.
    internal val flipPending: Boolean get() = queued != null || queuedDirect != null || cursorSide.cursorOnlyPending
    @Volatile internal var needsModeset = true

    // Each commit has a serial that comes back with its flip: a flip of an old commit (from
    // before switching terminals) is not taken for the one awaited.
    internal var commitSerial = 0L
    internal var awaitedSerial = -1L

    // A flip that never comes (suspend, a GPU reset) must not freeze the screen: after a
    // while the picture is given up and the mode set anew.
    internal var flipWatchdog: dev.caulis.event.EventLoop.Timer? = null

    // A picture was refused: the next one is drawn whole (what changed was used up).
    internal var redrawAll = false
    internal var modesetFailures = 0

    // The previous mode's buffers and mode: kept until the new mode is on screen.
    private var retired: List<Slot> = emptyList()
    private var retiredBlob = 0

    // Mode sets also set our settings (max bpc, adaptive sync) and reset what other programs
    // may have left (colour space, HDR, ...): each property the driver refuses is left out from
    // then on (logged by name), the others still apply.
    private val refusedProperties = HashSet<String>()

    // maxBpc or adaptiveSync changed since the last tested mode set.
    internal var extrasChanged = false

    // The properties the last tested mode set had (the next real one sets the same).
    internal var extrasInUse: List<Extra> = emptyList()

    // One such property: [value] for [property] of the object [objectId].
    internal class Extra(val property: String, val objectId: Int, val props: Map<String, DrmDevice.Property>, val value: Long)

    // The properties a mode set sets besides the mode (those the driver has and did not refuse).
    internal fun extras(): List<Extra> {
        val list = ArrayList<Extra>()
        connectorProps["max bpc"]?.let { prop ->
            // (Within what the connector takes.)
            val bpc = prop.range?.let { maxBpc.toLong().coerceIn(it) } ?: maxBpc.toLong()
            if (bpc != maxBpc.toLong()) log.info { "$name: max bpc $maxBpc is out of the connector's range ${prop.range}: $bpc" }
            list += Extra("max bpc", connectorId, connectorProps, bpc)
        }
        // What another session (or the firmware) may have left: no HDR, standard colours.
        for (property in listOf("Colorspace", "HDR_OUTPUT_METADATA", "content type")) list += Extra(property, connectorId, connectorProps, 0)
        // Adaptive sync only where the monitor has it (as the kernel says now).
        val capable = device.properties(connectorId, Drm.DRM_MODE_OBJECT_CONNECTOR())["vrr_capable"]?.value == 1L
        if (adaptiveSync && !capable) log.info { "$name: adaptive sync is on, but the monitor does not have it" }
        list += Extra("VRR_ENABLED", crtcId, crtcProps, if (adaptiveSync && capable) 1 else 0)
        for (property in listOf("CTM", "DEGAMMA_LUT")) list += Extra(property, crtcId, crtcProps, 0)
        return list.filter { it.props.containsKey(it.property) && it.property !in refusedProperties }
    }

    // The [extras] the driver takes along with the mode, as [test] (a TEST_ONLY commit with
    // them) finds: all, or — if the mode works without them — those it takes one by one.
    internal fun workingExtras(extras: List<Extra>, test: (List<Extra>) -> Int): List<Extra> =
        takenAlong(extras, { test(it) == 0 }) { extra, alone ->
            refusedProperties += extra.property
            if (alone) log.warn("$name: the driver refuses ${extra.property} = ${extra.value}; left as it is")
            else log.warn("$name: the driver refuses ${extra.property} along with the others; left as it is")
        }

    init {
        device.onFlip(crtcId) { nanos, sequence, serial -> flips.flipped(nanos, sequence, serial) }
    }

    // (Volatile: the input thread moves the cursor too — see moveFromAnyThread.)
    @Volatile internal var cursorX = 0
    @Volatile internal var cursorY = 0
    @Volatile internal var cursorVisible = false

    /** The cursor plane (null: none, or the display refused it). */
    val hardwareCursor: dev.caulis.core.HardwareCursor? get() = if (cursorPlaneId == 0 || cursorSide.cursorRefused) null else cursorSide.cursorPlane

    // The legacy cursor move works here (false once the driver refused it: then only the
    // compositor's thread moves the cursor, with its commits). Closed: never again.
    @Volatile internal var quickMoves = true
    @Volatile internal var closed = false

    /**
     * Turns the monitor on with the first mode that the kernel accepts (tested before
     * applying, so a mode that does not work never reaches the screen). Returns false if
     * none works; the monitor then stays off and everything else continues.
     */
    internal fun start(first: Mode? = null): Boolean {
        for (candidate in (listOfNotNull(first) + modes.take(MAX_MODES_TO_TRY)).distinct()) {
            if (tryMode(candidate)) {
                mode = candidate
                needsModeset = true
                flips.scheduleFrame(0)
                return true
            }
        }
        return false
    }

    private fun tryMode(candidate: Mode): Boolean {
        // (What was made is freed again if a later part fails.)
        val newSlots = ArrayList<Slot>()
        val blob = try {
            repeat(BUFFERS) {
                val buffer = renderer.createBuffer(candidate.width, candidate.height, Dmabuf.XRGB8888, emptyList(), scanout = true)
                try {
                    newSlots += Slot(buffer, device.addFramebuffer(buffer))
                } catch (e: Exception) {
                    buffer.close()
                    throw e
                }
            }
            Arena.ofConfined().use { temp -> device.createBlob(temp.allocateFrom(JAVA_BYTE, *candidate.info)) }
        } catch (e: Exception) {
            log.warn("$name: buffers for $candidate could not be made: ${e.message}")
            newSlots.forEach(::freeSlot)
            return false
        }
        fun test(extras: List<Extra>) = device.commit(Drm.DRM_MODE_ATOMIC_TEST_ONLY() or Drm.DRM_MODE_ATOMIC_ALLOW_MODESET()) { request ->
            fullState(request, blob, newSlots[0], candidate, extras)
        }
        // Some drivers refuse a property (a max bpc out of range): the mode goes without it.
        val extras = workingExtras(extras(), ::test)
        val result = test(extras)
        if (result != 0) {
            log.info { "$name: $candidate refused (${errno(result)})" }
            device.destroyBlob(blob)
            newSlots.forEach(::freeSlot)
            return false
        }
        // The buffers on screen now stay until the new mode is: removing them first would
        // switch the screen off in between.
        freeRetired()
        retired = slots
        retiredBlob = modeBlob
        slots = newSlots
        modeBlob = blob
        modesetFailures = 0
        extrasInUse = extras
        return true
    }

    internal fun freeRetired() {
        retired.forEach(::freeSlot)
        retired = emptyList()
        if (retiredBlob != 0) device.destroyBlob(retiredBlob)
        retiredBlob = 0
    }

    // Everything the monitor needs: connector -> CRTC -> mode, plane shows the buffer full screen.
    internal fun fullState(request: DrmDevice.AtomicRequest, blob: Int, slot: Slot, mode: Mode, extras: List<Extra>) {
        request.set(connectorId, connectorProps, "CRTC_ID", crtcId.toLong())
        request.set(crtcId, crtcProps, "MODE_ID", blob.toLong())
        request.set(crtcId, crtcProps, "ACTIVE", 1)
        // A link that failed (a dock, a KVM) is tried again with this mode set.
        request.setIfPresent(connectorId, connectorProps, "link-status", LINK_STATUS_GOOD)
        // Our settings and the reset of what others left (see extras).
        for (extra in extras) request.setIfPresent(extra.objectId, extra.props, extra.property, extra.value)
        // Our gamma table (a night light), also after switching terminals.
        request.setIfPresent(crtcId, crtcProps, "GAMMA_LUT", (gammaSide.pendingGamma ?: gammaSide.gammaBlob).toLong())
        flips.showOnPlane(request, slot, mode)
        cursorSide.cursorState(request)
        for (plane in strayPlanes()) {
            val props = device.properties(plane, Drm.DRM_MODE_OBJECT_PLANE())
            request.set(plane, props, "FB_ID", 0)
            request.set(plane, props, "CRTC_ID", 0)
        }
    }

    /** True if the monitor must get a whole picture even if nothing changed (set up, or a picture was refused). */
    val needsPicture: Boolean get() = needsModeset || redrawAll

    /**
     * Draws the next picture with [draw] and sends it to the monitor. [draw] also gets how
     * many frames old the buffer's picture is (1 = previous frame, 0 = unknown: draw
     * everything). Does nothing while another virtual terminal is shown, the screen is off
     * or a picture is still on its way. Returns true if a picture was drawn and sent.
     *
     * If [draw] fails, the exception goes on to the caller; this buffer counts as never
     * drawn, so the next picture is drawn whole (no half-drawn parts stay on screen).
     */
    fun present(draw: (target: GpuTarget, age: Int) -> Unit): Boolean = flips.present(draw)

    // Caulis 0.1.1's present (returning nothing): code built against it calls it.
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    @JvmName("present")
    fun presentWithoutAnswer(draw: (target: GpuTarget, age: Int) -> Unit) = flips.presentWithoutAnswer(draw)

    /**
     * Shows a program's own GPU buffer directly, without drawing anything (for a fullscreen
     * game or video). The kernel checks first whether the display can show it; returns
     * false if not — then draw normally with [present]. The program gets the buffer back
     * when the monitor has moved on to the next picture.
     */
    fun presentDirect(buffer: DmabufBuffer): Boolean = flips.presentDirect(buffer)

    /**
     * Asks for an [onFrame] call at the monitor's next refresh, e.g. because something
     * changed while the screen was idle. Cheap to call often.
     */
    fun requestFrame() = flips.requestFrame()

    /** Its link failed (DisplayPort training, a dock): set the mode again and redraw. */
    internal fun relink() = resume()

    /** After returning from another virtual terminal: set the mode again and redraw. */
    internal fun resume() {
        needsModeset = true
        awaitedSerial = -1 // flips from before belong to no commit of now
        flipWatchdog?.cancel()
        flipWatchdog = null
        cursorSide.cursorOnlyPending = false
        queued = null
        queuedDirect?.stopShowing() // that flip never happened
        queuedDirect = null
        slots.forEach { it.drawnInFrame = 0 } // draw everything again
        flips.scheduleFrame(0)
    }

    /**
     * The most bits per colour the link to the monitor may use ("max bpc"; 8 by default: more
     * makes fast modes fail on links without compression). Applied with a mode set.
     */
    var maxBpc: Int = 8
        set(value) {
            require(value in 6..16) { "maxBpc must be 6..16, not $value" }
            if (value == field) return
            field = value
            log.info { "$name: at most $value bits per colour (with the next mode set)" }
            refusedProperties -= "max bpc" // (another value may be taken)
            extrasChanged = true
            needsModeset = true
            if (mode != null) flips.scheduleFrame(0)
        }

    /**
     * Variable refresh rate (FreeSync, G-Sync compatible) if the monitor has it (off by
     * default: some monitors flicker with it). Applied with a mode set.
     */
    var adaptiveSync: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            log.info { "$name: adaptive sync ${if (value) "on" else "off"} (with the next mode set)" }
            refusedProperties -= "VRR_ENABLED"
            extrasChanged = true
            needsModeset = true
            if (mode != null) flips.scheduleFrame(0)
        }

    /** False while the screen is switched off (DPMS): nothing is drawn. */
    @Volatile
    var powered = true
        private set

    /** Switches the screen off (the CRTC goes inactive) or on again (a full mode set, then a picture). */
    fun setPower(on: Boolean): Boolean {
        if (on == powered) return true
        if (mode == null) return false
        if (!on) {
            if (isSessionActive()) {
                val result = device.commit(Drm.DRM_MODE_ATOMIC_ALLOW_MODESET()) { request ->
                    request.set(crtcId, crtcProps, "ACTIVE", 0)
                }
                if (result != 0) {
                    log.warn("$name could not be switched off ($result)")
                    return false
                }
            }
            releaseDirect()
            queued = null
            powered = false
        } else {
            powered = true
            needsModeset = true
            slots.forEach { it.drawnInFrame = 0 }
            flips.scheduleFrame(0)
        }
        log.info { "$name ${if (on) "on" else "off"}" }
        return true
    }

    /** Runs the monitor in [wanted] from now on (tested first); false if it does not take it. */
    fun changeMode(wanted: Mode): Boolean {
        if (wanted == mode) return true
        if (!tryMode(wanted)) return false
        mode = wanted
        needsModeset = true
        flips.scheduleFrame(0)
        log.info { "$name: $wanted" }
        return true
    }

    /** Entries of the monitor's gamma table (0: it has none). */
    val gammaSize: Int get() = crtcProps["GAMMA_LUT_SIZE"]?.value?.toInt() ?: 0

    /**
     * Uses the gamma table [red], [green], [blue] ([gammaSize] each); null: the monitor's own.
     * It goes with the next picture (tested now), and stays across mode sets and terminals.
     */
    fun setGamma(red: ShortArray?, green: ShortArray?, blue: ShortArray?): Boolean = gammaSide.setGamma(red, green, blue)

    internal fun errno(result: Int): String = when (-result) {
        16 -> "busy"
        22 -> "invalid"
        28 -> "no room (bandwidth)"
        13 -> "no permission (another session has the screen)"
        else -> "error $result"
    }

    /** Turns the monitor off (it was unplugged, or Caulis stops using it). */
    internal fun stop() {
        if (isSessionActive() && mode != null) {
            device.commit(Drm.DRM_MODE_ATOMIC_ALLOW_MODESET()) { request ->
                request.set(connectorId, connectorProps, "CRTC_ID", 0)
                request.set(crtcId, crtcProps, "MODE_ID", 0)
                request.set(crtcId, crtcProps, "ACTIVE", 0)
                request.set(planeId, planeProps, "FB_ID", 0)
                request.set(planeId, planeProps, "CRTC_ID", 0)
                // Every plane on the CRTC goes too: a plane left showing on a CRTC switched off
                // makes drivers refuse the whole commit (the monitor would stay lit).
                if (cursorPlaneId != 0) {
                    request.set(cursorPlaneId, cursorSide.cursorProps, "FB_ID", 0)
                    request.set(cursorPlaneId, cursorSide.cursorProps, "CRTC_ID", 0)
                }
                for (plane in strayPlanes()) {
                    val props = device.properties(plane, Drm.DRM_MODE_OBJECT_PLANE())
                    request.set(plane, props, "FB_ID", 0)
                    request.set(plane, props, "CRTC_ID", 0)
                }
            }.also { result -> if (result != 0) log.warn("$name could not be switched off (${errno(result)})") }
        }
        mode = null
    }

    internal fun freeSlot(slot: Slot) {
        device.removeFramebuffer(slot.framebuffer)
        slot.buffer.close()
    }

    // Gives programs' buffers back (the monitor stops showing them).
    private fun releaseDirect() {
        flips.lateDirect.forEach { it.stopShowing() }
        flips.lateDirect.clear()
        queuedDirect?.stopShowing()
        queuedDirect = null
        shownDirect?.stopShowing()
        shownDirect = null
    }

    private fun freeBuffers() {
        releaseDirect()
        slots.forEach(::freeSlot)
        slots = emptyList()
        shown = null
        queued = null
        if (modeBlob != 0) device.destroyBlob(modeBlob)
        modeBlob = 0
    }

    override fun close() {
        closed = true
        device.removeFlipHandler(crtcId)
        flipWatchdog?.cancel()
        flips.frameTimer?.cancel()
        flips.frameTimer = null
        freeBuffers()
        freeRetired()
        cursorSide.cursorSlots.forEach(::freeSlot)
        cursorSide.cursorSlots = emptyList()
        gammaSide.pendingGamma?.let { if (it != 0) device.destroyBlob(it) }
        if (gammaSide.gammaBlob != 0) device.destroyBlob(gammaSide.gammaBlob)
    }

}

/**
 * Of [extras] (properties set along with a mode), those [works] takes: all if it takes them
 * together; else — if it takes none of them either, the mode itself fails: all, as they are not
 * at fault — each one it takes alone, and only if those together work too. [refused] hears each
 * one left out (alone: it was refused alone, not only along with the others).
 */
internal fun <T> takenAlong(extras: List<T>, works: (List<T>) -> Boolean, refused: (T, alone: Boolean) -> Unit): List<T> {
    if (extras.isEmpty() || works(extras)) return extras
    if (!works(emptyList())) return extras
    val taken = extras.filter { extra -> works(listOf(extra)).also { if (!it) refused(extra, true) } }
    if (taken.isEmpty() || works(taken)) return taken
    taken.forEach { refused(it, false) }
    return emptyList()
}

/** Two buffers: one on screen (or on its way there), one being drawn — only one picture is ever on its way. */
internal const val BUFFERS = 2
internal const val MAX_MODESET_FAILURES = 5
internal const val FLIP_TIMEOUT_MILLIS = 1000L
internal const val LINK_STATUS_GOOD = 0L
internal const val DRM_CAP_CURSOR_WIDTH = 0x8
internal const val DRM_CAP_CURSOR_HEIGHT = 0x9
internal const val MAX_MODES_TO_TRY = 8
// EPERM, EACCES (another terminal has the screen), EBUSY, EINTR, EAGAIN: try again next time.
internal val TRANSIENT_ERRNOS = setOf(1, 13, 16, 4, 11)
internal const val RETRY_MILLIS = 16L
internal const val ERROR_RETRY_MILLIS = 1000L

// How often frames come while nothing reaches the screen (see dark).
internal const val DARK_HEARTBEAT_MILLIS = 1000L
