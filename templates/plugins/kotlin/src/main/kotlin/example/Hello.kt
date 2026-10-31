package example

import dev.caulis.plugins.PluginContext
import dev.caulis.wm.plugins.CaulisPlugin
import dev.caulis.wm.plugins.PluginApi

/**
 * All this is taken away when the plugin is disabled or its jar replaced: nothing needs undoing by
 * hand. What it throws disables it with a notice; the compositor goes on.
 */
class Hello : CaulisPlugin {
    override fun enable(context: PluginContext<PluginApi>) {
        val api = context.api
        val greeting = context.settings.string("greeting", "Hello")

        api.bind("Super+F9") { api.notify("$greeting: ${api.desktop.allWindows.size} windows") }
        api.onWindowOpened { window -> context.log.info("opened ${window.appId}") }
        api.every(60_000) { context.log.trace("a minute passed") }
        api.command("hello-count") { api.desktop.allWindows.size.toString() }
    }
}
