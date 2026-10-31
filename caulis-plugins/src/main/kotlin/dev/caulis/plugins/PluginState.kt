package dev.caulis.plugins

/** Where a plugin is in its life. */
enum class PluginState {
    /** Found; it may start (or its code is loaded and it is not enabled). */
    LOADED,

    /** Running. */
    ENABLED,

    /** Off: by the user, or the configuration. */
    DISABLED,

    /** Off: it threw or hung; [PluginInfo.problem] says what. Again with `enable`, or a new file. */
    FAILED,

    /** Not started: it needs another API or another plugin; [PluginInfo.problem] says what. */
    REFUSED,
}

/** What can be said of a plugin at a moment (`plugin list`). */
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    /** "jar", "lua", ... */
    val format: String,
    val state: PluginState,
    /** Why it failed or was refused. */
    val problem: String?,
    val path: java.nio.file.Path,
    val description: String,
    /** Which time this file runs (a hot reload adds one). */
    val generation: Int,
    /** What it holds now, by kind. */
    val held: Map<String, Int>,
)
