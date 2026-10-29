package dev.caulis.core

import dev.caulis.protocol.Display
import dev.caulis.protocol.extsessionlockv1.ExtSessionLockManagerV1
import dev.caulis.protocol.extsessionlockv1.ExtSessionLockSurfaceV1
import dev.caulis.protocol.extsessionlockv1.ExtSessionLockV1
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlSurface

/**
 * ext-session-lock: screen lockers (swaylock, gtklock, hyprlock, ...). While the session
 * is locked, nothing of the desktop is shown and all input goes to the locker; only the
 * locker can unlock, after the user proved who they are. If the locker crashes, the
 * session stays locked — a new locker can take over.
 *
 * This class speaks the protocol; [listener] (the window manager) decides what is shown.
 */
class SessionLockManager(display: Display) {

    interface Listener {
        /**
         * A program wants to lock the session. Return true to lock (hide everything now and
         * call [SessionLock.confirmLocked] once that is on screen), false to refuse.
         */
        fun lockRequested(lock: SessionLock): Boolean = false

        /** A locker's surface committed (also its first picture). */
        fun lockSurfaceCommitted(surface: LockSurface) {}

        /** How big a lock surface on [output] is (desktop units): its monitor's size. */
        fun lockSurfaceSize(output: Output?): Pair<Int, Int> = (output?.width ?: 0) to (output?.height ?: 0)

        /** The locker unlocks (the user typed the right password), or gave up before the lock took hold. */
        fun unlockRequested(lock: SessionLock) {}

        /** The locker is gone without unlocking (it crashed): the session must stay locked. */
        fun lockAbandoned(lock: SessionLock) {}
    }

    var listener: Listener = object : Listener {}

    init {
        display.addPrivilegedGlobal(ExtSessionLockManagerV1, VERSION, "lock") { manager ->
            manager.requests = object : ExtSessionLockManagerV1.Requests {
                override fun lock(resource: ExtSessionLockManagerV1, id: ExtSessionLockV1) {
                    val lock = SessionLock(id, this@SessionLockManager)
                    if (!listener.lockRequested(lock)) lock.refuse()
                }

                override fun destroy(resource: ExtSessionLockManagerV1) {}
            }
        }
    }

    companion object {
        const val VERSION = 1
    }
}

/** One locker's attempt to lock the session (ext_session_lock_v1). */
class SessionLock internal constructor(val resource: ExtSessionLockV1, internal val manager: SessionLockManager) {

    // The locker's surfaces, one per monitor (see surfaces).
    private val surfaceList = ArrayList<LockSurface>()

    /** The locker's surfaces, one per monitor (a live, read-only view). */
    val surfaces: List<LockSurface> = java.util.Collections.unmodifiableList(surfaceList)

    /** True once the locker was told the session is locked. */
    var confirmed = false
        private set

    // Accepted by the window manager (refused ones only get "finished").
    private var accepted = true

    // Unlocked or given up properly (anything else that ends it is a crash).
    private var ended = false

    init {
        resource.data = this
        resource.requests = object : ExtSessionLockV1.Requests {
            override fun getLockSurface(resource: ExtSessionLockV1, id: ExtSessionLockSurfaceV1, surface: WlSurface, output: WlOutput) {
                val target = surface.dataAs<Surface>() ?: return
                val monitor = output.dataAs<Output>()
                if (surfaces.any { it.wlOutput === output }) {
                    resource.postError(ExtSessionLockV1.Error.DUPLICATE_OUTPUT, "this output already has a lock surface")
                    return
                }
                if (target.current.buffer != null || target.pending.buffer != null) {
                    resource.postError(ExtSessionLockV1.Error.ALREADY_CONSTRUCTED, "the surface already has a buffer")
                    return
                }
                if (!target.assignRole(Surface.Role.LOCK, resource, ExtSessionLockV1.Error.ROLE)) return
                val lockSurface = LockSurface(id, target, output, this@SessionLock)
                surfaceList += lockSurface
                id.onDestroy { surfaceList -= lockSurface }
                // As big as its monitor (or the desktop until monitors are known).
                val (width, height) = manager.listener.lockSurfaceSize(monitor)
                lockSurface.configure(width, height)
            }

            override fun destroy(resource: ExtSessionLockV1) {
                if (confirmed) {
                    resource.postError(ExtSessionLockV1.Error.INVALID_DESTROY, "the session is locked; use unlock_and_destroy")
                    return
                }
                // Given up before the lock took hold (or after being refused): nothing to protect yet.
                ended = true
                if (accepted) manager.listener.unlockRequested(this@SessionLock)
            }

            override fun unlockAndDestroy(resource: ExtSessionLockV1) {
                if (!confirmed) {
                    resource.postError(ExtSessionLockV1.Error.INVALID_UNLOCK, "the session was never locked")
                    return
                }
                ended = true
                manager.listener.unlockRequested(this@SessionLock)
            }
        }
        resource.onDestroy {
            if (!ended && accepted) manager.listener.lockAbandoned(this)
        }
    }

    /** Tells the locker the session is locked (call once nothing else is visible on any screen). */
    fun confirmLocked() {
        if (confirmed || !resource.isAlive) return
        confirmed = true
        resource.sendLocked()
    }

    /** True while the locker program is still there. */
    val alive: Boolean get() = resource.isAlive && !ended

    internal fun refuse() {
        accepted = false
        resource.sendFinished()
    }
}

/** A locker's picture for one monitor (ext_session_lock_surface_v1). */
class LockSurface internal constructor(
    val resource: ExtSessionLockSurfaceV1,
    val surface: Surface,
    internal val wlOutput: WlOutput,
    val lock: SessionLock,
) {
    /** The monitor it covers, if known. */
    val output: Output? get() = wlOutput.dataAs<Output>()

    /** True while it shows a picture. */
    var mapped = false
        private set

    private var serial = 0
    private val sent = ArrayDeque<Triple<Int, Int, Int>>() // serial, width, height not yet acknowledged
    private var acknowledged: Pair<Int, Int>? = null // the size the locker agreed to draw

    init {
        resource.data = this
        surface.roleObject = this
        resource.requests = object : ExtSessionLockSurfaceV1.Requests {
            override fun ackConfigure(resource: ExtSessionLockSurfaceV1, serial: Int) {
                val index = sent.indexOfFirst { it.first == serial }
                if (index < 0) {
                    resource.postError(ExtSessionLockSurfaceV1.Error.INVALID_SERIAL, "no configure with serial $serial")
                    return
                }
                val (_, width, height) = sent[index]
                repeat(index + 1) { sent.removeFirst() } // older ones are answered too
                acknowledged = width to height
            }

            override fun destroy(resource: ExtSessionLockSurfaceV1) {}
        }
        surface.onCommit { committed() }
    }

    /** Tells the locker the size to draw (its monitor's). */
    fun configure(width: Int, height: Int) {
        if (!resource.isAlive) return
        serial++
        sent.addLast(Triple(serial, width, height))
        resource.sendConfigure(serial, width, height)
    }

    private fun committed() {
        if (!resource.isAlive) return
        val size = acknowledged
        val hasBuffer = surface.current.buffer != null
        when {
            hasBuffer && size == null ->
                resource.postError(ExtSessionLockSurfaceV1.Error.COMMIT_BEFORE_FIRST_ACK, "a buffer was committed before the first configure was acknowledged")
            !hasBuffer && size != null ->
                resource.postError(ExtSessionLockSurfaceV1.Error.NULL_BUFFER, "a lock surface must always show a picture")
            size != null && (surface.width != size.first || surface.height != size.second) ->
                resource.postError(ExtSessionLockSurfaceV1.Error.DIMENSIONS_MISMATCH, "the picture is ${surface.width}x${surface.height}, not ${size.first}x${size.second}")
            else -> {
                mapped = surface.hasContent
                lock.manager.listener.lockSurfaceCommitted(this)
            }
        }
    }

    override fun toString() = "LockSurface(${output?.name})"
}

/** Which window gets the focus when the session is unlocked ([Desktop.unlockFocus]). */
enum class UnlockFocus {
    /**
     * The window that had the focus when the session was locked; if it is gone, the newest
     * window of those open before the lock.
     */
    FOCUSED_BEFORE,

    /** The newest window of those open before the lock. */
    NEWEST_BEFORE,
}
