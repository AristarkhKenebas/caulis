package dev.caulis.plugins

/** The manager's own text answer to `plugin list|enable|disable|reload [id]`. */
fun PluginManager<*>.command(words: List<String>): String {
    val what = words.firstOrNull() ?: "list"
    val id = words.getOrNull(1)
    return when (what) {
        "list" -> list().joinToString("\n") { info ->
            val held = if (info.held.isEmpty()) "" else "  (" + info.held.entries.joinToString { "${it.value} ${it.key}" } + ")"
            "%-24s %-10s %-4s %-9s%s%s".format(info.id, info.version, info.format, info.state.name.lowercase(), held, info.problem?.let { "  $it" }.orEmpty())
        }.ifEmpty { "no plugins in ${options.directories.joinToString()}" }
        "enable" -> enable(id ?: return "plugin enable <id>") ?: "enabled"
        "disable" -> disable(id ?: return "plugin disable <id>") ?: "disabled"
        "reload" -> reload(id) ?: "reloaded"
        else -> "plugin list | enable <id> | disable <id> | reload [id]"
    }
}
