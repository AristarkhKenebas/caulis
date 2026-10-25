package dev.caulis.screenshot

import dev.caulis.core.Window
import dev.caulis.wm.Caulis
import dev.caulis.wm.config.ActionProvider

/** The "screenshot" action for config.json, Lua, keys and caulisctl (found on the classpath). */
class ScreenshotActions : ActionProvider {

    override val actions = listOf(
        Triple("screenshot", "[region|window|screen|focused|monitor]", "a screenshot: the chooser, or the focused window / the monitor with the pointer at once"),
    )

    override fun parse(name: String, argument: String): (Caulis.(Window?) -> Unit)? {
        if (name != "screenshot") return null
        return when (argument.lowercase()) {
            "" -> { _ -> Screenshots.of(this).choose() }
            "region", "area" -> { _ -> Screenshots.of(this).choose(ShotKind.REGION) }
            "window" -> { _ -> Screenshots.of(this).choose(ShotKind.WINDOW) }
            "screen" -> { _ -> Screenshots.of(this).choose(ShotKind.SCREEN) }
            "focused" -> { window -> Screenshots.of(this).let { shots -> if (window != null) shots.later { shots.takeWindow(window) } else shots.takeFocused() } }
            "monitor" -> { _ -> Screenshots.of(this).takeMonitor() }
            else -> throw IllegalArgumentException("\"$argument\" is not region, window, screen, focused or monitor")
        }
    }
}
