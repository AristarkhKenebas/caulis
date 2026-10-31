package dev.caulis.wm.plugins

import dev.caulis.plugins.PluginFormat
import dev.caulis.plugins.PluginHandle
import dev.caulis.wm.Caulis
import java.nio.file.Path

/**
 * `plugins { }`: where plugins are, and how much they are forgiven. By default the folder `plugins`
 * in the configuration's folder is followed; no folder or nothing in it costs next to nothing.
 *
 * Jar plugins run with your rights and are not sandboxed: installing one is running a program.
 * Lua plugins are sandboxed. See the documentation of plugins.
 */
class Plugins {
    /** False: no plugin runs and no folder is followed. Only at the start. */
    var enabled = true

    /** The folders plugins are in, the first first. Empty: `plugins` in the configuration's folder. Only at the start. */
    var directories: List<Path> = emptyList()

    /** Ids of plugins that stay off (also: `plugin disable <id>`, kept in the state folder). */
    var disabled: Set<String> = emptySet()

    /** A jar replaced is hot-reloaded, one added is started, one taken away is unloaded; a Lua file reloads on save. Only at the start. */
    var watch = true

    /** A changed file must be quiet this long before it is read (it may still be being copied). */
    var settleMillis = 300L

    /** How many failures before a plugin is disabled (1: the first). */
    var failuresBeforeDisable = 1

    /** A call into a plugin on the compositor's thread longer than this is reported with its stack (0: never). */
    var hangReportMillis = 2_000L

    /** A call this long has its thread interrupted (ends a wait or a sleep; 0: never). */
    var hangInterruptMillis = 5_000L

    /** A call this long is written down: the next start leaves the plugin off (0: never). */
    var hangQuarantineMillis = 10_000L

    /** A plugin that held up the desktop is disabled once its call returns. */
    var disableAfterHang = true

    /** After an unload: when to look whether its class loader was collected, with one full collection (0: never). */
    var leakCheckMillis = 30_000L

    /** With this many class loaders left behind, hot reloads are refused until a restart. */
    var maxLeakedGenerations = 8

    /** Run also what others can write to (normally refused: that would run their code as you). */
    var allowUnsafePermissions = false

    /** Where what `plugin disable` and a hang said is kept between sessions. Null: plugins.properties in the state folder. */
    var stateFile: Path? = null

    /** What each plugin is told, by id (see [dev.caulis.plugins.PluginSettings]). */
    val settings = LinkedHashMap<String, Map<String, Any?>>()

    /** Tells the plugin [id] its settings: `settings("clock", "format" to "HH:mm")`. */
    fun settings(id: String, vararg values: Pair<String, Any?>) {
        settings[id] = values.toMap()
    }

    /** What Lua plugins may do beyond their sandbox, by id: "exec" (start programs). Only you can give it; a plugin cannot ask for itself. */
    val allow = LinkedHashMap<String, Set<String>>()

    /** Gives the Lua plugin [id] [rights]: `allow("my-plugin", "exec")`. */
    fun allow(id: String, vararg rights: String) {
        allow[id] = rights.toSet()
    }

    // For the compositor's author (not the user).

    /** What your plugins get besides Caulis's API: [apiFor] makes it, [api] names it with its version. */
    fun api(name: String, major: Int, minor: Int, apiFor: (handle: PluginHandle, caulis: () -> Caulis) -> PluginApi) {
        extraApis[name] = dev.caulis.plugins.ApiVersion(major, minor)
        this.apiFor = apiFor
    }

    /** More kinds of plugin file. caulis-lua's is found by itself when it is on the class path. */
    fun format(format: PluginFormat<PluginApi>) {
        formats += format
    }

    /** More packages that come from the compositor only, not from a plugin's jar ("androidx.compose."). */
    fun sharePackages(vararg prefixes: String) {
        sharedPackages += prefixes
    }

    internal val extraApis = LinkedHashMap<String, dev.caulis.plugins.ApiVersion>()
    internal var apiFor: ((PluginHandle, () -> Caulis) -> PluginApi)? = null
    internal val formats = ArrayList<PluginFormat<PluginApi>>()
    internal val sharedPackages = ArrayList<String>()

    internal fun copyInto(other: Plugins) {
        for (field in Plugins::class.java.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val value = field.get(this)
            when (val target = field.get(other)) {
                is MutableMap<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    (target as MutableMap<Any?, Any?>).apply { clear(); putAll(value as Map<*, *>) }
                }
                is MutableList<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (target as MutableList<Any?>).apply { clear(); addAll(value as List<*>) }
                }
                else -> field.set(other, value)
            }
        }
    }

    companion object {
        /** The version of the API jar plugins are compiled against, what `api = caulis:0.1` asks for. */
        const val API_MAJOR = 0
        const val API_MINOR = 1
    }
}

/** For tests of compositors: what the manager holds. */
@dev.caulis.log.InternalCaulisApi
object PluginProbe {
    fun classLoader(caulis: Caulis, id: String): ClassLoader? = caulis.liveOrNull()?.rplugins?.manager?.classLoaderOf(id)
}
