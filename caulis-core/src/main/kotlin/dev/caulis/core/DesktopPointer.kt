package dev.caulis.core

import dev.caulis.core.input.Scroll
import dev.caulis.core.input.Tablet
import dev.caulis.geometry.Rect

/**
 * The pointer: moving over monitors, constraints of programs, hovering, buttons and the wheel.
 */
internal class DesktopPointer(private val desktop: Desktop) {

    // A click on a notice of ours closes it, or copies it on its copy button (nothing below sees
    // the press or the release). True if it was one.
    private fun noticeClick(button: Int, pressed: Boolean): Boolean {
        if (!pressed) {
            if (button != noticeButton) return false
            noticeButton = null
            return true
        }
        if (desktop.widgetHost.pressedWidget != null) return false // (a drag in a widget goes on)
        desktop.noticeBoard.actionAt(desktop.pointerX, desktop.pointerY)?.let { (notice, action) ->
            noticeButton = button
            when (action) {
                NoticeAction.COPY -> desktop.copyText?.let { copy ->
                    userCode("copyText", Unit) { copy(notice.fullText) }
                    desktop.noticeBoard.copied(notice)
                }
                NoticeAction.CLOSE -> desktop.dismiss(notice)
            }
            return true
        }
        // A warning or an error with buttons: a click elsewhere on it does nothing (it is not lost by chance).
        desktop.noticeBoard.at(desktop.pointerX, desktop.pointerY)?.let { notice ->
            noticeButton = button
            if (notice.level == NoticeLevel.INFO || desktop.noticeBoard.style.painter !is CardNoticePainter) desktop.dismiss(notice)
            return true
        }
        return false
    }

    fun pointerMovedBy(timeMillis: Int, dx: Double, dy: Double, rawDx: Double = dx, rawDy: Double = dy) {
        // Games and 3D programs hear the movement itself; a lock holds the pointer, a confinement keeps it in.
        desktop.pointerConstraints?.let { pc ->
            pc.relativeMotion(timeMillis, dx, dy, rawDx, rawDy)
            when (val c = constraintNow()) {
                null -> {}
                else -> if (c.locks) {
                    desktop.onUserActivity()
                    desktop.seat.pointerFrame()
                    return
                } else {
                    val (cx, cy) = confined(c, desktop.pointerX + dx, desktop.pointerY + dy)
                    return movePointer(timeMillis, cx, cy)
                }
            }
        }
        val from = desktop.monitorAt(desktop.pointerX, desktop.pointerY)
        val x = desktop.pointerX + dx
        val y = desktop.pointerY + dy
        if (from.contains(x, y)) return movePointer(timeMillis, x, y)
        when (desktop.monitorEdges) {
            MonitorEdges.TOUCHING -> {
                // Onto the monitor there, if there is one; else it stays at this one's edge.
                val onto = desktop.monitorList.firstOrNull { it.contains(x, y) }
                if (onto != null) {
                    movePointer(timeMillis, x, y)
                } else {
                    // Along the edge it can still go (sliding along it, not stuck).
                    val alongX = x.coerceIn(from.area.x.toDouble(), from.area.right - 1.0)
                    val alongY = y.coerceIn(from.area.y.toDouble(), from.area.bottom - 1.0)
                    val sideways = desktop.monitorList.firstOrNull { it !== from && it.contains(x, alongY) }?.let { x to alongY }
                        ?: desktop.monitorList.firstOrNull { it !== from && it.contains(alongX, y) }?.let { alongX to y }
                    val (nx, ny) = sideways ?: (alongX to alongY)
                    movePointer(timeMillis, nx, ny)
                }
            }
            MonitorEdges.PROPORTIONAL -> {
                val area = from.area
                // Which edge it went past (the one it went further past, at a corner).
                val overRight = x - (area.right - 1)
                val overLeft = area.x - x
                val overDown = y - (area.bottom - 1)
                val overUp = area.y - y
                val horizontal = maxOf(overRight, overLeft) >= maxOf(overDown, overUp)
                val next = if (horizontal) monitorPast(from, if (overRight > 0) 1 else -1, 0) else monitorPast(from, 0, if (overDown > 0) 1 else -1)
                if (next == null) {
                    movePointer(timeMillis, x.coerceIn(area.x.toDouble(), area.right - 1.0), y.coerceIn(area.y.toDouble(), area.bottom - 1.0))
                    return
                }
                val to = next.area
                if (horizontal) {
                    // The same share of the height; just inside the new monitor's near edge.
                    val share = ((y - area.y) / area.height).coerceIn(0.0, 1.0)
                    val nx = if (overRight > 0) to.x + overRight.coerceAtMost(to.width - 1.0) else to.right - 1 - overLeft.coerceAtMost(to.width - 1.0)
                    movePointer(timeMillis, nx, to.y + share * (to.height - 1))
                } else {
                    val share = ((x - area.x) / area.width).coerceIn(0.0, 1.0)
                    val ny = if (overDown > 0) to.y + overDown.coerceAtMost(to.height - 1.0) else to.bottom - 1 - overUp.coerceAtMost(to.height - 1.0)
                    movePointer(timeMillis, to.x + share * (to.width - 1), ny)
                }
            }
        }
    }

    // The monitor past [from]'s edge in a direction ([dx] or [dy] = ±1): wholly on that side,
    // the nearest (then the one most beside it).
    private fun monitorPast(from: Monitor, dx: Int, dy: Int): Monitor? {
        val a = from.area
        return desktop.monitorList.filter { it !== from }.filter { o ->
            val b = o.area
            (dx > 0 && b.x >= a.right - 1) || (dx < 0 && b.right <= a.x + 1) || (dy > 0 && b.y >= a.bottom - 1) || (dy < 0 && b.bottom <= a.y + 1)
        }.minWithOrNull(compareBy<Monitor>({ o ->
            val b = o.area
            when {
                dx > 0 -> b.x - a.right
                dx < 0 -> a.x - b.right
                dy > 0 -> b.y - a.bottom
                else -> a.y - b.bottom
            }
        }, { o ->
            val b = o.area
            // How far apart they are across: overlapping ones first.
            if (dx != 0) Math.abs((b.y + b.height / 2) - (a.y + a.height / 2)) else Math.abs((b.x + b.width / 2) - (a.x + a.width / 2))
        }))
    }

    // The lock in force at the last motion (to put the pointer at its hint when it ends).
    private var lastLock: dev.caulis.core.input.PointerConstraints.Constraint? = null

    // The constraint working now (none during a grab, the overview or a lock screen).
    internal fun constraintNow(): dev.caulis.core.input.PointerConstraints.Constraint? {
        val now = if (desktop.grab == null && !desktop.inOverview && !desktop.locked) desktop.pointerConstraints?.current() else null
        val ended = lastLock
        if (ended != null && ended !== now) {
            // A lock let go: the pointer goes where the program said it should be.
            ended.hint?.let { (hx, hy) -> desktop.events.surfaceOrigin(ended.surface)?.let { (ox, oy) -> desktop.pointerX = ox + hx; desktop.pointerY = oy + hy } }
        }
        lastLock = now?.takeIf { it.locks }
        return now
    }

    // [x], [y] kept inside the confinement (the part of the region the pointer is in).
    private fun confined(c: dev.caulis.core.input.PointerConstraints.Constraint, x: Double, y: Double): Pair<Double, Double> {
        val (ox, oy) = desktop.events.surfaceOrigin(c.surface) ?: return x to y
        val rects = c.region?.solidRects()?.takeIf { it.isNotEmpty() } ?: listOf(Rect(0, 0, c.surface.width, c.surface.height))
        val px = desktop.pointerX - ox
        val py = desktop.pointerY - oy
        val rect = rects.firstOrNull { px >= it.x && py >= it.y && px < it.right && py < it.bottom } ?: return x to y
        return (x - ox).coerceIn(rect.x.toDouble(), rect.right - 1.0) + ox to (y - oy).coerceIn(rect.y.toDouble(), rect.bottom - 1.0) + oy
    }

    // The program's surface at ([x], [y]) that gets the pen as a pen, or null: there the pen is
    // the pointer (our own things, programs without tablet events, and while the pointer is busy).
    internal fun tabletTarget(tablet: Tablet, x: Double, y: Double): Desktop.Hit? {
        val tablets = desktop.tablets ?: return null
        if (tablet.settings.pointerOnly == true) return null
        if ((desktop.overlay != null && !desktop.locked) || desktop.inOverview || desktop.grab != null || desktop.popupHost.grabs.isNotEmpty() || desktop.buttonsHeld > 0) return null
        if (!desktop.locked && desktop.noticeBoard.at(x, y) != null) return null
        val hit = (desktop.hits.pointedAt(x, y, widgetsToo = true) as? Desktop.Pointed.AtSurface)?.hit ?: return null
        // (Our title bars and window edges are above what is under them.)
        if (!desktop.locked && desktop.ownTitleBars.at(x, y) != null) return null
        if (!hit.surface.resource.isAlive || !desktop.seat.allowed(hit.surface) || !tablets.takes(hit.surface)) return null
        return hit
    }

    // The pen took over from the pointer: what the pointer was over hears that it left.
    internal fun pointerAway(timeMillis: Int) {
        desktop.widgetHost.hoverWidget(timeMillis, null, moved = false)
        if (desktop.ownTitleBars.hover != null) {
            desktop.ownTitleBars.hover = null
            desktop.changed()
        }
        desktop.cursors.decorationCursor = null
        if (desktop.seat.pointerFocus != null) desktop.seat.pointerMotion(timeMillis, null, 0.0, 0.0)
    }

    // The cursor is where the pen is (over a program that takes the pen as a pen).
    internal fun showPenAt(x: Double, y: Double) {
        desktop.cursors.cursorActive()
        val on = desktop.monitorAt(x, y)
        desktop.pointerX = x.coerceIn(on.area.x.toDouble(), on.area.right - 1.0)
        desktop.pointerY = y.coerceIn(on.area.y.toDouble(), on.area.bottom - 1.0)
        desktop.onChange()
    }

    // The pen's tip touched a program: our widgets it is not on hear of it (an open menu
    // closes, a text field gives the keyboard back). True if one took it (the program does not get it).
    internal fun penPressedOutside(x: Double, y: Double): Boolean {
        if (desktop.keyboardWidget != null) desktop.focusWidget(null)
        var taken = false
        for (other in desktop.widgetHost.widgets.toList().asReversed()) {
            if (userCode("Your widget (pressedOutside)", false) { other.pressedOutside(BTN_LEFT, x, y, false) }) taken = true
        }
        return taken
    }

    fun pointerMoved(timeMillis: Int, x: Double, y: Double) {
        // From an absolute position (nested, tablets): the movement counts for constraints too.
        desktop.pointerConstraints?.let { pc ->
            if (x != desktop.pointerX || y != desktop.pointerY) pc.relativeMotion(timeMillis, x - desktop.pointerX, y - desktop.pointerY, x - desktop.pointerX, y - desktop.pointerY)
            constraintNow()?.let { c ->
                if (c.locks) {
                    desktop.onUserActivity()
                    desktop.seat.pointerFrame()
                    return
                }
                val (cx, cy) = confined(c, x, y)
                return movePointer(timeMillis, cx, cy)
            }
        }
        movePointer(timeMillis, x, y)
    }

    internal fun movePointer(timeMillis: Int, x: Double, y: Double) {
        desktop.onUserActivity()
        desktop.inputLog.trace { "Pointer at ${String.format("%.1f, %.1f", x, y)}" }
        desktop.cursors.cursorActive()
        // On a monitor: past an edge with no monitor beyond, it stops there.
        val on = desktop.monitorAt(x, y)
        desktop.pointerX = x.coerceIn(on.area.x.toDouble(), on.area.right - 1.0)
        desktop.pointerY = y.coerceIn(on.area.y.toDouble(), on.area.bottom - 1.0)
        desktop.lastPointerMoveNanos = System.nanoTime()
        desktop.wallpaperShaders.pointerMoved()
        // (Not while the input thread already showed movements after this one: it would go back.)
        if (desktop.quickCursorMoves && desktop.planes.earlyInFlight.get() <= 1) desktop.planes.quickCursor()
        desktop.planes.publishEarly()
        if (!desktop.locked) desktop.noticeBoard.pointerAt(desktop.pointerX, desktop.pointerY)
        if (!desktop.locked && desktop.overlayHost.withOverlay { it.pointerMoved(desktop.pointerX, desktop.pointerY) }) {
            desktop.onChange()
            return
        }
        desktop.grab?.let { current ->
            desktop.pointerGrab.pointerMoved(current)
            desktop.onChange()
            return
        }
        if (desktop.inOverview) {
            desktop.onChange() // only our cursor moves; programs do not see it
            return
        }
        // Buttons held since a press in a program: it keeps the pointer, wherever it goes.
        pressedIn?.let { pressed ->
            if (desktop.buttonsHeld > 0 && !desktop.locked && pressed.surface.resource.isAlive && desktop.seat.drag == null) {
                desktop.seat.pointerMotion(timeMillis, pressed.surface, (desktop.pointerX - pressed.x) / pressed.scaleX, (desktop.pointerY - pressed.y) / pressed.scaleY)
                desktop.onChange()
                return
            }
            pressedIn = null
        }
        // A button pressed in a widget: it keeps the pointer until let go.
        desktop.widgetHost.pressedWidget?.let { pressed ->
            if (desktop.buttonsHeld > 0) {
                userCode("Your widget (pointerMoved)", Unit) { pressed.widget.pointerMoved(pressed.monitor, desktop.pointerX - pressed.area.x, desktop.pointerY - pressed.area.y) }
                desktop.onChange()
                return
            }
            desktop.widgetHost.pressedWidget = null
        }
        // Over one of our widgets: it has the pointer (the program below loses it). Title bars
        // are above the widgets below the windows.
        val under = desktop.hits.pointedAt(desktop.pointerX, desktop.pointerY, widgetsToo = true)
        val overWidget = under as? Desktop.Pointed.AtWidget
        // (Behind a lock no title bar lights up and no edge shows a resize cursor.)
        val decoration = if (desktop.locked || (overWidget != null && overWidget.level != DrawingLevel.BELOW_WINDOWS)) null else desktop.ownTitleBars.at(desktop.pointerX, desktop.pointerY)
        if (desktop.widgetHost.hoverWidget(timeMillis, overWidget.takeIf { decoration == null }, moved = true)) {
            desktop.onChange()
            return
        }
        // Over one of our title bars: the program does not see the pointer, the bar may light up.
        val hover = (decoration as? DesktopTitleBars.Decoration.Bar)?.let { it.window to it.button }
        desktop.cursors.decorationCursor = (decoration as? DesktopTitleBars.Decoration.Edge)?.let { desktop.cursors.resizeCursor(it.edges) }
        if (hover != desktop.ownTitleBars.hover) {
            desktop.ownTitleBars.hover = hover
            desktop.changed()
        }
        // The keyboard follows the pointer: onto another window, it gets the focus (once, so
        // keys can still move the focus while the pointer rests).
        // (Not while one of our widgets has the keyboard — a launcher, a menu: the pointer
        // passing over a window took the keys from it.)
        if (desktop.focusFollowsMouse && !desktop.locked && desktop.keyboardWidget == null) {
            val under = desktop.windowAt(desktop.pointerX, desktop.pointerY) ?: (decoration as? DesktopTitleBars.Decoration.Bar)?.window ?: (decoration as? DesktopTitleBars.Decoration.Edge)?.window
            if (under !== hovered) {
                hovered = under
                if (under != null && under !== desktop.focusedWindow && desktop.layerHost.exclusiveLayer() == null && !behindSpecial(under)) desktop.focus(under)
            }
        }
        if (decoration != null) {
            desktop.seat.pointerMotion(timeMillis, null, 0.0, 0.0)
            desktop.onChange()
            return
        }
        val hit = (under as? Desktop.Pointed.AtSurface)?.hit
        desktop.seat.pointerMotion(timeMillis, hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
        desktop.onChange() // only the cursor moved: the rest of the scene stays as it is
    }

    // [window] is behind a special workspace shown on the pointer's monitor that a click
    // outside hides (SpecialWorkspaces.clickOutsideHides): the pointer passing over it there
    // does not take the focus away from the special workspace (a key meant for its window —
    // moving it out — went to the window behind). Clicking there hides it, as before.
    private fun behindSpecial(window: Window): Boolean {
        if (desktop.scratchpadOf(window) != null || !desktop.specialWorkspaces.clickOutsideHides) return false
        return desktop.shownSpecialOn(desktop.monitorAt(desktop.pointerX, desktop.pointerY)) != null
    }

    // The version of the scene the pointer's program was last checked against.
    private var pointerCheckedVersion = -1L

    // Windows moved under a pointer standing still (another workspace shown, a window
    // opened, closed or moved): the program now under it gets it — with its cursor — and the
    // one that went away stops getting it (a game on a hidden workspace saw every move).
    internal fun pointerOntoWhatIsThere() {
        if (pointerCheckedVersion == desktop.version) return
        pointerCheckedVersion = desktop.version
        if (desktop.overlay != null || desktop.grab != null || desktop.inOverview || desktop.buttonsHeld > 0) return
        if (desktop.pens.onPrograms) return // (the pen has it: the program under it gets the pen)
        val time = (System.nanoTime() / 1_000_000).toInt()
        val under = desktop.hits.pointedAt(desktop.pointerX, desktop.pointerY, widgetsToo = true)
        val overWidget = under as? Desktop.Pointed.AtWidget
        val onBar = (overWidget == null || overWidget.level == DrawingLevel.BELOW_WINDOWS) && desktop.ownTitleBars.at(desktop.pointerX, desktop.pointerY) != null
        if (desktop.widgetHost.hoverWidget(time, overWidget.takeIf { !onBar }, moved = false) || onBar) return
        val hit = (under as? Desktop.Pointed.AtSurface)?.hit
        if (hit?.surface === desktop.seat.pointerFocus) return
        desktop.inputLog.trace { "The pointer is over ${hit?.root?.roleObject ?: "nothing"} now (the windows moved)" }
        desktop.seat.pointerMotion(time, hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
    }

    // The window the pointer was over last (for focusFollowsMouse).
    internal var hovered: Window? = null

    // The button that closed a notice: its release is ours too.
    private var noticeButton: Int? = null

    // The button whose press only closed a menu (pressedOutside): its release is ours too.
    private var outsideButton: Int? = null

    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean, passToApps: Boolean = true) {
        desktop.inputLog.trace { "Button $button ${if (pressed) "pressed" else "released"} at ${desktop.pointerX.toInt()},${desktop.pointerY.toInt()}${if (passToApps) "" else " (for the window manager)"}" }
        desktop.buttonsHeld = (desktop.buttonsHeld + if (pressed) 1 else -1).coerceAtLeast(0)
        // (Wallpaper shaders may ripple from it: not on a lock screen.)
        if (pressed && !desktop.locked) desktop.wallpaperShaders.pressed(desktop.pointerX, desktop.pointerY, button)
        // A first button pressed: no release is owed to a notice or a menu any more (one taken
        // elsewhere — by the lock, by an overlay — left these set).
        if (pressed && desktop.buttonsHeld == 1) {
            noticeButton = null
            outsideButton = null
        }
        // The last button let go: the program the press was in lets go of the pointer.
        val releasedFrom = if (!pressed && desktop.buttonsHeld == 0) pressedIn.also { pressedIn = null } else null
        desktop.cursors.cursorActive()
        if (!passToApps) return
        // A drag and drop going on: letting go drops it (wherever the pointer is).
        if (desktop.seat.drag != null) {
            desktop.seat.pointerButton(timeMillis, button, pressed)
            if (desktop.seat.drag == null) movePointer(timeMillis, desktop.pointerX, desktop.pointerY) // the one under the pointer gets it now
            return
        }
        if (desktop.overlayHost.withOverlay { it.pointerButton(button, pressed) }) return
        // Notices are above everything (a bar too): a click on one is theirs (none on a lock screen).
        if (!desktop.locked && noticeClick(button, pressed)) return
        // A release of a press that only closed a menu: nobody's.
        if (!pressed && button == outsideButton) {
            outsideButton = null
            return
        }
        // A widget's (the one pressed in keeps it until the last button is let go).
        // (While locked only the lock screen's widgets can be hovered or pressed.)
        val widgetTarget = if (desktop.inOverview) null else desktop.widgetHost.pressedWidget?.widget ?: desktop.widgetHost.hoveredWidget
        // A press anywhere else: the widgets it is not on hear of it (an open menu closes).
        if (pressed && desktop.buttonsHeld == 1 && !desktop.inOverview && desktop.widgetHost.widgets.isNotEmpty()) {
            var taken = false
            for (other in desktop.widgetHost.widgets.toList().asReversed()) {
                if (other === widgetTarget || !desktop.lockHost.widgetUsable(other)) continue
                if (userCode("Your widget (pressedOutside)", false) { other.pressedOutside(button, desktop.pointerX, desktop.pointerY, widgetTarget != null) }) taken = true
            }
            if (taken && widgetTarget == null) {
                desktop.inputLog.trace { "Button $button: a press outside a menu, which closed (not passed on)" }
                outsideButton = button
                return
            }
        }
        if (widgetTarget != null) {
            if (pressed && desktop.buttonsHeld == 1) {
                desktop.widgetHost.hoveredArea?.takeIf { widgetTarget === desktop.widgetHost.hoveredWidget }?.let { area -> desktop.widgetHost.pressedWidget = DesktopWidgets.PressedWidget(widgetTarget, area, desktop.monitorAt(desktop.pointerX, desktop.pointerY)) }
            }
            if (pressed && userCode("Your widget (takesKeyboard)", false) { widgetTarget.takesKeyboard }) desktop.focusWidget(widgetTarget)
            userCode("Your widget (pointerButton)", Unit) { widgetTarget.pointerButton(button, pressed) }
            if (!pressed && desktop.buttonsHeld == 0 && desktop.widgetHost.pressedWidget != null) {
                desktop.widgetHost.pressedWidget = null
                movePointer(timeMillis, desktop.pointerX, desktop.pointerY)
            }
            return
        }
        // A click anywhere else takes the keyboard back from a widget.
        if (pressed && desktop.keyboardWidget != null) desktop.focusWidget(null)
        if (desktop.locked) {
            // Only the lock screen can be under the pointer: a click on one of its surfaces
            // (the locker's on another monitor, or one our own lock screen allows) gives that
            // one the keyboard — taking it from our own lock screen's widget too.
            if (pressed) desktop.hits.surfaceAt(desktop.pointerX, desktop.pointerY)?.takeIf { desktop.lockHost.lockAllows(it.root) }?.let { hit ->
                val window = hit.root.roleObject as? Window
                if (window != null) desktop.focus(window) else if (desktop.seat.keyboardFocus !== hit.root) desktop.widgetHost.keyboardTo(hit.root)
            }
            desktop.seat.pointerButton(timeMillis, button, pressed)
            return
        }
        if (desktop.inOverview) {
            if (pressed) desktop.overview.overviewClick()
            return
        }
        if (desktop.grab != null) {
            // The button that started a move or resize ends it; the app does not see it.
            if (!pressed && desktop.buttonsHeld == 0) {
                desktop.pointerGrab.end()
                movePointer(timeMillis, desktop.pointerX, desktop.pointerY) // the app under the pointer gets it back
            }
            return
        }
        // Title bars and edges of our own (no program sees these clicks).
        if (desktop.popupHost.grabs.isEmpty() && desktop.ownTitleBars.button(button, pressed)) return
        if (pressed) {
            val hit = desktop.hits.surfaceAt(desktop.pointerX, desktop.pointerY)
            // A click outside the open menus closes them (and is not passed to the window below).
            if (desktop.popupHost.grabs.isNotEmpty() && (hit == null || desktop.popupHost.popups.none { it.surface === hit.root })) {
                desktop.popupHost.grabs.toList().asReversed().forEach { it.dismiss() }
                desktop.popupHost.grabs.clear()
                return
            }
            // A click on the dimmed area around an overlay scratchpad (or a special workspace) hides it.
            val overlay = desktop.pads.shownPads.lastOrNull { it.style is Scratchpad.Overlay && !it.leaving && (!it.isSpecial || desktop.specialWorkspaces.clickOutsideHides) }
            if (overlay != null && (hit == null || (hit.root.roleObject as? Window)?.let(desktop::scratchpadOf) !== overlay)) {
                desktop.pads.hideScratchpad(overlay)
                return
            }
            // A click on a panel that wants the keyboard on demand gives it the keyboard.
            (hit?.root?.roleObject as? LayerSurface)?.let { layer ->
                if (layer.current.keyboard != KEYBOARD_NONE && desktop.layerHost.exclusiveLayer() == null) {
                    desktop.layerHost.layerFocus = layer
                    desktop.widgetHost.keyboardTo(layer.surface)
                }
            }
            val window = hit?.root?.roleObject as? Window
            if (window != null && (window !== desktop.focusedWindow || desktop.layerHost.layerFocus != null)) {
                desktop.focus(window)
            } else if (window == null) {
                // A click on another monitor's free space: new windows open there now.
                val clicked = desktop.monitorAt(desktop.pointerX, desktop.pointerY)
                if (clicked !== desktop.focusedMonitor && hit?.root?.roleObject !is LayerSurface) desktop.focusMonitor(clicked)
            }
            // The first button: the pointer stays with this program until the last one is let
            // go, outside its window too (selecting text past the edge, dragging a slider).
            if (desktop.buttonsHeld == 1 && hit != null && hit.surface === desktop.seat.pointerFocus) {
                pressedIn = PressedIn(hit.surface, desktop.pointerX - hit.localX * hit.scaleX, desktop.pointerY - hit.localY * hit.scaleY, hit.scaleX, hit.scaleY)
            }
        }
        desktop.seat.pointerButton(timeMillis, button, pressed)
        if (releasedFrom != null) movePointer(timeMillis, desktop.pointerX, desktop.pointerY) // the one under the pointer gets it now
    }

    // The surface the held buttons were pressed in, and where its top left corner was (desktop
    // units): pointer moves go to it until they are let go (Wayland's implicit grab).
    internal var pressedIn: PressedIn? = null

    internal class PressedIn(val surface: Surface, val x: Double, val y: Double, val scaleX: Double, val scaleY: Double)

    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) {
        val source = if (value120 != 0) dev.caulis.core.input.Scroll.Source.WHEEL else dev.caulis.core.input.Scroll.Source.FINGER
        pointerAxis(
            timeMillis,
            if (vertical) dev.caulis.core.input.Scroll(source, vertical = value, vertical120 = value120)
            else dev.caulis.core.input.Scroll(source, horizontal = value, horizontal120 = value120),
        )
    }

    fun pointerAxis(timeMillis: Int, scroll: Scroll) {
        desktop.inputLog.trace { "$scroll" }
        desktop.cursors.cursorActive()
        if (!desktop.locked && desktop.overlay != null) {
            // An overlay hears each direction in steps (a notch is 1).
            for (vertical in listOf(true, false)) {
                val value = if (vertical) scroll.vertical else scroll.horizontal
                val value120 = if (vertical) scroll.vertical120 else scroll.horizontal120
                if (value != 0.0 || value120 != 0) desktop.overlayHost.withOverlay { it.scroll(vertical, if (value120 != 0) value120 / 120.0 else value / 15.0) }
            }
            return
        }
        desktop.widgetHost.hoveredWidget?.takeIf { !desktop.inOverview }?.let { widget ->
            if (scroll.horizontal != 0.0 || scroll.vertical != 0.0) userCode("Your widget (scroll)", Unit) { widget.scroll(scroll.horizontal, scroll.vertical) }
            return
        }
        desktop.seat.pointerAxis(timeMillis, scroll)
    }
}
