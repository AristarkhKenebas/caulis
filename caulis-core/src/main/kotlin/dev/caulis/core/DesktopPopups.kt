package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * Menus and tooltips of programs: which are open, which took the pointer and keyboard (grabs),
 * and which go away when what they belong to does.
 */
internal class DesktopPopups(private val desktop: Desktop) {

    /** Mapped popups, in the order they opened (children after parents). */
    internal val popups = ArrayList<Popup>()

    /** Popups that asked for a grab: a click outside them closes them. */
    internal val grabs = ArrayList<Popup>()

    // A menu stays on the monitor its window (or panel) is on.
    fun popupBounds(popup: Popup): Rect? {
        val (ox, oy) = popup.parent?.let(desktop.hits::visibleOrigin) ?: popup.layerParent?.let { it.x to it.y } ?: return null
        val window = generateSequence(popup.parent) { (it.role as? Popup)?.parent }.mapNotNull { it.role as? Window }.firstOrNull()
        val monitor = window?.let { if (desktop.workspaceOf(it) != null || desktop.directed(it)) desktop.monitorOf(it) else null } ?: desktop.monitorAt(ox.toDouble(), oy.toDouble())
        val area = monitor.area
        return Rect(area.x - ox, area.y - oy, area.width, area.height)
    }

    fun popupMapped(popup: Popup) {
        popups += popup
        desktop.changed()
    }

    fun popupUnmapped(popup: Popup) {
        popups -= popup
        grabs -= popup
        // Its own menus go with it (a submenu without its menu would float there forever).
        popups.filter { it.parent === popup.shell }.asReversed().forEach { it.dismiss() }
        desktop.changed()
        if (desktop.seat.keyboardFocus === popup.surface) desktop.restoreKeyboard()
    }

    // Closes the menus of [owner] (a window), as when it goes, hides or moves away: menus of
    // a window that is not there must not stay on screen.
    internal fun dismissPopupsOf(owner: Window, why: String) {
        val gone = popups.filter { desktop.capturing.ownerOf(it.surface) === owner }
        if (gone.isEmpty()) return
        desktop.windowsLog.info { "The menus of $owner close: $why" }
        gone.asReversed().forEach { it.dismiss() }
    }

    // Menus holding the keyboard (grabs) that are not shown any more close: an invisible one
    // kept the title bars from being clicked, and might get the keyboard back.
    internal fun dismissHiddenGrabs(why: String) {
        val hidden = grabs.filter { !popupShown(it) }
        if (hidden.isEmpty()) return
        desktop.windowsLog.info { "${hidden.size} menu(s) holding the keyboard close: $why" }
        // (Not holding it any more even if the program never closes them.)
        grabs -= hidden.toSet()
        hidden.asReversed().forEach { it.dismiss() }
        if (hidden.any { desktop.seat.keyboardFocus === it.surface }) desktop.restoreKeyboard()
    }

    // A menu is shown only while what it belongs to is: its window on a shown workspace (not
    // minimized) or in a shown scratchpad, or its panel. (Until its program closes it.)
    internal fun popupShown(popup: Popup): Boolean = when (val owner = desktop.capturing.ownerOf(popup.surface)) {
        is Window -> owner.mapped && (
            desktop.scratchpadOf(owner)?.let { it.shown && !it.leaving }
                ?: desktop.workspaceOf(owner)?.let { it.windows.contains(owner) && desktop.monitorList.any { m -> m.workspace === it } }
                ?: false
            )
        is LayerSurface -> owner in desktop.layerSurfaces
        else -> false
    }

    fun popupGrab(popup: Popup, serial: Int) {
        // Only a program you are using now may take the keyboard with a menu — answering a
        // click or a key you gave it (one in the background, even under the pointer, would
        // get your keys): else the menu is closed at once (an invalid grab).
        val client = popup.resource.client
        if (!desktop.seat.isInputSerial(client, serial) || desktop.locked || (popup.parent == null && popup.layerParent == null)) {
            desktop.windowsLog.info { "${client.name} asked for a menu grab not answering your input: the menu is closed" }
            popup.dismiss()
            return
        }
        grabs += popup
        desktop.widgetHost.keyboardTo(popup.surface)
    }
}
