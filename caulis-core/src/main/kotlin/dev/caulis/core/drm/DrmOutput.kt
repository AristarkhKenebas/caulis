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
 * Pictures are drawn into one of three buffers and then shown with a page flip; when the
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
    // Planes of other programs still attached to our CRTC (cursor, overlays): turned off.
    private val strayPlanes: List<Int>,
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
     * ([nanos], monotonic clock), the time between refreshes and the monitor's frame number.
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

    // A picture is on its way to the screen; the next one waits for the flip.
    private val flipPending: Boolean get() = queued != null || queuedDirect != null
    private var needsModeset = true
    private var frameScheduled = false

    init {
        device.onFlip(crtcId) { nanos, sequence -> flipped(nanos, sequence) }
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
        val newSlots = try {
            (0 until BUFFERS).map {
                val buffer = renderer.createBuffer(candidate.width, candidate.height, Dmabuf.XRGB8888, emptyList(), scanout = true)
                Slot(buffer, device.addFramebuffer(buffer))
            }
        } catch (e: IllegalStateException) {
            return false
        }
        val blob = Arena.ofConfined().use { temp -> device.createBlob(temp.allocateFrom(JAVA_BYTE, *candidate.info)) }
        val result = device.commit(Drm.DRM_MODE_ATOMIC_TEST_ONLY() or Drm.DRM_MODE_ATOMIC_ALLOW_MODESET()) { request ->
            fullState(request, blob, newSlots[0], candidate)
        }
        if (result != 0) {
            device.destroyBlob(blob)
            newSlots.forEach(::freeSlot)
            return false
        }
        freeBuffers()
        slots = newSlots
        modeBlob = blob
        return true
    }

    // Everything the monitor needs: connector -> CRTC -> mode, plane shows the buffer full screen.
    private fun fullState(request: DrmDevice.AtomicRequest, blob: Int, slot: Slot, mode: Mode) {
        request.set(connectorId, connectorProps, "CRTC_ID", crtcId.toLong())
        request.set(crtcId, crtcProps, "MODE_ID", blob.toLong())
        request.set(crtcId, crtcProps, "ACTIVE", 1)
        showOnPlane(request, slot, mode)
        for (plane in strayPlanes) {
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

    /** True if the monitor must get a picture even if nothing changed (it is being set up). */
    val needsPicture: Boolean get() = needsModeset

    /**
     * Draws the next picture with [draw] and sends it to the monitor. [draw] also gets how
     * many frames old the buffer's picture is (1 = previous frame, 0 = unknown: draw
     * everything). Does nothing while another virtual terminal is shown or a picture is
     * still on its way.
     */
    fun present(draw: (target: GpuTarget, age: Int) -> Unit) {
        val current = mode ?: return
        if (!isSessionActive() || flipPending || !powered) return
        val slot = slots.first { it !== shown }
        frameNumber++
        val age = if (slot.drawnInFrame == 0L) 0 else (frameNumber - slot.drawnInFrame).toInt()
        slot.drawnInFrame = frameNumber
        draw(slot.buffer, age)
        renderer.end()

        val result = if (needsModeset) {
            device.commit(Drm.DRM_MODE_ATOMIC_ALLOW_MODESET() or Drm.DRM_MODE_PAGE_FLIP_EVENT()) { request ->
                fullState(request, modeBlob, slot, current)
            }
        } else {
            device.commit(Drm.DRM_MODE_ATOMIC_NONBLOCK() or Drm.DRM_MODE_PAGE_FLIP_EVENT()) { request ->
                request.set(planeId, planeProps, "FB_ID", slot.framebuffer.toLong())
            }
        }
        if (result == 0) {
            needsModeset = false
            queued = slot
        } else {
            // Busy or refused (e.g. right after switching terminals): try again a bit later.
            scheduleFrame(RETRY_MILLIS)
        }
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
        val result = device.commit(Drm.DRM_MODE_ATOMIC_NONBLOCK() or Drm.DRM_MODE_PAGE_FLIP_EVENT()) { request ->
            request.set(planeId, planeProps, "FB_ID", id.toLong())
        }
        if (result != 0) return false
        buffer.startShowing()
        queuedDirect = buffer
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
    private fun flipped(nanos: Long, sequence: Long) {
        val wasQueued = flipPending
        if (wasQueued) {
            val previousDirect = shownDirect
            if (queuedDirect != null) {
                shownDirect = queuedDirect
                shown = null // none of our buffers is on screen
            } else {
                shown = queued
                shownDirect = null
            }
            // The program may draw into its previous buffer again.
            if (previousDirect != null && previousDirect !== shownDirect) previousDirect.stopShowing()
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

    /** After returning from another virtual terminal: set the mode again and redraw. */
    internal fun resume() {
        needsModeset = true
        queued = null
        queuedDirect?.stopShowing() // that flip never happened
        queuedDirect = null
        slots.forEach { it.drawnInFrame = 0 } // draw everything again
        scheduleFrame(0)
    }

    private fun scheduleFrame(delayMillis: Long) {
        if (frameScheduled) return
        frameScheduled = true
        loop.after(delayMillis) {
            frameScheduled = false
            nextFrame()
        }
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

    /** Uses the gamma table [red], [green], [blue] ([gammaSize] each); null: the monitor's own. */
    fun setGamma(red: ShortArray?, green: ShortArray?, blue: ShortArray?): Boolean {
        if (crtcProps["GAMMA_LUT"] == null || !isSessionActive()) return false
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
        val result = device.commit(0) { request -> request.set(crtcId, crtcProps, "GAMMA_LUT", blob.toLong()) }
        if (gammaBlob != 0) device.destroyBlob(gammaBlob)
        gammaBlob = blob
        if (result != 0) log.warn("$name: the gamma table was refused ($result)")
        return result == 0
    }

    private var gammaBlob = 0

    /** Turns the monitor off (it was unplugged, or Caulis stops using it). */
    internal fun stop() {
        if (isSessionActive() && mode != null) {
            device.commit(Drm.DRM_MODE_ATOMIC_ALLOW_MODESET()) { request ->
                request.set(connectorId, connectorProps, "CRTC_ID", 0)
                request.set(crtcId, crtcProps, "MODE_ID", 0)
                request.set(crtcId, crtcProps, "ACTIVE", 0)
                request.set(planeId, planeProps, "FB_ID", 0)
                request.set(planeId, planeProps, "CRTC_ID", 0)
            }
        }
        mode = null
    }

    private fun freeSlot(slot: Slot) {
        device.removeFramebuffer(slot.framebuffer)
        slot.buffer.close()
    }

    // Gives programs' buffers back (the monitor stops showing them).
    private fun releaseDirect() {
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
        freeBuffers()
    }

    private companion object {
        /** Three buffers: one on screen, one queued, one being drawn. */
        const val BUFFERS = 3
        const val MAX_MODES_TO_TRY = 8
        const val RETRY_MILLIS = 16L
        const val ERROR_RETRY_MILLIS = 1000L
    }
}
