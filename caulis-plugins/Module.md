# Module caulis-plugins

Plugins for any compositor: [dev.caulis.plugins.PluginManager] finds them in a folder, and loads,
enables, disables, unloads and hot-reloads them, for a [dev.caulis.plugins.PluginHost] saying what
they may call. Jar plugins run with the user's rights and are not sandboxed.

# Package dev.caulis.plugins

[dev.caulis.plugins.Plugin] (what a plugin implements), [dev.caulis.plugins.PluginManifest] (what it
says of itself), [dev.caulis.plugins.PluginManager] (the running of them).
