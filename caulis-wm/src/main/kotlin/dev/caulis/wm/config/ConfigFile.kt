package dev.caulis.wm.config

import dev.caulis.log.Logger
import dev.caulis.wm.Caulis
import java.nio.file.Files
import java.nio.file.Path

/**
 * The configuration as data: `config.json` in the compositor's folder (written by hand, or
 * by the Nix module) — read, checked against [Settings], and applied on top of what the
 * code says. Each setting stands on its own: a mistake in one is reported (with its line)
 * and the others still apply.
 */
object ConfigFile {

    private val log = Logger("config")

    /** What reading a file found: the settings to apply, and what is wrong. */
    class Read(val values: Map<String, Any?>, val problems: List<String>)

    /** Reads and checks [text] (nothing is applied). */
    fun read(text: String): Read {
        val json = try {
            Json.parse(text)
        } catch (e: JsonException) {
            return Read(emptyMap(), listOf(e.message ?: "not JSON"))
        }
        val obj = json as? JsonValue.Obj ?: return Read(emptyMap(), listOf("line ${json.line}: the file should be an object { ... }, not ${json.kind}"))
        return read(obj)
    }

    /**
     * Reads and checks settings given as JSON values (from another language: their lines say
     * where each was written).
     */
    fun read(obj: JsonValue.Obj): Read {
        val problems = Problems()
        val values = LinkedHashMap<String, Any?>()
        for ((name, value) in obj.fields) {
            // One setting at a time: a mistake in it leaves the others in.
            val one = Settings.root.read(JsonValue.Obj(linkedMapOf(name to value), obj.line, obj.column), "", problems)
            if (one is Map<*, *>) one.forEach { (k, v) -> values[k as String] = v }
        }
        return Read(values, problems.all)
    }

    /** Reads and checks the file at [path]: its problems (empty: all is well). */
    fun check(path: Path): List<String> = try {
        read(Files.readString(path)).problems
    } catch (e: Exception) {
        listOf("$path cannot be read: ${e.message ?: e}")
    }

    /**
     * Where [caulis]'s file [name] (`config.json`, `config.lua`, ...) is: in
     * [Caulis.configFolder] — or, when there is none there, for the whole system in
     * `$XDG_CONFIG_DIRS/<configName>/` (`/etc/xdg/...`, where the NixOS module puts it). Null:
     * nowhere.
     */
    fun location(caulis: Caulis, name: String = "config.json"): Path? {
        val own = caulis.file(name)
        if (Files.isRegularFile(own) || caulis.configDir != null) return own.takeIf { Files.isRegularFile(it) }
        val dirs = System.getenv("XDG_CONFIG_DIRS")?.takeIf { it.isNotBlank() } ?: "/etc/xdg"
        return dirs.split(':').filter { it.startsWith("/") }
            .map { Path.of(it, caulis.configName, name) }
            .firstOrNull { Files.isRegularFile(it) }
    }

    /** The files of other languages a compositor may read, and the module that reads each. */
    val languageFiles = linkedMapOf("config.lua" to "dev.caulis:caulis-lua", "config.kts" to "dev.caulis:caulis-script")

    /** The languages this program has (their modules are on the class path). */
    val languages: List<ConfigLanguage> by lazy {
        java.util.ServiceLoader.load(ConfigLanguage::class.java, ConfigFile::class.java.classLoader).toList()
    }

    /**
     * Applies [caulis]'s `config.json`, then `config.lua`, then `config.kts` (each on top of
     * the one before, all on top of what the code says) — those there are. Their problems go
     * to the log (and so on screen) and come back. Applied again, what they added before
     * (rules, keys, events) is replaced.
     */
    fun applyTo(caulis: Caulis): List<String> {
        for ((list, items) in caulis.configAdded) {
            list.removeIf { item -> items.any { it === item } }
            // Timers it started end too (applied again, it starts its own anew).
            items.filterIsInstance<Caulis.WaitingTimer>().forEach { it.close() }
        }
        val lists = caulis.addableLists()
        val sizes = lists.map { it.size }
        val problems = ArrayList<String>()
        location(caulis)?.let { problems += applyJson(caulis, it) }
        for ((name, module) in languageFiles) {
            val path = location(caulis, name) ?: continue
            val language = languages.firstOrNull { it.fileName == name }
            if (language == null) {
                problems += "$name is there, but this compositor cannot read it (it needs $module)"
                continue
            }
            val found = try {
                language.apply(caulis, path)
            } catch (e: Exception) {
                listOf("$name could not be applied: ${e.message ?: e}")
            }
            log.info { "Applied $path${if (found.isEmpty()) "" else " (${found.size} problems)"}" }
            problems += found
        }
        caulis.configAdded = lists.mapIndexed { i, list -> list to list.subList(sizes[i], list.size).toList() }
        problems.forEach(log::warn)
        return problems
    }

    private fun applyJson(caulis: Caulis, path: Path): List<String> {
        val read = try {
            read(Files.readString(path))
        } catch (e: Exception) {
            return listOf("$path cannot be read: ${e.message ?: e}")
        }
        val problems = read.problems.map { "config.json, $it" }.toMutableList()
        problems += Settings.applyEach(caulis, read.values).map { "config.json, $it" }
        log.info { "Applied $path (${read.values.size} settings${if (problems.isEmpty()) "" else ", ${problems.size} problems"})" }
        return problems
    }
}

/**
 * A language a compositor's configuration can be written in besides Kotlin and JSON — Lua
 * (caulis-lua), Kotlin script (caulis-script). Found through java.util.ServiceLoader: a module
 * on the class path that has one is used for its [fileName].
 */
interface ConfigLanguage {

    /** The file it reads in the compositor's folder: "config.lua". */
    val fileName: String

    /**
     * Applies [file] to [caulis] (not running yet: before the compositor starts, and on a fresh
     * configuration each time the file changes). Returns what is wrong, each with where
     * ("config.lua:12: ..."); what is right still applies.
     */
    fun apply(caulis: Caulis, file: Path): List<String>

    /** Annotations of its API for editors (LuaLS for Lua), or null. */
    fun stubs(): String? = null
}
