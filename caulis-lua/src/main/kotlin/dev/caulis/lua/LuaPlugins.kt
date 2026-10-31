package dev.caulis.lua

import dev.caulis.plugins.Plugin
import dev.caulis.plugins.PluginClassLoader
import dev.caulis.plugins.PluginCode
import dev.caulis.plugins.PluginContext
import dev.caulis.plugins.PluginFiles
import dev.caulis.plugins.PluginFormat
import dev.caulis.plugins.PluginFormatProvider
import dev.caulis.plugins.PluginLoadRequest
import dev.caulis.plugins.PluginManifest
import dev.caulis.plugins.PluginManifestException
import dev.caulis.plugins.PluginSource
import dev.caulis.wm.plugins.PluginApi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/** What a compositor adds to the table Lua plugins get (newm: bar modules, launcher providers, ...). */
fun interface LuaPluginExtras {
    /** Adds functions through [table] for the plugin whose API is [api]. */
    fun add(table: LuaPluginTable, api: PluginApi)
}

/**
 * The table Lua plugins get, as far as a compositor adds to it. Arguments come as plain values:
 * text, Double, Boolean, null, a Map or List for a table, and a function as [LuaCallback].
 */
interface LuaPluginTable {
    fun function(name: String, body: (List<Any?>) -> Any?)

    /** A handle Lua can take away: `handle.remove()`. Give it what the API returned; a function can return it. */
    fun handle(closing: AutoCloseable): Any
}

/** A Lua function, callable from Kotlin; a mistake or too long a run disables the plugin. */
fun interface LuaCallback {
    operator fun invoke(arguments: List<Any?>): Any?
}

/**
 * Plugins in Lua: a file `name.lua` in a plugin folder, or a folder with `caulis-plugin.properties`
 * and `main.lua`. A file says what the manifest says in comments at its top, all of them optional:
 *
 * ```lua
 * -- id: my-plugin
 * -- version: 1.0
 * -- api: caulis:0.1
 * -- description: says hello
 * -- depends: other-plugin
 * plugin.bind("Super+H", function() plugin.notify("Hello") end)
 * ```
 *
 * A sandbox: no files, no programs (without the user's `allow`), no Java, no raw keys, and a time limit
 * per call; what it does comes through the table `plugin`. Saved again, it runs anew.
 */
class LuaPluginFormat @JvmOverloads constructor(private val extras: LuaPluginExtras? = null) : PluginFormat<PluginApi> {
    override val name get() = "lua"

    override fun recognize(path: Path, defaultApi: String): PluginSource<PluginApi>? {
        val folder = Files.isDirectory(path)
        if (folder && !Files.isRegularFile(path.resolve("main.lua"))) return null
        if (!folder && path.extension != "lua") return null
        val where = path.fileName.toString()
        val manifest = if (folder) {
            val file = path.resolve(PluginManifest.FILE_NAME)
            if (!Files.isRegularFile(file)) throw PluginManifestException("$where: no ${PluginManifest.FILE_NAME} next to main.lua")
            Files.newBufferedReader(file).use { PluginManifest.read(it, defaultApi, where) }
        } else {
            PluginManifest.of(header(path, defaultApi), defaultApi, where)
        }
        return Source(path, if (folder) path.resolve("main.lua") else path, manifest, extras)
    }

    // The comments at the top of the file, and what they leave out.
    private fun header(file: Path, defaultApi: String): Map<String, String> {
        val fields = linkedMapOf(
            "id" to file.nameWithoutExtension.lowercase().replace('_', '-'),
            "version" to "0.0.0",
            "api" to "$defaultApi:0.1",
        )
        Files.newBufferedReader(file).useLines { lines ->
            for (line in lines) {
                val match = HEADER.matchEntire(line.trim()) ?: if (line.isBlank() || line.trim().startsWith("--")) continue else break
                fields[match.groupValues[1]] = match.groupValues[2].trim()
            }
        }
        return fields
    }

    private class Source(
        override val path: Path,
        private val main: Path,
        override val manifest: PluginManifest,
        private val extras: LuaPluginExtras?,
    ) : PluginSource<PluginApi> {
        override fun fingerprint() = PluginFiles.fingerprint(path)

        override fun load(request: PluginLoadRequest): PluginCode<PluginApi> {
            val code = Files.readString(main)
            return object : PluginCode<PluginApi> {
                override val classLoader: PluginClassLoader? get() = null
                override fun newPlugin(): Plugin<PluginApi> = LuaPlugin(main, code, extras)
                override fun close() {}
            }
        }
    }

    private companion object {
        val HEADER = Regex("--\\s*(id|version|api|name|description|depends):\\s*(.*)")
    }
}

/** Finds [LuaPluginFormat] when caulis-lua is on the class path. */
class LuaPluginFormatProvider : PluginFormatProvider {
    override fun format(): PluginFormat<*> = LuaPluginFormat()
}

// One enabling of a Lua plugin: its own Lua state, gone when it is disabled.
internal class LuaPlugin(private val main: Path, private val code: String, private val extras: LuaPluginExtras?) : Plugin<PluginApi> {
    override fun enable(context: PluginContext<PluginApi>) {
        val sandbox = LuaPluginSandbox(context, main.parent, extras)
        sandbox.run(code, main.fileName.toString())
    }
}
