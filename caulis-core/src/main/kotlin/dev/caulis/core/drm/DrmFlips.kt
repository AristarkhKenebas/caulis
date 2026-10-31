package dev.caulis.core.drm

import dev.caulis.core.DmabufBuffer
import dev.caulis.event.EventLoop
import dev.caulis.ffi.c.drm.Drm
import dev.caulis.render.GpuTarget

/**
 * Pictures reaching the screen: presenting, waiting for the flip, direct scanout, the next frame's time.
 */
internal class DrmFlips(private val drmOutput: DrmOutput) {

    internal fun showOnPlane(request: DrmDevice.AtomicRequest, slot: DrmOutput.Slot, mode: DrmOutput.Mode) {
        request.set(drmOutput.planeId, drmOutput.planeProps, "FB_ID", slot.framebuffer.toLong())
        request.set(drmOutput.planeId, drmOutput.planeProps, "CRTC_ID", drmOutput.crtcId.toLong())
        // Source rectangle is in 16.16 fixed point.
        request.set(drmOutput.planeId, drmOutput.planeProps, "SRC_X", 0)
        request.set(drmOutput.planeId, drmOutput.planeProps, "SRC_Y", 0)
        request.set(drmOutput.planeId, drmOutput.planeProps, "SRC_W", mode.width.toLong() shl 16)
        request.set(drmOutput.planeId, drmOutput.planeProps, "SRC_H", mode.height.toLong() shl 16)
        request.set(drmOutput.planeId, drmOutput.planeProps, "CRTC_X", 0)
        request.set(drmOutput.planeId, drmOutput.planeProps, "CRTC_Y", 0)
        request.set(drmOutput.planeId, drmOutput.planeProps, "CRTC_W", mode.width.toLong())
        request.set(drmOutput.planeId, drmOutput.planeProps, "CRTC_H", mode.height.toLong())
    }

    fun present(draw: (target: GpuTarget, age: Int) -> Unit): Boolean {
        val current = drmOutput.mode ?: return false
        if (!drmOutput.isSessionActive() || drmOutput.flipPending || !drmOutput.powered) return false
        val slot = drmOutput.slots.first { it !== drmOutput.shown }
        drmOutput.frameNumber++
        val age = if (slot.drawnInFrame == 0L) 0 else (drmOutput.frameNumber - slot.drawnInFrame).toInt()
        slot.drawnInFrame = 0 // unknown until drawn to the end
        try {
            draw(slot.buffer, age)
        } catch (e: Throwable) {
            drmOutput.redrawAll = true
            throw e
        } finally {
            drmOutput.renderer.end()
        }
        slot.drawnInFrame = drmOutput.frameNumber

        val serial = ++drmOutput.commitSerial
        val gamma = drmOutput.gammaSide.pendingGamma
        val result = if (drmOutput.needsModeset) {
            if (drmOutput.extrasChanged) {
                // maxBpc or adaptiveSync changed: tested first (a refused one is left out).
                drmOutput.extrasChanged = false
                drmOutput.extrasInUse = drmOutput.workingExtras(drmOutput.extras()) { extras ->
                    drmOutput.device.commit(Drm.DRM_MODE_ATOMIC_TEST_ONLY() or Drm.DRM_MODE_ATOMIC_ALLOW_MODESET()) { request -> drmOutput.fullState(request, drmOutput.modeBlob, slot, current, extras) }
                }
            }
            drmOutput.device.commit(Drm.DRM_MODE_ATOMIC_ALLOW_MODESET() or Drm.DRM_MODE_PAGE_FLIP_EVENT(), serial) { request ->
                drmOutput.fullState(request, drmOutput.modeBlob, slot, current, drmOutput.extrasInUse)
            }
        } else {
            drmOutput.device.commit(Drm.DRM_MODE_ATOMIC_NONBLOCK() or Drm.DRM_MODE_PAGE_FLIP_EVENT(), serial) { request ->
                request.set(drmOutput.planeId, drmOutput.planeProps, "FB_ID", slot.framebuffer.toLong())
                if (drmOutput.cursorSide.cursorDirty) drmOutput.cursorSide.cursorState(request)
                // A new gamma table goes with the picture (no commit of its own, no stall).
                if (gamma != null) request.setIfPresent(drmOutput.crtcId, drmOutput.crtcProps, "GAMMA_LUT", gamma.toLong())
            }
        }
        if (result == 0) {
            if (drmOutput.needsModeset) drmOutput.freeRetired() // the new mode is on screen
            drmOutput.cursorSide.cursorDirty = false
            drmOutput.needsModeset = false
            drmOutput.redrawAll = false
            drmOutput.modesetFailures = 0
            drmOutput.gammaSide.gammaApplied(gamma)
            drmOutput.queued = slot
            awaitFlip(serial)
        } else {
            // Busy or refused (e.g. right after switching terminals): the whole picture again a
            // bit later — what changed was used up by this one.
            drmOutput.log.trace { "$drmOutput.name: a picture was refused (${drmOutput.errno(result)})" }
            drmOutput.redrawAll = true
            slot.drawnInFrame = 0
            if (drmOutput.needsModeset && ++drmOutput.modesetFailures >= MAX_MODESET_FAILURES) {
                drmOutput.log.warn("$drmOutput.name: setting $current fails (${drmOutput.errno(result)}); trying another mode")
                drmOutput.modesetFailures = 0
                if (!drmOutput.start(drmOutput.modes.firstOrNull { it != current })) {
                    drmOutput.log.error("$drmOutput.name: no mode works; it stays off")
                    return false
                }
            }
            scheduleFrame(RETRY_MILLIS)
            return false
        }
        return true
    }

    // Programs' buffers whose flip never came: given back after the next flip that does.
    internal val lateDirect = ArrayList<DmabufBuffer>()

    // Waits for the flip of commit [serial]; one that does not come within a second is given up.
    internal fun awaitFlip(serial: Long) {
        drmOutput.awaitedSerial = serial
        drmOutput.flipWatchdog?.cancel()
        drmOutput.flipWatchdog = drmOutput.loop.after(FLIP_TIMEOUT_MILLIS) {
            drmOutput.flipWatchdog = null
            if (drmOutput.awaitedSerial != serial || !drmOutput.flipPending) return@after
            drmOutput.log.warn("$drmOutput.name: the monitor did not show the picture within $FLIP_TIMEOUT_MILLIS ms; setting it up anew")
            drmOutput.queued = null
            // The kernel may still show it: the program gets it back after the next picture
            // that does reach the screen, not now.
            drmOutput.queuedDirect?.let { lateDirect += it }
            drmOutput.queuedDirect = null
            drmOutput.cursorSide.cursorOnlyPending = false
            drmOutput.awaitedSerial = -1
            drmOutput.needsModeset = true
            drmOutput.slots.forEach { it.drawnInFrame = 0 }
            scheduleFrame(0)
        }
    }

    // Caulis 0.1.1's present (returning nothing): code built against it calls it.
    fun presentWithoutAnswer(draw: (target: GpuTarget, age: Int) -> Unit) {
        present(draw)
    }

    fun presentDirect(buffer: DmabufBuffer): Boolean {
        val current = drmOutput.mode ?: return false
        if (!drmOutput.isSessionActive() || drmOutput.flipPending || drmOutput.needsModeset || !drmOutput.powered) return false
        // On a descriptor shared with the renderer, a program's buffer handles could never be
        // closed (they may be the GPU driver's own): no direct showing then.
        if (drmOutput.device.sharedWithRenderer) return false
        if (buffer.width != current.width || buffer.height != current.height) return false
        val framebuffer = buffer.attachment(this) { ScanoutFramebuffer(drmOutput.device, drmOutput.device.importFramebuffer(buffer.dmabuf)) }
        val id = framebuffer.id ?: return false
        if (framebuffer.refused) return false
        val test = drmOutput.device.commit(Drm.DRM_MODE_ATOMIC_TEST_ONLY()) { request ->
            request.set(drmOutput.planeId, drmOutput.planeProps, "FB_ID", id.toLong())
        }
        if (test != 0) {
            framebuffer.refused = true // this buffer will not work later either
            return false
        }
        val serial = ++drmOutput.commitSerial
        val gamma = drmOutput.gammaSide.pendingGamma
        val withCursor = drmOutput.cursorSide.cursorDirty
        val result = drmOutput.device.commit(Drm.DRM_MODE_ATOMIC_NONBLOCK() or Drm.DRM_MODE_PAGE_FLIP_EVENT(), serial) { request ->
            request.set(drmOutput.planeId, drmOutput.planeProps, "FB_ID", id.toLong())
            // The cursor and a new gamma table go along, as with our own pictures.
            if (withCursor) drmOutput.cursorSide.cursorState(request)
            if (gamma != null) request.setIfPresent(drmOutput.crtcId, drmOutput.crtcProps, "GAMMA_LUT", gamma.toLong())
        }
        if (result != 0) return false
        if (withCursor) drmOutput.cursorSide.cursorDirty = false
        drmOutput.gammaSide.gammaApplied(gamma)
        buffer.startShowing()
        drmOutput.queuedDirect = buffer
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
    internal fun flipped(nanos: Long, sequence: Long, serial: Long) {
        // A flip of an older commit (from before switching terminals, or given up): not ours to count.
        if (serial != drmOutput.awaitedSerial) {
            drmOutput.log.trace { "$drmOutput.name: a flip of an old commit ($serial, awaiting $drmOutput.awaitedSerial)" }
            return
        }
        drmOutput.awaitedSerial = -1
        drmOutput.flipWatchdog?.cancel()
        drmOutput.flipWatchdog = null
        if (drmOutput.cursorSide.cursorOnlyPending) {
            // Only the cursor moved: nothing to report. A picture first (it takes the cursor
            // with it); the cursor alone only if no picture came — else, with the mouse moving
            // every refresh, windows would never get a frame.
            drmOutput.cursorSide.cursorOnlyPending = false
            drmOutput.lastFlipNanos = System.nanoTime()
            frameAfterFlip()
            drmOutput.cursorSide.cursorAlone()
            return
        }
        val wasQueued = drmOutput.flipPending
        // Buffers given up on by the watchdog: something newer is on screen now.
        lateDirect.forEach { it.stopShowing() }
        lateDirect.clear()
        if (wasQueued) {
            val previousDirect = drmOutput.shownDirect
            if (drmOutput.queuedDirect != null) {
                drmOutput.shownDirect = drmOutput.queuedDirect
                drmOutput.shown = null // none of our buffers is on screen
            } else {
                drmOutput.shown = drmOutput.queued
                drmOutput.shownDirect = null
            }
            // The program may draw into its previous buffer again (every start of showing has
            // its stop — also when it committed the same buffer once more).
            previousDirect?.stopShowing()
            drmOutput.queued = null
            drmOutput.queuedDirect = null
        }
        drmOutput.lastFlipNanos = System.nanoTime()
        if (wasQueued) {
            try {
                drmOutput.onPresented(nanos, drmOutput.refreshNanos, sequence, drmOutput.shownDirect != null)
            } catch (e: Exception) {
                drmOutput.log.error("Reporting a frame of $drmOutput.name failed", e)
            }
        }
        frameAfterFlip()
        drmOutput.cursorSide.cursorAlone()
    }

    fun requestFrame() {
        if (drmOutput.flipPending || drmOutput.mode == null) return // onFrame comes with the next flip anyway
        scheduleFrame(frameDelayMillis(drmOutput.lastFlipNanos, drmOutput.refreshNanos, System.nanoTime(), drmOutput.maxRenderMillis))
    }

    // Nothing reaches the screen (switched off, or another terminal is shown): no refresh paces
    // the frames then, so they come only as a slow heartbeat — programs do not freeze, and
    // the compositor does not draw for nothing as fast as it can.
    private val dark: Boolean get() = !drmOutput.powered || !drmOutput.isSessionActive()

    // The next picture after a flip: at once; with maxRenderMillis, that long before the next refresh.
    private fun frameAfterFlip() {
        if (drmOutput.maxRenderMillis <= 0) return nextFrame()
        val delay = frameDelayMillis(drmOutput.lastFlipNanos, drmOutput.refreshNanos, System.nanoTime(), drmOutput.maxRenderMillis)
        if (delay <= 0) nextFrame() else scheduleFrame(delay)
    }

    // Asks for the next picture. If drawing fails, the screen must not freeze for good:
    // the error is logged and drawing is tried again a little later.
    private fun nextFrame() {
        try {
            drmOutput.onFrame()
        } catch (e: Exception) {
            drmOutput.log.error("Drawing a frame for $drmOutput.name failed", e)
            scheduleFrame(ERROR_RETRY_MILLIS)
        }
    }

    internal fun scheduleFrame(delayMillis: Long) {
        val delay = if (dark) maxOf(delayMillis, DARK_HEARTBEAT_MILLIS) else delayMillis
        val due = System.nanoTime() + delay * 1_000_000
        // One frame at a time; an earlier one replaces a later (the screen came back on).
        if (frameTimer != null && due >= frameDue) return
        frameTimer?.cancel()
        frameDue = due
        frameTimer = drmOutput.loop.after(delay) {
            frameTimer = null
            nextFrame()
        }
    }

    // The frame asked for, and when it is due (System.nanoTime).
    internal var frameTimer: EventLoop.Timer? = null

    private var frameDue = 0L
}

/**
 * How long to wait before drawing the next picture, [lastFlip] being the last refresh (nanoseconds).
 * [maxRenderMillis] 0: until the next refresh is due (the picture shows at the one after it).
 * Else: until that long before the next refresh still ahead, so that it shows on that very one
 * (at once when that moment is past: it shows a refresh later, as a frame drawn too late does).
 */
internal fun frameDelayMillis(lastFlip: Long, refresh: Long, now: Long, maxRenderMillis: Int): Long {
    if (maxRenderMillis <= 0 || refresh <= 0) return (lastFlip + refresh - now).coerceAtLeast(0) / 1_000_000
    val render = minOf(maxRenderMillis * 1_000_000L, refresh)
    val periods = if (now <= lastFlip) 1 else (now - lastFlip) / refresh + 1
    return (lastFlip + periods * refresh - render - now).coerceAtLeast(0) / 1_000_000
}
