package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * What the protocol objects ask of the desktop, through their own Listener interfaces
 * ([Activation.Listener], [ServerDecorations.Listener], [ForeignToplevels.Listener]) instead of
 * the whole [Desktop]: they are to move to a module of their own, below the desktop. Each
 * answer is the desktop's own; nothing is kept here.
 */
internal class DesktopProtocols(private val desktop: Desktop) :
    Activation.Listener, ServerDecorations.Listener, ForeignToplevels.Listener {

    override val focusedWindow: Window? get() = desktop.focusedWindow
    override val focusedAt: Long get() = desktop.focusedAt
    override val allWindows: List<Window> get() = desktop.allWindows
    override val locked: Boolean get() = desktop.locked
    override val titleBars: TitleBars get() = desktop.titleBars

    override fun ignoresActivation(window: Window): Boolean = WindowRequest.ACTIVATE in desktop.behaviorOf(window).ignored
    override fun focus(window: Window) = desktop.focus(window)
    override fun setUrgent(window: Window, urgent: Boolean) = desktop.setUrgent(window, urgent)

    override fun titleBarsOf(window: Window): TitleBars = desktop.titleBarsOf(window)
    override fun decorationOfChanged(window: Window) = desktop.decorationOfChanged(window)
    override fun onDecorationSettingsChanged(tellAll: () -> Unit) {
        desktop.decorationsChanged = tellAll
    }

    override fun isMinimized(window: Window): Boolean = desktop.isMinimized(window)
    override fun outputOf(window: Window): Output? = desktop.monitorOf(window).output
    override fun minimize(window: Window) = desktop.minimize(window)
    override fun setMaximized(window: Window, maximized: Boolean) = desktop.setMaximized(window, maximized)
    override fun setFullscreen(window: Window, fullscreen: Boolean) = desktop.setFullscreen(window, fullscreen)
    override fun setMinimizeTarget(window: Window, surface: Surface?, rect: Rect?) = desktop.setMinimizeTarget(window, surface, rect)
    override fun onWindowListChanged(listener: () -> Unit): AutoCloseable = desktop.onWindowListChanged(listener)
}
