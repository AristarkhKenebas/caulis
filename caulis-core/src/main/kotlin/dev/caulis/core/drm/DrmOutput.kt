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
    private val loop: EventLoop,
    private val device: DrmDevice,
    private val renderer: Renderer,
    private val isSessionActive: () -> Boolean,
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
    private val log = dev.caulis.log.Logger("drm")


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
    private val crtcProps = device.properties(crtcId, Drm.DRM_MODE_OBJECT_CRTC())
    private val planeProps = device.properties(planeId, Drm.DRM_MODE_OBJECT_PLANE())

    private class Slot(val buffer: GpuBuffer, val framebuffer: Int) {
        /** The frame this buffer was last drawn in (0 = never). */
        var drawnInFrame = 0L
    }

    private var frameNumber = 0L
    private var lastFlipNanos = 0L

    private var slots: List<Slot> = emptyList()
    private var modeBlob = 0
    private var shown: Slot? = null
    private var queued: Slot? = null

    // A program's own buffer on screen instead of one of ours (direct scanout).
    private var shownDirect: DmabufBuffer? = null
    private var queuedDirect: DmabufBuffer? = null

    // A picture (or only the cursor's new place) is on its way to the screen; the next one waits for the flip.
    private val flipPending: Boolean get() = queued != null || queuedDirect != null || cursorOnlyPending
    private var needsModeset = true

    // Each commit has a serial that comes back with its flip: a flip of an old commit (from
    // before switching terminals) is not taken for the one awaited.
    private var commitSerial = 0L
    private var awaitedSerial = -1L

    // A flip that never comes (suspend, a GPU reset) must not freeze the screen: after a
    // while the picture is given up and the mode set anew.
    private var flipWatchdog: dev.caulis.event.EventLoop.Timer? = null

    // A picture was refused: the next one is drawn whole (what changed was used up).
    private var redrawAll = false
    private var modesetFailures = 0

    // The previous mode's buffers and mode: kept until the new mode is on screen.
    private var retired: List<Slot> = emptyList()
    private var retiredBlob = 0

    // Mode sets also set our settings (max bpc, adaptive sync) and reset what other programs
    // may have left (colour space, HDR, ...): each property the driver refuses is left out from
    // then on (logged by name), the others still apply.
    private val refusedProperties = HashSet<String>()

    // maxBpc or adaptiveSync changed since the last tested mode set.
    private var extrasChanged = false

    // The properties the last tested mode set had (the next real one sets the same).
    private var extrasInUse: List<Extra> = emptyList()

    // One such property: [value] for [property] of the object [objectId].
    private class Extra(val property: String, val objectId: Int, val props: Map<String, DrmDevice.Property>, val value: Long)

    // The properties a mode set sets besides the mode (those the driver has and did not refuse).
    private fun extras(): List<Extra> {
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
    private fun workingExtras(extras: List<Extra>, test: (List<Extra>) -> Int): List<Extra> =
        takenAlong(extras, { test(it) == 0 }) { extra, alone ->
            refusedProperties += extra.property
            if (alone) log.warn("$name: the driver refuses ${extra.property} = ${extra.value}; left as it is")
            else log.warn("$name: the driver refuses ${extra.property} along with the others; left as it is")
        }

    init {
        device.onFlip(crtcId) { nanos, sequence, serial -> flipped(nanos, sequence, serial) }
    }

    // --- the cursor plane -------------------------------------------------------------------

    private val cursorProps = if (cursorPlaneId != 0) device.properties(cursorPlaneId, Drm.DRM_MODE_OBJECT_PLANE()) else emptyMap()

    // Two pictures: one shown, one to draw the next into.
    private var cursorSlots: List<Slot> = emptyList()
    private var cursorFront = 0
    private var cursorX = 0
    private var cursorY = 0
    private var cursorVisible = false
    // The cursor's state changed and is not on screen yet.
    private var cursorDirty = false
    // Only the cursor changed: a commit of its own is on its way.
    private var cursorOnlyPending = false
    // The display refused the cursor plane: the cursor goes into the frames from now on.
    private var cursorRefused = false
    // The display took the cursor plane once (before that, mode sets leave it out).
    private var cursorTested = false

    /** The cursor plane (null: none, or the display refused it). */
    val hardwareCursor: dev.caulis.core.HardwareCursor? get() = if (cursorPlaneId == 0 || cursorRefused) null else cursorPlane

    private val cursorPlane = object : dev.caulis.core.HardwareCursor {
        override val width: Int = device.capability(DRM_CAP_CURSOR_WIDTH).takeIf { it in 16..512 }?.toInt() ?: 64
        override val height: Int = device.capability(DRM_CAP_CURSOR_HEIGHT).takeIf { it in 16..512 }?.toInt() ?: 64

        override fun show(x: Int, y: Int, draw: ((GpuTarget) -> Unit)?): Boolean {
            if (cursorRefused) return false
            if (draw != null) {
                if (cursorSlots.isEmpty() && !makeCursorSlots(width, height)) return false
                val back = cursorSlots[1 - cursorFront]
                draw(back.buffer)
                renderer.end()
                cursorFront = 1 - cursorFront
            } else if (cursorSlots.isEmpty()) {
                return false
            }
            if (draw == null && cursorVisible && x == cursorX && y == cursorY) return true
            if (!cursorTested) {
                // Tested once the monitor runs (a display that cannot do it falls back for good);
                // until then the cursor is drawn into the pictures.
                if (needsModeset || !isSessionActive() || mode == null) return false
                cursorX = x
                cursorY = y
                cursorVisible = true
                if (!testCursor()) {
                    log.info { "$name: the cursor plane is refused; the cursor is drawn into the pictures" }
                    cursorRefused = true
                    cursorVisible = false
                    return false
                }
                cursorTested = true
                log.info { "$name: the cursor is on its own plane (${width}x$height)" }
            }
            cursorX = x
            cursorY = y
            cursorVisible = true
            cursorMoved()
            return true
        }

        override fun hide() {
            if (!cursorVisible) return
            cursorVisible = false
            cursorMoved()
        }
    }

    private fun makeCursorSlots(width: Int, height: Int): Boolean {
        val made = ArrayList<Slot>()
        try {
            repeat(2) {
                // Plain rows: what cursor planes take everywhere.
                val buffer = renderer.createBuffer(width, height, Dmabuf.ARGB8888, listOf(Dmabuf.MODIFIER_LINEAR), scanout = true)
                try {
                    made += Slot(buffer, device.addFramebuffer(buffer))
                } catch (e: Exception) {
                    buffer.close()
                    throw e
                }
            }
        } catch (e: Exception) {
            log.info { "$name: no cursor plane pictures (${e.message}); the cursor is drawn into the pictures" }
            made.forEach(::freeSlot)
            cursorRefused = true
            return false
        }
        cursorSlots = made
        return true
    }

    // The cursor plane as it should be now.
    private fun cursorState(request: DrmDevice.AtomicRequest) {
        if (cursorPlaneId == 0) return
        val slot = cursorSlots.getOrNull(cursorFront)
        if (!cursorVisible || slot == null || cursorRefused || !cursorTested) {
            request.set(cursorPlaneId, cursorProps, "FB_ID", 0)
            request.set(cursorPlaneId, cursorProps, "CRTC_ID", 0)
            return
        }
        val w = cursorPlane.width.toLong()
        val h = cursorPlane.height.toLong()
        request.set(cursorPlaneId, cursorProps, "FB_ID", slot.framebuffer.toLong())
        request.set(cursorPlaneId, cursorProps, "CRTC_ID", crtcId.toLong())
        request.set(cursorPlaneId, cursorProps, "SRC_X", 0)
        request.set(cursorPlaneId, cursorProps, "SRC_Y", 0)
        request.set(cursorPlaneId, cursorProps, "SRC_W", w shl 16)
        request.set(cursorPlaneId, cursorProps, "SRC_H", h shl 16)
        request.set(cursorPlaneId, cursorProps, "CRTC_X", cursorX.toLong())
        request.set(cursorPlaneId, cursorProps, "CRTC_Y", cursorY.toLong())
        request.set(cursorPlaneId, cursorProps, "CRTC_W", w)
        request.set(cursorPlaneId, cursorProps, "CRTC_H", h)
    }

    private fun testCursor(): Boolean {
        cursorTested = true // (so that cursorState includes it)
        val ok = device.commit(Drm.DRM_MODE_ATOMIC_TEST_ONLY()) { request -> cursorState(request) } == 0
        cursorTested = false
        return ok
    }

    // The cursor changed: it goes with the next picture, or — if none is coming — on its own.
    // Not at once: a picture drawn right after it (the same refresh) takes it along. A
    // commit of the cursor alone would hold the monitor until the next refresh, and the
    // picture would wait for it: with the mouse moving all the time over a window that
    // redraws (a game), the pictures would hardly ever get through.
    private fun cursorMoved() {
        cursorDirty = true
        if (cursorAloneQueued) return
        cursorAloneQueued = true
        loop.after(0) {
            cursorAloneQueued = false
            cursorAlone()
        }
    }

    // A check for a cursor without a picture is waiting (see cursorMoved).
    private var cursorAloneQueued = false

    private fun commitCursor() {
        val serial = ++commitSerial
        val result = device.commit(Drm.DRM_MODE_ATOMIC_NONBLOCK() or Drm.DRM_MODE_PAGE_FLIP_EVENT(), serial) { request -> cursorState(request) }
        if (result == 0) {
            cursorDirty = false
            cursorOnlyPending = true
            awaitFlip(serial)
        } else {
            log.trace { "$name: the cursor's move was refused (${errno(result)}); with the next picture" }
        }
    }

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
                scheduleFrame(0)
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

    private fun freeRetired() {
        retired.forEach(::freeSlot)
        retired = emptyList()
        if (retiredBlob != 0) device.destroyBlob(retiredBlob)
        retiredBlob = 0
    }

    // Everything the monitor needs: connector -> CRTC -> mode, plane shows the buffer full screen.
    private fun fullState(request: DrmDevice.AtomicRequest, blob: Int, slot: Slot, mode: Mode, extras: List<Extra>) {
        request.set(connectorId, connectorProps, "CRTC_ID", crtcId.toLong())
        request.set(crtcId, crtcProps, "MODE_ID", blob.toLong())
        request.set(crtcId, crtcProps, "ACTIVE", 1)
        // A link that failed (a dock, a KVM) is tried again with this mode set.
        request.setIfPresent(connectorId, connectorProps, "link-status", LINK_STATUS_GOOD)
        // Our settings and the reset of what others left (see extras).
        for (extra in extras) request.setIfPresent(extra.objectId, extra.props, extra.property, extra.value)
        // Our gamma table (a night light), also after switching terminals.
        request.setIfPresent(crtcId, crtcProps, "GAMMA_LUT", (pendingGamma ?: gammaBlob).toLong())
        showOnPlane(request, slot, mode)
        cursorState(request)
        for (plane in strayPlanes()) {
            val props = device.properties(plane, Drm.DRM_MODE_OBJECT_PLANE())
            request.set(plane, props, "FB_ID", 0)
            request.set(plane, props, "CRTC_ID", 0)
        }
    }

    private fun showOnPlane(request: DrmDevice.AtomicRequest, slot: Slot, mode: Mode) {
        request.set(planeId, planeProps, "FB_ID", slot.framebuffer.toLong())
        request.set(planeId, planeProps, "CRTC_ID", crtcId.toLong())
        // Source rectangle is in 16.16 fixed point.
        request.set(planeId, planeProps, "SRC_X", 0)
        request.set(planeId, planeProps, "SRC_Y", 0)
        request.set(planeId, planeProps, "SRC_W", mode.width.toLong() shl 16)
        request.set(planeId, planeProps, "SRC_H", mode.height.toLong() shl 16)
        request.set(planeId, planeProps, "CRTC_X", 0)
        request.set(planeId, planeProps, "CRTC_Y", 0)
        request.set(planeId, planeProps, "CRTC_W", mode.width.toLong())
        request.set(planeId, planeProps, "CRTC_H", mode.height.toLong())
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
    fun present(draw: (target: GpuTarget, age: Int) -> Unit): Boolean {
        val current = mode ?: return false
        if (!isSessionActive() || flipPending || !powered) return false
        val slot = slots.first { it !== shown }
        frameNumber++
        val age = if (slot.drawnInFrame == 0L) 0 else (frameNumber - slot.drawnInFrame).toInt()
        slot.drawnInFrame = 0 // unknown until drawn to the end
        try {
            draw(slot.buffer, age)
        } catch (e: Throwable) {
            redrawAll = true
            throw e
        } finally {
            renderer.end()
        }
        slot.drawnInFrame = frameNumber

        val serial = ++commitSerial
        val gamma = pendingGamma
        val result = if (needsModeset) {
            if (extrasChanged) {
                // maxBpc or adaptiveSync changed: tested first (a refused one is left out).
                extrasChanged = false
                extrasInUse = workingExtras(extras()) { extras ->
                    device.commit(Drm.DRM_MODE_ATOMIC_TEST_ONLY() or Drm.DRM_MODE_ATOMIC_ALLOW_MODESET()) { request -> fullState(request, modeBlob, slot, current, extras) }
                }
            }
            device.commit(Drm.DRM_MODE_ATOMIC_ALLOW_MODESET() or Drm.DRM_MODE_PAGE_FLIP_EVENT(), serial) { request ->
                fullState(request, modeBlob, slot, current, extrasInUse)
            }
        } else {
            device.commit(Drm.DRM_MODE_ATOMIC_NONBLOCK() or Drm.DRM_MODE_PAGE_FLIP_EVENT(), serial) { request ->
                request.set(planeId, planeProps, "FB_ID", slot.framebuffer.toLong())
                if (cursorDirty) cursorState(request)
                // A new gamma table goes with the picture (no commit of its own, no stall).
                if (gamma != null) request.setIfPresent(crtcId, crtcProps, "GAMMA_LUT", gamma.toLong())
            }
        }
        if (result == 0) {
            if (needsModeset) freeRetired() // the new mode is on screen
            cursorDirty = false
            needsModeset = false
            redrawAll = false
            modesetFailures = 0
            gammaApplied(gamma)
            queued = slot
            awaitFlip(serial)
        } else {
            // Busy or refused (e.g. right after switching terminals): the whole picture again a
            // bit later — what changed was used up by this one.
            log.trace { "$name: a picture was refused (${errno(result)})" }
            redrawAll = true
            slot.drawnInFrame = 0
            if (needsModeset && ++modesetFailures >= MAX_MODESET_FAILURES) {
                log.warn("$name: setting $current fails (${errno(result)}); trying another mode")
                modesetFailures = 0
                if (!start(modes.firstOrNull { it != current })) {
                    log.error("$name: no mode works; it stays off")
                    return false
                }
            }
            scheduleFrame(RETRY_MILLIS)
            return false
        }
        return true
    }

    // Programs' buffers whose flip never came: given back after the next flip that does.
    private val lateDirect = ArrayList<DmabufBuffer>()

    // Waits for the flip of commit [serial]; one that does not come within a second is given up.
    private fun awaitFlip(serial: Long) {
        awaitedSerial = serial
        flipWatchdog?.cancel()
        flipWatchdog = loop.after(FLIP_TIMEOUT_MILLIS) {
            flipWatchdog = null
            if (awaitedSerial != serial || !flipPending) return@after
            log.warn("$name: the monitor did not show the picture within $FLIP_TIMEOUT_MILLIS ms; setting it up anew")
            queued = null
            // The kernel may still show it: the program gets it back after the next picture
            // that does reach the screen, not now.
            queuedDirect?.let { lateDirect += it }
            queuedDirect = null
            cursorOnlyPending = false
            awaitedSerial = -1
            needsModeset = true
            slots.forEach { it.drawnInFrame = 0 }
            scheduleFrame(0)
        }
    }

    // Caulis 0.1.1's present (returning nothing): code built against it calls it.
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    @JvmName("present")
    fun presentWithoutAnswer(draw: (target: GpuTarget, age: Int) -> Unit) {
        present(draw)
    }

    /**
     * Shows a program's own GPU buffer directly, without drawing anything (for a fullscreen
     * game or video). The kernel checks first whether the display can show it; returns
     * false if not — then draw normally with [present]. The program gets the buffer back
     * when the monitor has moved on to the next picture.
     */
    fun presentDirect(buffer: DmabufBuffer): Boolean {
        val current = mode ?: return false
        if (!isSessionActive() || flipPending || needsModeset || !powered) return false
        // On a descriptor shared with the renderer, a program's buffer handles could never be
        // closed (they may be the GPU driver's own): no direct showing then.
        if (device.sharedWithRenderer) return false
        if (buffer.width != current.width || buffer.height != current.height) return false
        val framebuffer = buffer.attachment(this) { ScanoutFramebuffer(device, device.importFramebuffer(buffer.dmabuf)) }
        val id = framebuffer.id ?: return false
        if (framebuffer.refused) return false
        val test = device.commit(Drm.DRM_MODE_ATOMIC_TEST_ONLY()) { request ->
            request.set(planeId, planeProps, "FB_ID", id.toLong())
        }
        if (test != 0) {
            framebuffer.refused = true // this buffer will not work later either
            return false
        }
        val serial = ++commitSerial
        val gamma = pendingGamma
        val withCursor = cursorDirty
        val result = device.commit(Drm.DRM_MODE_ATOMIC_NONBLOCK() or Drm.DRM_MODE_PAGE_FLIP_EVENT(), serial) { request ->
            request.set(planeId, planeProps, "FB_ID", id.toLong())
            // The cursor and a new gamma table go along, as with our own pictures.
            if (withCursor) cursorState(request)
            if (gamma != null) request.setIfPresent(crtcId, crtcProps, "GAMMA_LUT", gamma.toLong())
        }
        if (result != 0) return false
        if (withCursor) cursorDirty = false
        gammaApplied(gamma)
        buffer.startShowing()
        queuedDirect = buffer
        awaitFlip(serial)
        return true
    }

    // A program's buffer registered with the display; freed together with the buffer.
    private class ScanoutFramebuffer(private val device: DrmDevice, val id: Int?) : AutoCloseable {
        var refused = false

        override fun close() {
            id?.let(device::removeFramebuffer)
        }
    }

    // The monitor now shows the queued picture: the previous one is free again.
    private fun flipped(nanos: Long, sequence: Long, serial: Long) {
        // A flip of an older commit (from before switching terminals, or given up): not ours to count.
        if (serial != awaitedSerial) {
            log.trace { "$name: a flip of an old commit ($serial, awaiting $awaitedSerial)" }
            return
        }
        awaitedSerial = -1
        flipWatchdog?.cancel()
        flipWatchdog = null
        if (cursorOnlyPending) {
            // Only the cursor moved: nothing to report. A picture first (it takes the cursor
            // with it); the cursor alone only if no picture came — else, with the mouse moving
            // every refresh, windows would never get a frame.
            cursorOnlyPending = false
            lastFlipNanos = System.nanoTime()
            nextFrame()
            cursorAlone()
            return
        }
        val wasQueued = flipPending
        // Buffers given up on by the watchdog: something newer is on screen now.
        lateDirect.forEach { it.stopShowing() }
        lateDirect.clear()
        if (wasQueued) {
            val previousDirect = shownDirect
            if (queuedDirect != null) {
                shownDirect = queuedDirect
                shown = null // none of our buffers is on screen
            } else {
                shown = queued
                shownDirect = null
            }
            // The program may draw into its previous buffer again (every start of showing has
            // its stop — also when it committed the same buffer once more).
            previousDirect?.stopShowing()
            queued = null
            queuedDirect = null
        }
        lastFlipNanos = System.nanoTime()
        if (wasQueued) {
            try {
                onPresented(nanos, refreshNanos, sequence, shownDirect != null)
            } catch (e: Exception) {
                log.error("Reporting a frame of $name failed", e)
            }
        }
        nextFrame()
        cursorAlone()
    }

    // The cursor moved, and no picture is on its way to take it along: it goes on its own.
    private fun cursorAlone() {
        if (cursorDirty && !flipPending && !needsModeset && powered && isSessionActive() && mode != null) commitCursor()
    }

    /**
     * Asks for an [onFrame] call at the monitor's next refresh, e.g. because something
     * changed while the screen was idle. Cheap to call often.
     */
    fun requestFrame() {
        if (flipPending || mode == null) return // onFrame comes with the next flip anyway
        val wait = (lastFlipNanos + refreshNanos - System.nanoTime()).coerceAtLeast(0) / 1_000_000
        scheduleFrame(wait)
    }

    // Nothing reaches the screen (switched off, or another terminal is shown): no refresh paces
    // the frames then, so they come only as a slow heartbeat — programs do not freeze, and
    // the compositor does not draw for nothing as fast as it can.
    private val dark: Boolean get() = !powered || !isSessionActive()

    // Asks for the next picture. If drawing fails, the screen must not freeze for good:
    // the error is logged and drawing is tried again a little later.
    private fun nextFrame() {
        try {
            onFrame()
        } catch (e: Exception) {
            log.error("Drawing a frame for $name failed", e)
            scheduleFrame(ERROR_RETRY_MILLIS)
        }
    }

    /** Its link failed (DisplayPort training, a dock): set the mode again and redraw. */
    internal fun relink() = resume()

    /** After returning from another virtual terminal: set the mode again and redraw. */
    internal fun resume() {
        needsModeset = true
        awaitedSerial = -1 // flips from before belong to no commit of now
        flipWatchdog?.cancel()
        flipWatchdog = null
        cursorOnlyPending = false
        queued = null
        queuedDirect?.stopShowing() // that flip never happened
        queuedDirect = null
        slots.forEach { it.drawnInFrame = 0 } // draw everything again
        scheduleFrame(0)
    }

    private fun scheduleFrame(delayMillis: Long) {
        val delay = if (dark) maxOf(delayMillis, DARK_HEARTBEAT_MILLIS) else delayMillis
        val due = System.nanoTime() + delay * 1_000_000
        // One frame at a time; an earlier one replaces a later (the screen came back on).
        if (frameTimer != null && due >= frameDue) return
        frameTimer?.cancel()
        frameDue = due
        frameTimer = loop.after(delay) {
            frameTimer = null
            nextFrame()
        }
    }

    // The frame asked for, and when it is due (System.nanoTime).
    private var frameTimer: EventLoop.Timer? = null
    private var frameDue = 0L

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
            if (mode != null) scheduleFrame(0)
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
            if (mode != null) scheduleFrame(0)
        }

    /** False while the screen is switched off (DPMS): nothing is drawn. */
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
            scheduleFrame(0)
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
        scheduleFrame(0)
        log.info { "$name: $wanted" }
        return true
    }

    /** Entries of the monitor's gamma table (0: it has none). */
    val gammaSize: Int get() = crtcProps["GAMMA_LUT_SIZE"]?.value?.toInt() ?: 0

    /**
     * Uses the gamma table [red], [green], [blue] ([gammaSize] each); null: the monitor's own.
     * It goes with the next picture (tested now), and stays across mode sets and terminals.
     */
    fun setGamma(red: ShortArray?, green: ShortArray?, blue: ShortArray?): Boolean {
        if (crtcProps["GAMMA_LUT"] == null) return false
        val blob = if (red == null || green == null || blue == null) {
            0
        } else {
            val size = red.size
            if (size != gammaSize || green.size != size || blue.size != size) return false
            // struct drm_color_lut { u16 red, green, blue, reserved }
            Arena.ofConfined().use { temp ->
                val table = temp.allocate(size * 8L)
                for (i in 0 until size) {
                    table.set(java.lang.foreign.ValueLayout.JAVA_SHORT, i * 8L, red[i])
                    table.set(java.lang.foreign.ValueLayout.JAVA_SHORT, i * 8L + 2, green[i])
                    table.set(java.lang.foreign.ValueLayout.JAVA_SHORT, i * 8L + 4, blue[i])
                    table.set(java.lang.foreign.ValueLayout.JAVA_SHORT, i * 8L + 6, 0)
                }
                device.createBlob(table)
            }
        }
        if (isSessionActive()) {
            val result = device.commit(Drm.DRM_MODE_ATOMIC_TEST_ONLY()) { request -> request.set(crtcId, crtcProps, "GAMMA_LUT", blob.toLong()) }
            if (result != 0) {
                log.warn("$name: the gamma table was refused (${errno(result)})")
                if (blob != 0) device.destroyBlob(blob)
                return false
            }
        }
        pendingGamma?.let { if (it != 0 && it != gammaBlob) device.destroyBlob(it) }
        pendingGamma = blob
        redrawAll = true
        scheduleFrame(0)
        return true
    }

    // The gamma table on screen, and one waiting for the next picture.
    private var gammaBlob = 0
    private var pendingGamma: Int? = null

    // [gamma] reached the screen with a picture: the one before is no longer needed.
    private fun gammaApplied(gamma: Int?) {
        if (gamma == null || pendingGamma != gamma) return
        if (gammaBlob != 0 && gammaBlob != gamma) device.destroyBlob(gammaBlob)
        gammaBlob = gamma
        pendingGamma = null
    }

    private fun errno(result: Int): String = when (-result) {
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
                    request.set(cursorPlaneId, cursorProps, "FB_ID", 0)
                    request.set(cursorPlaneId, cursorProps, "CRTC_ID", 0)
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

    private fun freeSlot(slot: Slot) {
        device.removeFramebuffer(slot.framebuffer)
        slot.buffer.close()
    }

    // Gives programs' buffers back (the monitor stops showing them).
    private fun releaseDirect() {
        lateDirect.forEach { it.stopShowing() }
        lateDirect.clear()
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
        device.removeFlipHandler(crtcId)
        flipWatchdog?.cancel()
        frameTimer?.cancel()
        frameTimer = null
        freeBuffers()
        freeRetired()
        cursorSlots.forEach(::freeSlot)
        cursorSlots = emptyList()
        pendingGamma?.let { if (it != 0) device.destroyBlob(it) }
        if (gammaBlob != 0) device.destroyBlob(gammaBlob)
    }

    private companion object {
        /** Two buffers: one on screen (or on its way there), one being drawn — only one picture is ever on its way. */
        const val BUFFERS = 2
        const val MAX_MODESET_FAILURES = 5
        const val FLIP_TIMEOUT_MILLIS = 1000L
        const val LINK_STATUS_GOOD = 0L
        const val DRM_CAP_CURSOR_WIDTH = 0x8
        const val DRM_CAP_CURSOR_HEIGHT = 0x9
        const val MAX_MODES_TO_TRY = 8
        const val RETRY_MILLIS = 16L
        const val ERROR_RETRY_MILLIS = 1000L

        // How often frames come while nothing reaches the screen (see dark).
        const val DARK_HEARTBEAT_MILLIS = 1000L
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
