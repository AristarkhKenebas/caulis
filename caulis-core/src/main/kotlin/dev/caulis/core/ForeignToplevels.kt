package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.Display
import dev.caulis.protocol.extforeigntoplevellistv1.ExtForeignToplevelHandleV1
import dev.caulis.protocol.extforeigntoplevellistv1.ExtForeignToplevelListV1
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface
import dev.caulis.protocol.wlrforeigntoplevelmanagementunstablev1.ZwlrForeignToplevelHandleV1
import dev.caulis.protocol.wlrforeigntoplevelmanagementunstablev1.ZwlrForeignToplevelManagerV1
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The window list for docks and taskbars (Waybar's taskbar, nwg-dock, sfwbar, a dock of your
 * own, ...):
 *
 * - wlr-foreign-toplevel-management: every window with its title, app id and state; the
 *   dock can activate, minimize, maximize, make fullscreen and close them, and tell where
 *   its icon for a window is, so the window minimizes into that icon.
 * - ext-foreign-toplevel-list: the same list, only to read (newer tools use it).
 *
 * Whenever the windows change, each dock is told exactly what changed for each window.
 */
class ForeignToplevels(private val display: Display, private val desktop: Desktop) {

    /**
     * Which programs may read the list of windows (titles and app ids of every window, also
     * through ext-foreign-toplevel-list, which screen sharing uses to pick a window). By
     * default the [privileged][Display.privileged] ones, as for docks that manage windows.
     */
    var mayListWindows: (dev.caulis.protocol.Client) -> Boolean = { client -> display.privileged(client) }

    // What a dock was last told about a window.
    private data class Facts(val title: String, val appId: String, val states: Set<Int>, val parent: Window?, val output: Output?)

    private class WlrHandle(val resource: ZwlrForeignToplevelHandleV1) {
        var told: Facts? = null
        val outputs = HashSet<WlOutput>()
    }

    private class ExtHandle(val resource: ExtForeignToplevelHandleV1) {
        var told: Facts? = null
    }

    private val wlr = LinkedHashMap<ZwlrForeignToplevelManagerV1, LinkedHashMap<Window, WlrHandle>>()
    private val ext = LinkedHashMap<ExtForeignToplevelListV1, LinkedHashMap<Window, ExtHandle>>()

    // A name for each window that never changes and is never used again (ext-foreign-toplevel-list).
    private val identifiers = java.util.WeakHashMap<Window, String>()
    private var nextIdentifier = 1L

    init {
        display.addGlobal(ZwlrForeignToplevelManagerV1, WLR_VERSION, { client -> display.privileged(client) && mayListWindows(client) }) { manager ->
            log.info { "${manager.client.name} watches the windows (a dock or taskbar)" }
            wlr[manager] = LinkedHashMap()
            manager.requests = object : ZwlrForeignToplevelManagerV1.Requests {
                override fun stop(resource: ZwlrForeignToplevelManagerV1) {
                    if (wlr.remove(resource) != null) resource.sendFinished()
                }
            }
            manager.onDestroy { wlr.remove(manager) }
            sync()
        }
        // (The titles of every window: not for any program, see mayListWindows.)
        display.addGlobal(ExtForeignToplevelListV1, EXT_VERSION, { client -> mayListWindows(client) }) { list ->
            log.info { "${list.client.name} reads the window list" }
            ext[list] = LinkedHashMap()
            list.requests = object : ExtForeignToplevelListV1.Requests {
                override fun stop(resource: ExtForeignToplevelListV1) {
                    if (ext.remove(resource) != null) resource.sendFinished()
                }

                override fun destroy(resource: ExtForeignToplevelListV1) {}
            }
            list.onDestroy { ext.remove(list) }
            sync()
        }
        desktop.onWindowListChanged(::sync)
    }

    /** The window an ext-foreign-toplevel-list handle stands for (e.g. to share it), if it is still there. */
    internal fun windowOf(handle: ExtForeignToplevelHandleV1): Window? =
        ext.values.firstNotNullOfOrNull { handles -> handles.entries.firstOrNull { it.value.resource === handle }?.key }
            ?.takeIf { it in desktop.allWindows }

    private fun factsOf(window: Window): Facts {
        val states = buildSet {
            if (window.maximized) add(STATE_MAXIMIZED)
            if (desktop.isMinimized(window)) add(STATE_MINIMIZED)
            if (window === desktop.focusedWindow) add(STATE_ACTIVATED)
            if (window.fullscreen) add(STATE_FULLSCREEN)
        }
        return Facts(window.title, window.appId, states, window.parent?.takeIf { it.surface.resource.isAlive }, desktop.monitorOf(window).output)
    }

    /** Tells every dock what changed since it was last told. */
    fun sync() {
        val windows = desktop.allWindows
        val present = windows.toSet()
        for ((manager, handles) in wlr.entries.toList()) {
            if (!manager.isAlive) continue
            // New windows first, so a dialog's parent is known when it is mentioned.
            for (window in windows) if (window !in handles) handles[window] = announce(manager, window)
            for ((window, handle) in handles.entries.toList()) {
                if (window !in present) {
                    if (handle.resource.isAlive) handle.resource.sendClosed()
                    handles -= window
                } else if (handle.resource.isAlive) {
                    tell(handle, factsOf(window), handles, manager.client)
                }
            }
        }
        for ((list, handles) in ext.entries.toList()) {
            if (!list.isAlive) continue
            for (window in windows) if (window !in handles) handles[window] = announce(list, window)
            for ((window, handle) in handles.entries.toList()) {
                if (window !in present) {
                    if (handle.resource.isAlive) handle.resource.sendClosed()
                    handles -= window
                } else if (handle.resource.isAlive) {
                    tell(handle, factsOf(window))
                }
            }
        }
    }

    // --- wlr-foreign-toplevel-management --------------------------------------------------

    private fun announce(manager: ZwlrForeignToplevelManagerV1, window: Window): WlrHandle {
        val resource = manager.client.createServerResource(ZwlrForeignToplevelHandleV1, manager.version)
        val ask = { what: String -> log.info { "${manager.client.name} asks: $what $window" } }
        resource.requests = object : ZwlrForeignToplevelHandleV1.Requests {
            override fun setMaximized(resource: ZwlrForeignToplevelHandleV1) = act(window) { ask("maximize"); desktop.setMaximized(it, true) }
            override fun unsetMaximized(resource: ZwlrForeignToplevelHandleV1) = act(window) { ask("unmaximize"); desktop.setMaximized(it, false) }
            override fun setMinimized(resource: ZwlrForeignToplevelHandleV1) = act(window) { ask("minimize"); desktop.minimize(it) }
            override fun unsetMinimized(resource: ZwlrForeignToplevelHandleV1) = act(window) { ask("restore"); desktop.focus(it) }

            override fun activate(resource: ZwlrForeignToplevelHandleV1, seat: WlSeat) = act(window) {
                ask("activate")
                if (!desktop.locked) desktop.focus(it)
            }

            override fun close(resource: ZwlrForeignToplevelHandleV1) = act(window) { ask("close"); it.close() }

            override fun setRectangle(resource: ZwlrForeignToplevelHandleV1, surface: WlSurface, x: Int, y: Int, width: Int, height: Int) {
                if (width < 0 || height < 0) {
                    resource.postError(ZwlrForeignToplevelHandleV1.Error.INVALID_RECTANGLE, "a rectangle cannot be ${width}x$height")
                    return
                }
                act(window) { desktop.setMinimizeTarget(it, surface.dataAs<Surface>(), Rect(x, y, width, height).takeUnless { r -> r.isEmpty }) }
            }

            override fun destroy(resource: ZwlrForeignToplevelHandleV1) {}

            override fun setFullscreen(resource: ZwlrForeignToplevelHandleV1, output: WlOutput?) = act(window) { ask("fullscreen"); desktop.setFullscreen(it, true) }
            override fun unsetFullscreen(resource: ZwlrForeignToplevelHandleV1) = act(window) { ask("leave fullscreen"); desktop.setFullscreen(it, false) }
        }
        manager.sendToplevel(resource)
        return WlrHandle(resource)
    }

    // A dock's request for a window that may have closed meanwhile.
    private fun act(window: Window, action: (Window) -> Unit) {
        if (window in desktop.allWindows) action(window) else log.trace { "A dock asked for $window, which is gone" }
    }

    private fun tell(handle: WlrHandle, facts: Facts, handles: Map<Window, WlrHandle>, client: dev.caulis.protocol.Client) {
        val resource = handle.resource
        val before = handle.told
        var changed = false
        if (before?.title != facts.title) {
            resource.sendTitle(facts.title)
            changed = true
        }
        if (before?.appId != facts.appId) {
            resource.sendAppId(facts.appId)
            changed = true
        }
        if (before?.states != facts.states) {
            // Fullscreen exists since version 2.
            val states = if (resource.version >= 2) facts.states else facts.states - STATE_FULLSCREEN
            resource.sendState(uints(states.sorted()))
            changed = true
        }
        if (resource.version >= 3 && (before == null || before.parent !== facts.parent)) {
            if (before != null || facts.parent != null) {
                resource.sendParent(facts.parent?.let { handles[it]?.resource })
                changed = true
            }
        }
        // The monitor it is on (docks on each monitor show its windows); it may move.
        val on: Set<WlOutput> = (facts.output?.let { listOf(it) } ?: Output.of(display)).flatMap { it.resourcesOf(client) }.toSet()
        for (output in handle.outputs.toList()) {
            if (output in on && output.isAlive) continue
            handle.outputs -= output
            if (output.isAlive) resource.sendOutputLeave(output)
            changed = true
        }
        for (output in on) {
            if (handle.outputs.add(output)) {
                resource.sendOutputEnter(output)
                changed = true
            }
        }
        if (changed) {
            log.trace { "Telling ${client.name}: $facts" }
            resource.sendDone()
        }
        handle.told = facts
    }

    // --- ext-foreign-toplevel-list ----------------------------------------------------------

    private fun announce(list: ExtForeignToplevelListV1, window: Window): ExtHandle {
        val resource = list.client.createServerResource(ExtForeignToplevelHandleV1, list.version)
        resource.requests = object : ExtForeignToplevelHandleV1.Requests {
            override fun destroy(resource: ExtForeignToplevelHandleV1) {}
        }
        list.sendToplevel(resource)
        resource.sendIdentifier(identifiers.getOrPut(window) { "caulis-${nextIdentifier++}" })
        return ExtHandle(resource)
    }

    private fun tell(handle: ExtHandle, facts: Facts) {
        val before = handle.told
        var changed = before == null // the identifier was sent
        if (before?.title != facts.title) {
            handle.resource.sendTitle(facts.title)
            changed = true
        }
        if (before?.appId != facts.appId) {
            handle.resource.sendAppId(facts.appId)
            changed = true
        }
        if (changed) handle.resource.sendDone()
        handle.told = facts
    }

    private fun uints(values: List<Int>): ByteArray {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
        values.forEach { bytes.putInt(it) }
        return bytes.array()
    }

    private val log = dev.caulis.log.Logger("docks")

    companion object {
        const val WLR_VERSION = 3
        const val EXT_VERSION = 1

        private const val STATE_MAXIMIZED = ZwlrForeignToplevelHandleV1.State.MAXIMIZED
        private const val STATE_MINIMIZED = ZwlrForeignToplevelHandleV1.State.MINIMIZED
        private const val STATE_ACTIVATED = ZwlrForeignToplevelHandleV1.State.ACTIVATED
        private const val STATE_FULLSCREEN = ZwlrForeignToplevelHandleV1.State.FULLSCREEN
    }
}
