package dev.caulis.core

/**
 * A picture of the whole state as text, for finding problems.
 */
internal class DesktopReport(private val desktop: Desktop) {

    fun describe(): String = buildString {
        appendLine("Desktop ${desktop.width}x$desktop.height, room for windows $desktop.usableArea${if (desktop.locked) ", LOCKED" else ""}${if (desktop.inOverview) ", overview open" else ""}")
        appendLine("  focus: ${desktop.focusedWindow ?: "nothing"}; keyboard: ${desktop.seat.keyboardFocus?.roleObject ?: desktop.seat.keyboardFocus ?: "nobody"}; pointer at ${desktop.pointerX.toInt()},${desktop.pointerY.toInt()} over ${desktop.seat.pointerFocus?.roleObject ?: "nothing"}")
        appendLine("  cursor: ${desktop.cursorTheme.name} ${desktop.cursorTheme.size} px, showing ${desktop.cursorShape ?: "the program's own"}${if (desktop.cursors.cursorHidden) " (hidden)" else ""}")
        for (workspace in desktop.workspaces.values.sortedBy { it.number }) {
            appendLine("  workspace ${workspace.number}${if (workspace === desktop.activeWorkspace) " (on screen)" else ""}, layout ${workspace.layout.name}:")
            for (window in workspace.windows) appendLine("    ${describe(window, workspace)}")
            for (window in workspace.minimized) appendLine("    ${describe(window, workspace)} [minimized]")
        }
        for (pad in desktop.pads.allPads) {
            appendLine("  ${if (pad.isSpecial) "special workspace" else "scratchpad"} ${pad.name} (${if (pad.shown) "shown" else "hidden"}): ${pad.windows.joinToString { it.toString() }.ifEmpty { "no window" }}")
        }
        for (layer in desktop.layerHost.layerSurfaces) {
            appendLine("  panel \"${layer.namespace}\" (${layer.surface.resource.client.name}) on ${layer.current.layer} at ${layer.x},${layer.y} ${layer.surface.width}x${layer.surface.height}${if (layer === desktop.layerHost.layerFocus) ", has the keyboard" else ""}")
        }
        if (desktop.animator.glides.isNotEmpty()) appendLine("  moving now: ${desktop.animator.glides.keys.joinToString { it.title.take(24) }}")
        for (notice in desktop.notices) appendLine("  on screen: $notice")
    }

    private fun describe(window: Window, workspace: Workspace): String = buildString {
        append(window).append(" from ").append(window.surface.resource.client.name)
        append(" at ").append(desktop.animator.screenRects[window] ?: desktop.currentRect(window))
        append(if (window in workspace.floating || !window.tiled) ", floating" else ", tiled")
        if (window.maximized) append(", maximized")
        if (window.fullscreen) append(", fullscreen")
        if (window.parent != null) append(", dialog of ").append(window.parent)
        if (window === desktop.focusedWindow) append(", focused")
    }
}
