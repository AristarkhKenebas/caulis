package dev.caulis.core.drm

import dev.caulis.ffi.c.drm.Drm
import dev.caulis.render.Dmabuf
import dev.caulis.render.GpuTarget

/**
 * The monitor's cursor plane: its pictures, moving it, and what is done when the display refuses it.
 */
internal class DrmCursorPlane(private val drmOutput: DrmOutput) {

    internal val cursorProps = if (drmOutput.cursorPlaneId != 0) drmOutput.device.properties(drmOutput.cursorPlaneId, Drm.DRM_MODE_OBJECT_PLANE()) else emptyMap()

    // Two pictures: one shown, one to draw the next into.
    internal var cursorSlots: List<DrmOutput.Slot> = emptyList()

    private var cursorFront = 0

    // The cursor's state changed and is not on screen yet.
    internal var cursorDirty = false

    // Only the cursor changed: a commit of its own is on its way.
    internal var cursorOnlyPending = false

    // The display refused the cursor plane: the cursor goes into the frames from now on.
    @Volatile internal var cursorRefused = false

    // The display took the cursor plane once (before that, mode sets leave it out).
    @Volatile private var cursorTested = false

    // (The input thread's own count of refusals in a row.)
    private var quickMoveFailures = 0

    internal inner class Plane : dev.caulis.core.HardwareCursor, dev.caulis.core.MovableFromAnyThread {
        // From the input thread: the same picture at another place, at once — not waiting for
        // the compositor's thread (busy for a moment: the pointer would stand still). The next
        // commit of the compositor's thread takes the same place along (cursorX, cursorY).
        override fun moveFromAnyThread(x: Int, y: Int): Boolean {
            if (!drmOutput.quickMoves || drmOutput.closed || cursorRefused || !cursorTested || !drmOutput.cursorVisible || !drmOutput.powered || drmOutput.needsModeset || drmOutput.mode == null || !drmOutput.isSessionActive()) return false
            if (x == drmOutput.cursorX && y == drmOutput.cursorY) return true
            val result = drmOutput.device.moveCursor(drmOutput.crtcId, x, y)
            if (result == 0) {
                drmOutput.cursorX = x
                drmOutput.cursorY = y
                quickMoveFailures = 0
                return true
            }
            // Not ours just now (another terminal: EACCES/EPERM) or busy: the next time. Else,
            // refused again and again, the driver cannot: from now on the compositor's thread
            // alone moves it.
            if (-result !in TRANSIENT_ERRNOS && ++quickMoveFailures >= 3) {
                drmOutput.quickMoves = false
                drmOutput.log.info { "$drmOutput.name: the cursor cannot be moved from the input thread (${drmOutput.errno(result)}); it moves with the compositor's thread" }
            }
            return false
        }

        override val width: Int = drmOutput.device.capability(DRM_CAP_CURSOR_WIDTH).takeIf { it in 16..512 }?.toInt() ?: 64
        override val height: Int = drmOutput.device.capability(DRM_CAP_CURSOR_HEIGHT).takeIf { it in 16..512 }?.toInt() ?: 64

        override fun show(x: Int, y: Int, draw: ((GpuTarget) -> Unit)?): Boolean {
            if (cursorRefused) return false
            if (draw != null) {
                if (cursorSlots.isEmpty() && !makeCursorSlots(width, height)) return false
                val back = cursorSlots[1 - cursorFront]
                draw(back.buffer)
                drmOutput.renderer.end()
                cursorFront = 1 - cursorFront
            } else if (cursorSlots.isEmpty()) {
                return false
            }
            if (draw == null && drmOutput.cursorVisible && x == drmOutput.cursorX && y == drmOutput.cursorY) return true
            if (!cursorTested) {
                // Tested once the monitor runs (a display that cannot do it falls back for good);
                // until then the cursor is drawn into the pictures.
                if (drmOutput.needsModeset || !drmOutput.isSessionActive() || drmOutput.mode == null) return false
                drmOutput.cursorX = x
                drmOutput.cursorY = y
                drmOutput.cursorVisible = true
                if (!testCursor()) {
                    drmOutput.log.info { "$drmOutput.name: the cursor plane is refused; the cursor is drawn into the pictures" }
                    cursorRefused = true
                    drmOutput.cursorVisible = false
                    return false
                }
                cursorTested = true
                drmOutput.log.info { "$drmOutput.name: the cursor is on its own plane (${width}x$height)" }
            }
            drmOutput.cursorX = x
            drmOutput.cursorY = y
            drmOutput.cursorVisible = true
            cursorMoved()
            return true
        }

        override fun hide() {
            if (!drmOutput.cursorVisible) return
            drmOutput.cursorVisible = false
            cursorMoved()
        }
    }

    internal val cursorPlane = Plane()

    private fun makeCursorSlots(width: Int, height: Int): Boolean {
        val made = ArrayList<DrmOutput.Slot>()
        try {
            repeat(2) {
                // Plain rows: what cursor planes take everywhere.
                val buffer = drmOutput.renderer.createBuffer(width, height, Dmabuf.ARGB8888, listOf(Dmabuf.MODIFIER_LINEAR), scanout = true)
                try {
                    made += DrmOutput.Slot(buffer, drmOutput.device.addFramebuffer(buffer))
                } catch (e: Exception) {
                    buffer.close()
                    throw e
                }
            }
        } catch (e: Exception) {
            drmOutput.log.info { "$drmOutput.name: no cursor plane pictures (${e.message}); the cursor is drawn into the pictures" }
            made.forEach(drmOutput::freeSlot)
            cursorRefused = true
            return false
        }
        cursorSlots = made
        return true
    }

    // The cursor plane as it should be now.
    internal fun cursorState(request: DrmDevice.AtomicRequest) {
        if (drmOutput.cursorPlaneId == 0) return
        val slot = cursorSlots.getOrNull(cursorFront)
        if (!drmOutput.cursorVisible || slot == null || cursorRefused || !cursorTested) {
            request.set(drmOutput.cursorPlaneId, cursorProps, "FB_ID", 0)
            request.set(drmOutput.cursorPlaneId, cursorProps, "CRTC_ID", 0)
            return
        }
        val w = cursorPlane.width.toLong()
        val h = cursorPlane.height.toLong()
        request.set(drmOutput.cursorPlaneId, cursorProps, "FB_ID", slot.framebuffer.toLong())
        request.set(drmOutput.cursorPlaneId, cursorProps, "CRTC_ID", drmOutput.crtcId.toLong())
        request.set(drmOutput.cursorPlaneId, cursorProps, "SRC_X", 0)
        request.set(drmOutput.cursorPlaneId, cursorProps, "SRC_Y", 0)
        request.set(drmOutput.cursorPlaneId, cursorProps, "SRC_W", w shl 16)
        request.set(drmOutput.cursorPlaneId, cursorProps, "SRC_H", h shl 16)
        request.set(drmOutput.cursorPlaneId, cursorProps, "CRTC_X", drmOutput.cursorX.toLong())
        request.set(drmOutput.cursorPlaneId, cursorProps, "CRTC_Y", drmOutput.cursorY.toLong())
        request.set(drmOutput.cursorPlaneId, cursorProps, "CRTC_W", w)
        request.set(drmOutput.cursorPlaneId, cursorProps, "CRTC_H", h)
    }

    private fun testCursor(): Boolean {
        cursorTested = true // (so that cursorState includes it)
        val ok = drmOutput.device.commit(Drm.DRM_MODE_ATOMIC_TEST_ONLY()) { request -> cursorState(request) } == 0
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
        drmOutput.loop.after(0) {
            cursorAloneQueued = false
            cursorAlone()
        }
    }

    // A check for a cursor without a picture is waiting (see cursorMoved).
    private var cursorAloneQueued = false

    private fun commitCursor() {
        val serial = ++drmOutput.commitSerial
        val result = drmOutput.device.commit(Drm.DRM_MODE_ATOMIC_NONBLOCK() or Drm.DRM_MODE_PAGE_FLIP_EVENT(), serial) { request -> cursorState(request) }
        if (result == 0) {
            cursorDirty = false
            cursorOnlyPending = true
            drmOutput.flips.awaitFlip(serial)
        } else {
            drmOutput.log.trace { "$drmOutput.name: the cursor's move was refused (${drmOutput.errno(result)}); with the next picture" }
        }
    }

    // The cursor moved, and no picture is on its way to take it along: it goes on its own.
    internal fun cursorAlone() {
        if (cursorDirty && !drmOutput.flipPending && !drmOutput.needsModeset && drmOutput.powered && drmOutput.isSessionActive() && drmOutput.mode != null) commitCursor()
    }
}
