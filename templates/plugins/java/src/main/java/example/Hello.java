package example;

import dev.caulis.core.input.KeyBindings;
import dev.caulis.plugins.PluginContext;
import dev.caulis.wm.plugins.CaulisPlugin;
import dev.caulis.wm.plugins.PluginApi;

/**
 * All this is taken away when the plugin is disabled or its jar replaced: nothing needs undoing by
 * hand. What it throws disables it with a notice; the compositor goes on.
 */
public class Hello implements CaulisPlugin {
    @Override
    public void enable(PluginContext<PluginApi> context) {
        PluginApi api = context.getApi();
        String greeting = context.getSettings().string("greeting", "Hello");

        api.bind("Super+F9", new KeyBindings.Options(), () ->
            api.notify(greeting + ": " + api.getDesktop().getAllWindows().size() + " windows"));
        api.onWindowOpened(window -> context.getLog().info("opened " + window.getAppId()));
        api.every(60_000, () -> context.getLog().trace("a minute passed"));
    }
}
