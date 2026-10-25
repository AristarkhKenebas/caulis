package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.log.Logger
import dev.caulis.protocol.Display
import dev.caulis.protocol.extidlenotifyv1.ExtIdleNotificationV1
import dev.caulis.protocol.extidlenotifyv1.ExtIdleNotifierV1
import dev.caulis.protocol.idleinhibitunstablev1.ZwpIdleInhibitManagerV1
import dev.caulis.protocol.idleinhibitunstablev1.ZwpIdleInhibitorV1
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface

/**
 * When nobody uses the computer: programs ([watch], and swayidle or hypridle through
 * ext-idle-notify) are told after so long without a key, a click or the pointer moving, and
 * again when someone is back. A video player that keeps a window on screen (idle-inhibit),
 * [inhibitedByUser], or — if [fullscreenInhibits] — a fullscreen window keeps it from
 * counting as idle.
 */
class Idle(display: Display, private val loop: EventLoop, private val desktop: Desktop) {

    private val log = Logger("idle")
    private var lastActivity = System.nanoTime()

    private inner class Watch(val timeoutMillis: Long, val ignoreInhibitors: Boolean, val onIdle: () -> Unit, val onResume: () -> Unit) {
        var idle = false
        var timer: EventLoop.Timer? = null
        var closed = false

        // Wakes up when it would be idle (if nobody did anything meanwhile).
        fun arm(from: Long = lastActivity) {
            timer?.cancel()
            if (closed) return
            val dueIn = ((from + timeoutMillis * 1_000_000 - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
            timer = loop.after(dueIn) { check() }
        }

        fun check() {
            timer = null
            if (closed || idle) return
            val now = System.nanoTime()
            when {
                now - lastActivity < timeoutMillis * 1_000_000 -> arm()
                // Something keeps it awake: try again a whole timeout later.
                !ignoreInhibitors && inhibited() -> arm(from = now)
                else -> {
                    idle = true
                    onIdle()
                }
            }
        }
    }

    private val watches = ArrayList<Watch>()
    private val inhibitors = HashMap<ZwpIdleInhibitorV1, Surface>()

    /** True: nothing counts as idle (a "keep awake" switch, newm's inhibit-idle). */
    var inhibitedByUser = false

    /** A fullscreen window on screen keeps it from counting as idle (videos, games). */
    var fullscreenInhibits = false

    init {
        display.addGlobal(ZwpIdleInhibitManagerV1, 1) { manager ->
            manager.requests = object : ZwpIdleInhibitManagerV1.Requests {
                override fun createInhibitor(resource: ZwpIdleInhibitManagerV1, id: ZwpIdleInhibitorV1, surface: WlSurface) {
                    val target = surface.dataAs<Surface>() ?: return
                    inhibitors[id] = target
                    log.info { "${id.client.name} keeps the screen on while its window is shown" }
                    id.requests = object : ZwpIdleInhibitorV1.Requests {
                        override fun destroy(resource: ZwpIdleInhibitorV1) {}
                    }
                    id.onDestroy { inhibitors -= id }
                }

                override fun destroy(resource: ZwpIdleInhibitManagerV1) {}
            }
        }
        display.addGlobal(ExtIdleNotifierV1, 2) { notifier ->
            notifier.requests = object : ExtIdleNotifierV1.Requests {
                override fun getIdleNotification(resource: ExtIdleNotifierV1, id: ExtIdleNotificationV1, timeout: Int, seat: WlSeat) = serve(id, timeout, ignoreInhibitors = false)
                override fun getInputIdleNotification(resource: ExtIdleNotifierV1, id: ExtIdleNotificationV1, timeout: Int, seat: WlSeat) = serve(id, timeout, ignoreInhibitors = true)
                override fun destroy(resource: ExtIdleNotifierV1) {}
            }
        }
    }

    private fun serve(id: ExtIdleNotificationV1, timeout: Int, ignoreInhibitors: Boolean) {
        id.requests = object : ExtIdleNotificationV1.Requests {
            override fun destroy(resource: ExtIdleNotificationV1) {}
        }
        log.info { "${id.client.name} wants to know after ${timeout / 1000.0} s without input" }
        val close = watch(timeout.toLong().coerceAtLeast(0), ignoreInhibitors, onIdle = { if (id.isAlive) id.sendIdled() }, onResume = { if (id.isAlive) id.sendResumed() })
        id.onDestroy { close.close() }
    }

    /**
     * Runs [onIdle] after [timeoutMillis] without input, and [onResume] at the first input
     * after that. Close the result to stop.
     */
    fun watch(timeoutMillis: Long, ignoreInhibitors: Boolean = false, onIdle: () -> Unit, onResume: () -> Unit = {}): AutoCloseable {
        val watch = Watch(timeoutMillis, ignoreInhibitors, onIdle, onResume)
        watches += watch
        watch.arm(from = System.nanoTime())
        return AutoCloseable {
            watch.closed = true
            watch.timer?.cancel()
            watches -= watch
        }
    }

    /** Someone did something (a key, a click, the pointer, a touch): not idle any more. */
    fun activity() {
        lastActivity = System.nanoTime()
        for (watch in watches.toList()) {
            if (!watch.idle) continue
            watch.idle = false
            log.trace { "Back after idle" }
            try {
                watch.onResume()
            } catch (e: Exception) {
                log.error("An idle listener failed", e)
            }
            watch.arm()
        }
    }

    /** True while something keeps the computer from counting as idle. */
    fun inhibited(): Boolean {
        if (inhibitedByUser) return true
        val shown = desktop.monitors.flatMap { it.workspace.windows }.toSet()
        if (inhibitors.values.any { surface -> isShown(surface, shown) }) return true
        // Windows whose rules keep the screen on (videos without idle-inhibit of their own).
        if (shown.any { it.mapped && !desktop.isMinimized(it) && desktop.behaviorOf(it).keepsAwake }) return true
        return fullscreenInhibits && shown.any { it.fullscreen && it.mapped }
    }

    // A window's surface on a workspace a monitor shows (a panel's always counts).
    private fun isShown(surface: Surface, shown: Set<Window>): Boolean {
        var root = surface
        while (true) root = root.subsurface?.parent ?: break
        return when (val role = root.roleObject) {
            is Window -> role in shown && !desktop.isMinimized(role)
            is LayerSurface -> true
            else -> false
        }
    }
}
