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
 * a [hold] (a browser asking over D-Bus), [inhibitedByUser], or — if [fullscreenInhibits] — a
 * fullscreen window keeps it from counting as idle. Something that starts keeping it awake
 * while it is idle already (a video started by itself) counts as someone back.
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
                    if (!ignoreInhibitors) watchInhibitors()
                }
            }
        }
    }

    private val watches = ArrayList<Watch>()
    private val inhibitors = HashMap<ZwpIdleInhibitorV1, Surface>()

    // Something outside Wayland keeping the computer awake: its words, and the program whose window must show (0: any time).
    private class Hold(val reason: String, val pid: Int)

    private val holds = ArrayList<Hold>()

    // Looks each second, while a watch is idle, whether something keeps the computer awake now.
    private var inhibitCheck: EventLoop.Timer? = null

    /** Programs' idle inhibitors (idle-inhibit: video players) count (true); false: they are ignored. */
    var protocolInhibitors = true

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
                    id.onDestroy {
                        inhibitors -= id
                        log.info { "${id.client.name} no longer keeps the screen on" }
                    }
                    // (A video started while idle already: as if someone were back.)
                    resumeIfInhibited()
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
        val close = watch(Integer.toUnsignedLong(timeout), ignoreInhibitors, onIdle = { if (id.isAlive) id.sendIdled() }, onResume = { if (id.isAlive) id.sendResumed() })
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

    /**
     * Idle inhibitors and windows whose rules say [WindowBehavior.keepsAwake] keep the computer awake
     * while the session is locked too (false by default: once locked, a paused video does not
     * keep the screen on). [inhibitedByUser] counts either way.
     */
    var inhibitorsWorkWhileLocked = false

    /** True while something keeps the computer from counting as idle. */
    fun inhibited(): Boolean = inhibitedBy().isNotEmpty()

    /**
     * What keeps the computer from counting as idle now, in words for people: "mpv" (its
     * idle-inhibit), a [hold]'s reason ("Firefox: video-playing"), "keep-awake", "mpv
     * (fullscreen)"; empty: nothing.
     */
    fun inhibitedBy(): List<String> {
        val why = ArrayList<String>()
        if (inhibitedByUser) why += "keep-awake"
        if (desktop.locked && !inhibitorsWorkWhileLocked) return why
        val shown = desktop.monitors.flatMap { it.workspace.windows }.toSet()
        if (protocolInhibitors) for ((inhibitor, surface) in inhibitors) if (isShown(surface, shown)) why += nameOf(surface) ?: inhibitor.client.program
        for (hold in holds) {
            if (hold.pid <= 0 || shown.any { it.pid == hold.pid && it.mapped && !desktop.isMinimized(it) }) why += hold.reason
        }
        // Windows whose rules keep the screen on (videos without idle-inhibit of their own).
        for (window in shown) if (window.mapped && !desktop.isMinimized(window) && desktop.behaviorOf(window).keepsAwake) why += "${label(window)} (rule)"
        if (fullscreenInhibits) for (window in shown) if (window.fullscreen && window.mapped) why += "${label(window)} (fullscreen)"
        return why.distinct()
    }

    /**
     * Keeps the computer from counting as idle until the result is closed — for what asks
     * outside Wayland (org.freedesktop.ScreenSaver.Inhibit over D-Bus, logind's idle
     * inhibitors). [reason] is shown to people ([inhibitedBy]). With [pid] above 0 it counts
     * only while a window of that process is shown (a player playing in a window). Like
     * idle-inhibit it counts for nothing while locked unless [inhibitorsWorkWhileLocked].
     */
    fun hold(reason: String, pid: Int = 0): AutoCloseable {
        val hold = Hold(reason, pid)
        holds += hold
        log.info { "Kept awake: $reason${if (pid > 0) " (while a window of process $pid shows)" else ""}" }
        resumeIfInhibited()
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                holds -= hold
                log.info { "No longer kept awake: $reason" }
            }
        }
    }

    // A window's name for people: its app id, else its title.
    private fun label(window: Window): String = window.appId.ifEmpty { window.title }.ifEmpty { "a window" }

    // The name of what [surface] belongs to (a window, a panel), or null.
    private fun nameOf(surface: Surface): String? {
        var root = surface
        while (true) root = root.subsurface?.parent ?: break
        return when (val role = root.roleObject) {
            is Window -> label(role)
            is LayerSurface -> role.namespace.ifEmpty { null }
            else -> null
        }
    }

    // While a watch inhibitors hold off is idle, each second: something keeping the computer
    // awake now (a video that started by itself, a browser's D-Bus inhibitor) counts as someone back.
    private fun watchInhibitors() {
        if (inhibitCheck != null || watches.none { it.idle && !it.ignoreInhibitors }) return
        inhibitCheck = loop.after(1000) {
            inhibitCheck = null
            resumeIfInhibited()
            watchInhibitors()
        }
    }

    // Idle watches that inhibitors hold off are told someone is back when something keeps the computer awake now.
    private fun resumeIfInhibited() {
        val idle = watches.filter { it.idle && !it.ignoreInhibitors }
        if (idle.isEmpty()) return
        val why = inhibitedBy()
        if (why.isEmpty()) return
        log.info { "Kept awake while idle (${why.joinToString()}): as if someone were back" }
        for (watch in idle) {
            watch.idle = false
            try {
                watch.onResume()
            } catch (e: Exception) {
                log.error("An idle listener failed", e)
            }
            watch.arm(from = System.nanoTime())
        }
    }

    // A window's surface on a workspace a monitor shows, or a panel's on a monitor that is on
    // (one never shown, or on a monitor gone or off, keeps nothing awake).
    private fun isShown(surface: Surface, shown: Set<Window>): Boolean {
        var root = surface
        while (true) root = root.subsurface?.parent ?: break
        return when (val role = root.roleObject) {
            is Window -> role in shown && !desktop.isMinimized(role)
            is LayerSurface -> role.mapped && desktop.monitorOfLayer(role)?.powered == true
            else -> false
        }
    }
}
