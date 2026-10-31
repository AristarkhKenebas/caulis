package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Layout
import kotlin.math.roundToInt

/**
 * Scratchpads and special workspaces: windows kept apart that come and go on top of the rest,
 * with their looks, dim and focus.
 */
internal class DesktopScratchpads(private val desktop: Desktop) {

    internal val scratchpads = LinkedHashMap<String, Scratchpad>()

    // Scratchpads on screen (or leaving it), the last shown on top.
    internal val shownPads = ArrayList<Scratchpad>()

    // The scratchpad window with the keyboard, if any.
    internal var padFocus: Window? = null

    // The special workspace used last (shown, moved into, a window of it focused).
    internal var lastSpecial: Scratchpad? = null

    fun addScratchpad(name: String, style: Scratchpad.Style, match: (Window) -> Boolean): Scratchpad {
        require(name !in scratchpads) { "There is already a scratchpad named \"$name\"" }
        return Scratchpad(name, style, match).also { scratchpads[name] = it }
    }

    // The scratchpad a new window belongs to: by the scratchpads' rules, a rule sending it to a
    // special workspace, or the special workspace shown where it opens.
    internal fun scratchpadFor(window: Window): Scratchpad? {
        scratchpads.values.firstOrNull { pad -> userCode("Your scratchpad ${pad.name}'s match", false) { pad.match(window) } }?.let { return it }
        desktop.arranger.placementOf(window).specialWorkspace?.let { name ->
            // (A bad name from a rule: logged, and the window opens as usual — not refused.)
            try {
                return specialWorkspace(name)
            } catch (e: IllegalArgumentException) {
                desktop.layoutLog.warn("$window: your rule's special workspace is no good (${e.message}); it opens as usual")
            }
        }
        if (!desktop.specialWorkspaces.newWindowsJoin) return null
        val shown = desktop.shownSpecialOn(workMonitor) ?: return null
        // (A dialog of a window elsewhere stays with its window.)
        val parent = window.parent
        return if (parent == null || desktop.scratchpadOf(parent) === shown) shown else null
    }

    internal val specials = LinkedHashMap<String, Scratchpad>()

    // Scratchpads and special workspaces together.
    internal val allPads: List<Scratchpad> get() = scratchpads.values + specials.values

    fun specialWorkspace(name: String = SpecialWorkspaces.DEFAULT_NAME): Scratchpad {
        val key = specialName(name)
        return specials.getOrPut(key) {
            desktop.layoutLog.info { "Special workspace $key made" }
            Scratchpad(key, Scratchpad.Overlay(specialLayout(), desktop.specialWorkspaces.dim), { false }, isSpecial = true)
        }
    }

    // "special:music" (Hyprland's way) and "music" are the same; "" or "special" the one without a name.
    private fun specialName(name: String): String {
        val plain = name.trim().removePrefix("special:").trim()
        require(plain.none { it.isWhitespace() }) { "a special workspace's name is one word, not \"$name\"" }
        return plain.ifEmpty { SpecialWorkspaces.DEFAULT_NAME }
    }

    // The layout its windows are tiled with now.
    private fun specialLayout(): dev.caulis.tiling.Layout = desktop.specialWorkspaces.layout ?: desktop.layout

    fun toggleSpecialWorkspace(name: String = SpecialWorkspaces.DEFAULT_NAME): Boolean {
        val pad = specialWorkspace(name)
        if (pad.shown && !pad.leaving) {
            // Shown on another monitor: it comes over here instead.
            if (padMonitor(pad) !== workMonitor) return showSpecial(pad)
            hideScratchpad(pad)
            return false
        }
        return showSpecial(pad)
    }

    fun showSpecialWorkspace(name: String = SpecialWorkspaces.DEFAULT_NAME) {
        val pad = specialWorkspace(name)
        if (!pad.shown || pad.leaving || padMonitor(pad) !== workMonitor) showSpecial(pad)
    }

    fun hideSpecialWorkspace(name: String = SpecialWorkspaces.DEFAULT_NAME) {
        val pad = specials[specialName(name)] ?: return
        if (pad.shown && !pad.leaving) hideScratchpad(pad)
    }

    // Shows [pad] on the focused monitor (moving it here if it is shown on another one).
    // Not while locked if special workspaces hide on the lock: returns false then.
    internal fun showSpecial(pad: Scratchpad): Boolean {
        if (desktop.locked && desktop.specialWorkspaces.hideOnLock) {
            desktop.layoutLog.info { "Special workspace ${pad.name} not shown: the session is locked" }
            return false
        }
        // One special workspace per monitor: the one shown there goes.
        for (other in shownPads.toList()) if (other !== pad && other.isSpecial && !other.leaving && padMonitor(other) === workMonitor) hideScratchpad(other, focusBack = false)
        // Shown on another monitor: it moves over here (keeping the window to go back to there).
        if (pad.shown && !pad.leaving && padMonitor(pad) !== workMonitor) pad.monitor = workMonitor
        desktop.layoutLog.info { "Special workspace ${pad.name} shown on ${workMonitor.name}" }
        lastSpecial = pad
        showScratchpad(pad, appearing = pad.windows.toSet())
        return true
    }

    fun moveToSpecialWorkspace(window: Window, name: String = SpecialWorkspaces.DEFAULT_NAME, silent: Boolean = false) {
        val pad = specialWorkspace(name)
        val before = desktop.scratchpadOf(window)
        if (before === pad) {
            if (!silent && (!pad.shown || pad.leaving)) showSpecial(pad)
            return
        }
        if (before != null) {
            leavePad(before, window)
        } else {
            val from = desktop.workspaceOf(window) ?: return
            if (window.fullscreen) desktop.setFullscreen(window, false)
            if (window.maximized) desktop.setMaximized(window, false)
            desktop.arranger.pinned -= window
            from.remove(window)
            from.model.forget(window)
            desktop.arranger.pending.remove(window)
            desktop.arranger.arrange(from)
        }
        pad.windows += window
        lastSpecial = pad
        desktop.layoutLog.info { "$window moved into the special workspace ${pad.name}${if (silent) " (silently)" else ""}" }
        val hadFocus = window === desktop.focusedWindow
        if (pad.shown && !pad.leaving) {
            placeScratchpad(pad, appearing = emptySet())
            if (!silent || hadFocus) desktop.focus(window)
        } else if (!silent) {
            showSpecial(pad)
            desktop.focus(window)
        } else {
            // Waiting there unseen: drawn nowhere, the focus goes on.
            desktop.animator.glides -= window
            if (hadFocus) desktop.focuser.passFocusOn(desktop.windows.lastOrNull())
        }
        desktop.events.windowListChanged()
        desktop.changed()
    }

    fun moveOutOfSpecialWorkspace(window: Window): Boolean {
        val pad = desktop.scratchpadOf(window) ?: return false
        leavePad(pad, window)
        val workspace = desktop.focusedMonitor.workspace
        workspace.add(window)
        window.tiled = workspace.layout !== dev.caulis.tiling.Floating && !desktop.arranger.floats(window)
        if (!window.tiled) {
            workspace.floating += window
            val visible = window.visibleRect
            val place = desktop.arranger.floatingRects[window] ?: desktop.arranger.cascadeRect(visible.width, visible.height, desktop.focusedMonitor.area)
            window.x = place.x
            window.y = place.y
            window.configure(place.width, place.height)
        }
        desktop.arranger.arrange(workspace)
        desktop.layoutLog.info { "$window moved out of ${pad.name} onto workspace ${workspace.number}" }
        desktop.focus(window)
        desktop.events.windowListChanged()
        desktop.changed()
        return true
    }

    fun moveOutOfSpecialWorkspace(): Window? {
        val focused = desktop.focusedWindow?.takeIf { desktop.scratchpadOf(it)?.isSpecial == true }
        val pad = desktop.shownSpecialOn(workMonitor) ?: lastSpecial?.takeIf { it in specials.values }
        // (Its windows are oldest first, the one focused last at the end.)
        val window = focused ?: pad?.windows?.lastOrNull()
        if (window == null) {
            desktop.layoutLog.info { "Nothing to move out of a special workspace (${pad?.let { "${it.name} is empty" } ?: "none used yet"})" }
            return null
        }
        if (focused == null) desktop.layoutLog.info { "No window of a special workspace focused: $window (focused last in ${pad?.name}) moves out" }
        return window.takeIf { moveOutOfSpecialWorkspace(it) }
    }

    fun dragInSpecialWorkspace(window: Window, dx: Int, dy: Int) {
        val pad = desktop.scratchpadOf(window)?.takeIf { it.isSpecial && it.shown && !it.leaving } ?: return
        val tile = padTargets(pad)[window] ?: return
        desktop.animator.glides -= window
        window.x = tile.x + dx
        window.y = tile.y + dy
        desktop.changed()
    }

    fun dropFromSpecialWorkspace(window: Window, x: Double, y: Double): Boolean {
        val pad = desktop.scratchpadOf(window)?.takeIf { it.isSpecial } ?: return false
        val area = specialArea(pad)
        val inside = x >= area.x && x < area.right && y >= area.y && y < area.bottom
        if (inside || !pad.shown || pad.leaving) {
            if (pad.shown && !pad.leaving) placeScratchpad(pad, appearing = emptySet())
            return false
        }
        desktop.layoutLog.info { "$window dragged out of the special workspace ${pad.name}" }
        val there = desktop.monitorAt(x, y)
        if (there !== desktop.focusedMonitor) desktop.setFocusedMonitor(there)
        return moveOutOfSpecialWorkspace(window)
    }

    // [window] leaves [pad]: the others take its place; an empty special workspace may hide.
    private fun leavePad(pad: Scratchpad, window: Window) {
        pad.windows -= window
        pad.workspace.model.forget(window)
        desktop.animator.glides -= window
        if (padFocus === window) padFocus = null
        if (!pad.shown || pad.leaving) return
        if (pad.windows.isEmpty() && (!pad.isSpecial || desktop.specialWorkspaces.hideWhenEmpty)) hideScratchpad(pad, focusBack = false)
        else placeScratchpad(pad, appearing = emptySet())
    }

    // The monitor a shown scratchpad is on.
    internal fun padMonitor(pad: Scratchpad): Monitor = pad.monitor?.takeIf { it in desktop.monitorList } ?: workMonitor

    // Where special workspaces and scratchpads show: the director's monitor worked on, if it says one.
    private var lastWorkMonitor: Monitor? = null

    private val workMonitor: Monitor
        get() {
            val d = desktop.director ?: return desktop.focusedMonitor
            val m = userCode("Your WindowDirector (workMonitor)", null) { d.workMonitor() }?.takeIf { it in desktop.monitorList } ?: desktop.focusedMonitor
            if (m !== lastWorkMonitor) {
                lastWorkMonitor = m
                desktop.layoutLog.info { "Monitor worked on (special workspaces): ${m.name}" }
            }
            return m
        }

    // Shown special workspaces hide when the session locks (if the settings say so).
    internal fun hideSpecialsForLock() {
        if (!desktop.specialWorkspaces.hideOnLock) return
        for (pad in shownPads.toList()) if (pad.isSpecial && !pad.leaving) {
            desktop.layoutLog.info { "Special workspace ${pad.name} hidden: the screen locks" }
            // (At once: nothing of the session goes on showing on a lock.)
            hideScratchpad(pad, focusBack = false, atOnce = true)
        }
    }

    fun toggleScratchpad(name: String): Boolean {
        val pad = scratchpads[name] ?: return false
        if (pad.windows.isEmpty()) return false
        if (pad.shown && !pad.leaving) hideScratchpad(pad) else showScratchpad(pad, appearing = pad.windows.toSet())
        return true
    }

    fun moveToScratchpad(window: Window, name: String) {
        val pad = scratchpads[name] ?: return
        val from = desktop.workspaceOf(window) ?: return
        from.remove(window)
        from.model.forget(window)
        desktop.arranger.pending.remove(window)
        desktop.arranger.arrange(from)
        pad.windows += window
        showScratchpad(pad, appearing = emptySet())
    }

    // The monitor area a scratchpad is shown in.
    internal fun padArea(pad: Scratchpad): Rect = padMonitor(pad).area

    // Where a special workspace's windows are tiled: inside its gap, as big as its size says.
    internal fun specialArea(pad: Scratchpad): Rect {
        val area = padMonitor(pad).usableArea
        val settings = desktop.specialWorkspaces
        val gap = settings.gap.coerceAtMost(minOf(area.width, area.height) / 4)
        val w = ((area.width - 2 * gap) * settings.size).roundToInt().coerceAtLeast(1)
        val h = ((area.height - 2 * gap) * settings.size).roundToInt().coerceAtLeast(1)
        return Rect(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h)
    }

    // Where a window of [pad] comes from and goes to.
    private fun padHidden(pad: Scratchpad, rect: Rect): Rect = if (pad.isSpecial) desktop.specialWorkspaces.look.hidden(rect) else pad.hiddenRect(rect, padArea(pad))

    // How far a special workspace is shown now (0: gone, 1: there): for its windows' fading and its dim.
    internal fun padAmount(pad: Scratchpad, now: Long): Float {
        val start = pad.changedAt ?: return if (pad.leaving) 0f else 1f
        val picked = desktop.animator.pick(padAnimation(pad), desktop.animations.windowMove)
        val t = desktop.animator.animationProgress(start, picked.millis, now)
        if (t == null) {
            pad.changedAt = null
            return if (pad.leaving) 0f else 1f
        }
        val c = picked.curve.safeAt(t).toFloat().coerceIn(0f, 1f)
        return if (pad.leaving) pad.amountFrom * (1f - c) else pad.amountFrom + (1f - pad.amountFrom) * c
    }

    // It begins coming or going now, from how far it is shown.
    private fun padChanges(pad: Scratchpad, from: Float, now: Long) {
        pad.amountFrom = from
        pad.changedAt = if (desktop.animator.pick(padAnimation(pad), desktop.animations.windowMove).millis > 0) now else null
    }

    // The animation a scratchpad's windows come and go with.
    internal fun padAnimation(pad: Scratchpad?): String = if (pad?.isSpecial == true) desktop.specialWorkspaces.animation else "scratchpads"

    internal fun showScratchpad(pad: Scratchpad, appearing: Set<Window>, focusOn: Window? = null) {
        // (Coming back while going: it goes on from where it is, it does not start again.)
        val coming = pad.shown && pad.leaving
        if (!pad.shown || pad.leaving) {
            val now = System.nanoTime()
            padChanges(pad, if (coming) padAmount(pad, now) else 0f, now)
            pad.monitor = workMonitor
            if (pad.isSpecial) pad.focusBefore = desktop.focusedWindow?.takeIf { desktop.scratchpadOf(it) == null }?.let { java.lang.ref.WeakReference(it) }
        }
        pad.shown = true
        pad.leaving = false
        padTimers.remove(pad)?.cancel()
        shownPads -= pad
        shownPads += pad
        placeScratchpad(pad, appearing, fromShown = coming)
        (focusOn ?: pad.windows.lastOrNull())?.let(desktop::focus)
    }

    internal val padTimers = HashMap<Scratchpad, EventLoop.Timer>()

    internal fun hideScratchpad(pad: Scratchpad, focusBack: Boolean = true, atOnce: Boolean = false) {
        val began = System.nanoTime()
        padChanges(pad, padAmount(pad, began), began)
        pad.leaving = true
        pad.focusedAsItWent = desktop.focusedWindow?.takeIf { it in pad.windows }?.let { java.lang.ref.WeakReference(it) }
        if (pad.isSpecial) desktop.layoutLog.info { "Special workspace ${pad.name} hidden" }
        // A special workspace gives the focus back to the window that had it before (if it is still there).
        val back = pad.focusBefore?.get()?.takeIf { focusBack && it in desktop.windows }
        pad.focusBefore = null
        val now = System.nanoTime()
        for (window in pad.windows) {
            val from = desktop.animator.shownRects[window] ?: desktop.windowRoles.currentRect(window)
            desktop.animator.glides[window] = DesktopAnimations.Glide(from, now, to = padHidden(pad, from), name = padAnimation(pad))
        }
        // (A hide before this one, shown again in between, must not end this one early.)
        padTimers.remove(pad)?.cancel()
        fun gone() {
            padTimers -= pad
            if (!pad.leaving) return // shown again meanwhile
            pad.leaving = false
            pad.shown = false
            shownPads -= pad
            for (window in pad.windows) desktop.animator.glides -= window
            desktop.popupHost.dismissHiddenGrabs("their scratchpad is hidden")
            if (padFocus != null && padFocus in pad.windows) desktop.focus(back ?: desktop.windows.lastOrNull())
            desktop.changed()
        }
        // (Without the animation: gone at once, not drawn where it was meanwhile.)
        val millis = desktop.animator.pick(padAnimation(pad), desktop.animations.windowMove).millis
        if (millis <= 0 || atOnce) gone() else padTimers[pad] = desktop.compositor.display.loop.after(millis) { gone() }
        if (padFocus != null && padFocus in pad.windows) desktop.focus(back ?: desktop.windows.lastOrNull())
        desktop.changed()
    }

    // Where the scratchpad's windows go: its edge, the middle, or its own layout.
    internal fun padTargets(pad: Scratchpad, extra: Window? = null): Map<Window, Rect> {
        val windows = pad.windows + listOfNotNull(extra)
        if (windows.isEmpty()) return emptyMap()
        if (pad.isSpecial) {
            // The compositor's layout (or its own), inside its gap.
            val layout = specialLayout()
            val area = specialArea(pad)
            if (layout === Floating) {
                // No tiling: each window in the middle, two thirds of it as big.
                val w = area.width * 2 / 3
                val h = area.height * 2 / 3
                return windows.associateWith { desktop.arranger.fit(it, Rect(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h)) }
            }
            if (pad.workspace.layout != layout) pad.workspace.layout = layout
            val rects = desktop.arranger.arrangeSafely(pad.workspace, windows, area, windows.lastOrNull())
            return windows.associateWith { window -> desktop.arranger.fit(window, rects.getOrNull(windows.indexOf(window)) ?: area) }
        }
        if (pad.style is Scratchpad.Overlay) {
            val rects = desktop.arranger.arrangeSafely(pad.workspace, windows, padArea(pad), windows.lastOrNull())
            return windows.zip(rects).associate { (window, rect) -> window to desktop.arranger.fit(window, rect) }
        }
        val rect = pad.shownRect(padArea(pad))
        return windows.associateWith { desktop.arranger.fit(it, rect) }
    }

    // Sizes and places the scratchpad's windows; [appearing] ones grow or slide into place.
    internal fun placeScratchpad(pad: Scratchpad, appearing: Set<Window>, fromShown: Boolean = false) {
        val now = System.nanoTime()
        for ((window, rect) in padTargets(pad)) {
            window.tiled = pad.style is Scratchpad.Overlay && !(pad.isSpecial && specialLayout() === Floating)
            val visibleRect = window.visibleRect
            // (Asked for by the size it was asked last too: it may not have drawn that one yet.)
            if (visibleRect.width != rect.width || visibleRect.height != rect.height || window.configuredSize != rect.width to rect.height) window.configure(rect.width, rect.height)
            window.x = rect.x
            window.y = rect.y
            if (window in appearing && desktop.animator.pick(padAnimation(pad), desktop.animations.windowMove).millis > 0) {
                desktop.animator.glides[window] = DesktopAnimations.Glide(desktop.animator.shownRects[window].takeIf { fromShown } ?: padHidden(pad, rect), now, name = padAnimation(pad))
            }
        }
        desktop.changed()
    }
}
