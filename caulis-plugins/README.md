# caulis-plugins

A plugin system that knows nothing of any desktop: finds plugins in a folder, reads their
manifests, orders them by their dependencies, loads each jar in a class loader of its own, enables,
disables, unloads and hot-reloads them. A plugin that throws is disabled and reported; one that
hangs the compositor's thread is detected, reported and interrupted; a scope takes away everything
a plugin registered; a class loader that stays behind is found. It costs nothing while no plugin
is loaded: no thread but the folder's watch, nothing per frame.

`caulis-wm` brings it, with the API for Caulis compositors (`dev.caulis.wm.plugins.PluginApi`).
Another compositor implements `PluginHost<MyApi>` and creates a `PluginManager`.

Jar plugins run with the user's rights and are not sandboxed: installing one is running a program.
