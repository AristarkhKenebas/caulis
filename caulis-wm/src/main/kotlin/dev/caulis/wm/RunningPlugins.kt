package dev.caulis.wm

import dev.caulis.core.NoticeLevel
import dev.caulis.log.Logger
import dev.caulis.plugins.ApiVersion
import dev.caulis.plugins.PluginFormat
import dev.caulis.plugins.PluginFormatProvider
import dev.caulis.plugins.PluginHandle
import dev.caulis.plugins.PluginHost
import dev.caulis.plugins.PluginManager
import dev.caulis.plugins.command
import dev.caulis.plugins.PluginOptions
import dev.caulis.wm.config.Actions
import dev.caulis.wm.plugins.PluginApi
import dev.caulis.wm.plugins.Plugins
import java.nio.file.Path

// The action `plugin ...`: what it says is shown as a notice.
internal fun Caulis.runPluginAction(text: String) {
    val said = live().rplugins.command(text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
    if (said.isNotEmpty()) notify(said, title = "Plugins")
}

/**
 * The running compositor's plugins: the manager, and what plugins registered that belongs to no
 * configuration (their actions and commands stay through reloads of the configuration).
 */
internal class RunningPlugins(private val running: Running) : AutoCloseable {
    private val log = Logger("plugins")

    /** Their actions, by name. */
    val actions = LinkedHashMap<String, Actions.Own>()

    /** Their commands (`--plugin cmd <name> ...`, or the compositor's own way), by name. */
    val commands = LinkedHashMap<String, (String) -> String?>()

    /** Their layouts, by name. */
    val layouts = LinkedHashMap<String, () -> dev.caulis.tiling.Layout>()

    /** The screen shaders of plugins, the last one is shown. */
    val screenShaders = ArrayList<dev.caulis.core.ScreenShader>()

    var manager: PluginManager<PluginApi>? = null
        private set

    private val host = object : PluginHost<PluginApi> {
        override val apiName = "caulis"
        override val apis: Map<String, ApiVersion>
            get() = mapOf("caulis" to ApiVersion(Plugins.API_MAJOR, Plugins.API_MINOR)) + running.config.plugins.extraApis

        override fun apiFor(handle: PluginHandle): PluginApi =
            running.config.plugins.apiFor?.invoke(handle) { running.config } ?: PluginApi(handle) { running.config }

        override fun post(task: Runnable) {
            running.loop.post { if (running.isOpen) running.safely("plugins") { task.run() } }
        }

        override fun notice(problem: Boolean, title: String, text: String) {
            running.desktop.notify(text, if (problem) NoticeLevel.WARNING else NoticeLevel.INFO, title)
        }

        override fun dataDirFor(id: String): Path = stateFolder("XDG_DATA_HOME", ".local/share").resolve("plugins").resolve(id)

        // (A locked screen: a plugin's start could draw on it, and nobody is there to see what goes wrong.)
        override fun mayChangeNow() = !running.desktop.locked
    }

    private fun stateFolder(variable: String, usual: String): Path {
        val base = System.getenv(variable)?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home")).resolve(usual)
        return base.resolve(running.config.configName)
    }

    private fun options(settings: Plugins): PluginOptions = PluginOptions(
        directories = settings.directories.ifEmpty { listOf(running.config.configFolder.resolve("plugins")) },
        enabled = settings.enabled,
        disabled = settings.disabled,
        settings = settings.settings.toMap(),
        watch = settings.watch,
        settleMillis = settings.settleMillis,
        failuresBeforeDisable = settings.failuresBeforeDisable,
        hangReportMillis = settings.hangReportMillis,
        hangInterruptMillis = settings.hangInterruptMillis,
        hangQuarantineMillis = settings.hangQuarantineMillis,
        disableAfterHang = settings.disableAfterHang,
        leakCheckMillis = settings.leakCheckMillis,
        maxLeakedGenerations = settings.maxLeakedGenerations,
        allowUnsafePermissions = settings.allowUnsafePermissions,
        sharedPackages = dev.caulis.plugins.PluginClassLoader.DEFAULT_SHARED + settings.sharedPackages,
        stateFile = settings.stateFile ?: stateFolder("XDG_STATE_HOME", ".local/state").resolve("plugins.properties"),
        parentLoader = Caulis::class.java.classLoader,
    )

    /** At the start, on the compositor's thread. */
    fun start() {
        val settings = running.config.plugins
        if (!settings.enabled) {
            log.info { "Plugins are off" }
            return
        }
        // (caulis-lua and the like, when they are on the class path. What the compositor gave counts first.)
        val found = java.util.ServiceLoader.load(PluginFormatProvider::class.java, Caulis::class.java.classLoader).map { provider ->
            @Suppress("UNCHECKED_CAST")
            provider.format() as PluginFormat<PluginApi>
        }
        val formats = settings.formats + found.filter { one -> settings.formats.none { it.name == one.name } }
        manager = PluginManager(host, options(settings), formats).also { it.start() }
    }

    /** The configuration was replaced, or its file read again. */
    fun configurationChanged() {
        manager?.configure(options(running.config.plugins))
    }

    /** `plugin ...` as text (the action, IPC). */
    fun command(words: List<String>): String {
        val plugins = manager ?: return "plugins are off"
        return when (words.firstOrNull()) {
            "cmd" -> {
                val name = words.getOrNull(1) ?: return "plugin cmd <name> [arguments]; there are: ${commands.keys.joinToString().ifEmpty { "none" }}"
                val command = commands[name] ?: return "no plugin has the command $name"
                command(words.drop(2).joinToString(" ")).orEmpty()
            }
            "run" -> {
                val name = words.getOrNull(1) ?: return "plugin run <action> [argument]; there are: ${actions.keys.joinToString().ifEmpty { "none" }}"
                val action = actions[name] ?: return "no plugin has the action $name"
                action.run(running.config, words.drop(2).joinToString(" "), null)
                ""
            }
            "layout" -> {
                val name = words.getOrNull(1) ?: return "plugin layout <name>; there are: ${layouts.keys.joinToString().ifEmpty { "none" }}"
                val make = layouts[name] ?: return "no plugin has the layout $name"
                running.config.layout = make()
                ""
            }
            else -> plugins.command(words)
        }
    }

    override fun close() {
        val plugins = manager ?: return
        manager = null
        running.safely("plugins") { plugins.close() }
    }
}
