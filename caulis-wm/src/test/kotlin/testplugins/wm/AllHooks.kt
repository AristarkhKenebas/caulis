package testplugins.wm

import dev.caulis.core.Drawing
import dev.caulis.core.DrawingLevel
import dev.caulis.core.Monitor
import dev.caulis.core.OverlayCanvas
import dev.caulis.core.Widget
import dev.caulis.geometry.Rect
import dev.caulis.plugins.PluginContext
import dev.caulis.tiling.Layout
import dev.caulis.tiling.LayoutWindow
import dev.caulis.wm.plugins.CaulisPlugin
import dev.caulis.wm.plugins.PluginApi

/** What the plugins of the tests tell the tests. (From the compositor's class loader: both see one.) */
object Shared {
    @JvmField val events = java.util.concurrent.CopyOnWriteArrayList<String>()
}

/** Uses every kind of hook there is. */
class AllHooks : CaulisPlugin {
    override fun enable(context: PluginContext<PluginApi>) {
        val api = context.api
        api.onWindowOpened { w -> Shared.events += "opened ${w.appId}" }
        api.onWindowClosed { Shared.events += "closed" }
        api.onWindowChanged { }
        api.onFocusChanged { Shared.events += "focus" }
        api.onWorkspaceChanged { n -> Shared.events += "workspace $n" }
        api.onReload { Shared.events += "reload" }
        api.onNotice { }
        api.onLockChanged { }
        api.onKey { key -> if (key.evdevCode == 88) { Shared.events += "F12"; true } else false }
        api.onPointerMotion { _, _ -> false }
        api.onPointerButton { _, _ -> false }
        api.onScroll { _, _, _ -> false }
        api.onGesture { false }
        api.bind("Super+F10") { Shared.events += "key" }
        api.action("all-hooks-action", "text", "a test") { text, _ -> Shared.events += "action $text" }
        api.command("all-hooks") { text -> "said $text" }
        api.every(50) { Shared.events += "tick" }
        api.after(10_000) { Shared.events += "late" }
        api.addDrawing(DrawingLevel.ABOVE_WINDOWS, object : Drawing {
            override fun draw(monitor: Monitor, canvas: OverlayCanvas) {
                canvas.fill(Rect(0, 0, 4, 4), 1f, 1f, 1f)
                Shared.events += "drawn"
            }
        })
        api.addWidget(object : Widget {
            override fun area(monitor: Monitor) = Rect(10, 10, 4, 4)
            override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: Rect) = canvas.fill(area, 1f, 1f, 1f)
        })
        api.effectsFor { _, fx -> fx }
        api.panelEffects { null }
        api.layout("all-hooks") {
            object : Layout {
                override val name = "all-hooks"
                override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int) = windows.map { area }
            }
        }
        api.rule { appId("plugin-rule") }
        api.screenShader(dev.caulis.core.ScreenShader.INVERT)
        context.scope.onClose { Shared.events += "scope closed" }
    }

    override fun disable() {
        Shared.events += "disable"
    }
}

/** Throws in a window event. */
class ThrowsOnWindow : CaulisPlugin {
    override fun enable(context: PluginContext<PluginApi>) {
        context.api.onWindowOpened { error("broken plugin") }
    }
}

/** Sleeps in a key, a hang the watchdog can end. */
class HangsOnKey : CaulisPlugin {
    override fun enable(context: PluginContext<PluginApi>) {
        context.api.action("hang-now") { _, _ ->
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
                Shared.events += "interrupted"
            }
        }
    }
}
