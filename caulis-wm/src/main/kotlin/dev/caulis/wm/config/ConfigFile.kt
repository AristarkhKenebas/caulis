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
     * Where [caulis]'s `config.json` is: in [Caulis.configFolder] — or, when there is none
     * there, for the whole system in `$XDG_CONFIG_DIRS/<configName>/` (`/etc/xdg/...`, where
     * the NixOS module puts it). Null: nowhere.
     */
    fun location(caulis: Caulis): Path? {
        val own = caulis.file("config.json")
        if (Files.isRegularFile(own) || caulis.configDir != null) return own.takeIf { Files.isRegularFile(it) }
        val dirs = System.getenv("XDG_CONFIG_DIRS")?.takeIf { it.isNotBlank() } ?: "/etc/xdg"
        return dirs.split(':').filter { it.startsWith("/") }
            .map { Path.of(it, caulis.configName, "config.json") }
            .firstOrNull { Files.isRegularFile(it) }
    }

    /**
     * Applies [caulis]'s `config.json` (see [location]), if there is one; its problems go to
     * the log (and so on screen) and come back.
     */
    fun applyTo(caulis: Caulis): List<String> {
        val path = location(caulis) ?: return emptyList()
        val read = try {
            read(Files.readString(path))
        } catch (e: Exception) {
            return listOf("$path cannot be read: ${e.message ?: e}").onEach(log::warn)
        }
        val problems = read.problems.map { "config.json, $it" }.toMutableList()
        // Applied once more (the file changed): its rules, keys and programs replace the last ones.
        caulis.configAdded?.let { last ->
            caulis.rules.removeAll(last.rules.toSet())
            caulis.bindings.removeAll(last.bindings.toSet())
            caulis.startActions.removeAll(last.startup.toSet())
        }
        try {
            caulis.configAdded = Settings.apply(caulis, read.values)
            log.info { "Applied $path (${read.values.size} settings${if (problems.isEmpty()) "" else ", ${problems.size} problems"})" }
        } catch (e: Exception) {
            problems += "config.json could not be applied in full: ${e.message ?: e}"
        }
        problems.forEach(log::warn)
        return problems
    }
}
