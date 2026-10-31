package dev.caulis.plugins

import java.io.Reader
import java.util.Properties

/** A plugin's manifest is wrong: what is wrong, in words for the person who installed it. */
class PluginManifestException(message: String) : Exception(message)

/** Another plugin this one needs. */
data class PluginDependency(
    val id: String,
    /** The lowest version that will do (null: any). */
    val atLeast: PluginVersion? = null,
    /** True: works without it too, but starts after it when it is there. */
    val optional: Boolean = false,
) {
    override fun toString() = id + (atLeast?.let { ">=$it" } ?: "") + (if (optional) "?" else "")
}

/**
 * What a plugin says of itself: `caulis-plugin.properties` at the top of a jar (or in a plugin's
 * folder), in Java's properties format:
 *
 * ```
 * id = my-plugin                     # small letters, digits and -; its name everywhere
 * version = 1.2.0
 * api = caulis:0.1                   # the API it was written for; several: caulis:0.1, newm:0.1
 * main = org.example.MyPlugin        # (jar plugins) the class that implements Plugin
 * name = My plugin                   # optional: what people read
 * description = Says hello           # optional
 * depends = other-plugin>=1.0, nice-to-have?     # optional
 * ```
 */
data class PluginManifest(
    val id: String,
    val version: PluginVersion,
    /** The APIs it was written for: name to version. */
    val apis: Map<String, ApiVersion>,
    val main: String? = null,
    val name: String = id,
    val description: String = "",
    val dependencies: List<PluginDependency> = emptyList(),
) {
    init {
        require(isId(id)) { "\"$id\" is not a plugin id (small letters, digits and -, starting with a letter)" }
    }

    companion object {
        /** The manifest's file name. */
        const val FILE_NAME = "caulis-plugin.properties"

        private val ID = Regex("[a-z][a-z0-9-]*")

        /** Whether [id] can be a plugin's id. */
        @JvmStatic
        fun isId(id: String): Boolean = ID.matches(id) && id.length <= 64

        /** Reads a manifest; [defaultApi] is the API name of a bare `api = 0.1`. Throws [PluginManifestException]. */
        @JvmStatic
        fun read(reader: Reader, defaultApi: String, where: String): PluginManifest {
            val properties = Properties()
            try {
                properties.load(reader)
            } catch (e: java.io.IOException) {
                throw PluginManifestException("$where: cannot be read: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw PluginManifestException("$where: cannot be read: ${e.message}")
            }
            return of(properties.stringPropertyNames().associateWith { properties.getProperty(it).trim() }, defaultApi, where)
        }

        /** A manifest from its [fields] (see the class's description). Throws [PluginManifestException]. */
        @JvmStatic
        fun of(fields: Map<String, String>, defaultApi: String, where: String): PluginManifest {
            fun wanted(name: String) = fields[name]?.ifBlank { null } ?: throw PluginManifestException("$where: no \"$name\" in the manifest")
            fun <T> checked(body: () -> T): T = try {
                body()
            } catch (e: IllegalArgumentException) {
                throw PluginManifestException("$where: ${e.message}")
            }
            val id = wanted("id")
            if (!isId(id)) throw PluginManifestException("$where: \"$id\" is not a plugin id (small letters, digits and -, starting with a letter)")
            return PluginManifest(
                id = id,
                version = checked { PluginVersion.parse(wanted("version")) },
                apis = checked {
                    wanted("api").split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { item ->
                        val named = item.contains(':')
                        val apiName = if (named) item.substringBefore(':').trim() else defaultApi
                        apiName to ApiVersion.parse(item.substringAfter(':').trim())
                    }
                },
                main = fields["main"]?.ifBlank { null },
                name = fields["name"]?.ifBlank { null } ?: id,
                description = fields["description"].orEmpty(),
                dependencies = checked { fields["depends"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::dependency) },
            )
        }

        private fun dependency(text: String): PluginDependency {
            val optional = text.endsWith("?")
            val bare = text.removeSuffix("?")
            val id = bare.substringBefore(">=").trim()
            require(isId(id)) { "\"$text\" is not a dependency (other-plugin, other-plugin>=1.0, other-plugin?)" }
            val least = if (">=" in bare) PluginVersion.parse(bare.substringAfter(">=").trim()) else null
            return PluginDependency(id, least, optional)
        }
    }
}

/** A plugin's version: numbers with dots, compared as numbers; what follows a `-` is only shown. */
class PluginVersion private constructor(private val numbers: List<Int>, private val text: String) : Comparable<PluginVersion> {
    override fun compareTo(other: PluginVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val difference = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    override fun equals(other: Any?) = other is PluginVersion && compareTo(other) == 0
    override fun hashCode() = numbers.dropLastWhile { it == 0 }.hashCode()
    override fun toString() = text

    companion object {
        @JvmStatic
        fun parse(text: String): PluginVersion {
            val numbers = text.substringBefore('-').split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } }
            require(text.isNotBlank() && numbers.all { it != null }) { "\"$text\" is not a version (numbers with dots: 1.2.0)" }
            return PluginVersion(numbers.map { it!! }, text)
        }
    }
}

/**
 * The version of an API, `major.minor`. A plugin written for 1.2 runs on 1.2 and 1.5, not on 1.1 or
 * 2.0. While the major is 0 the same holds: within 0.x the minor only grows when the API does, and
 * what breaks plugins raises the major's successor in the changelog.
 */
data class ApiVersion(val major: Int, val minor: Int) {
    /** Whether a compositor offering this version runs a plugin written for [wanted]. */
    fun runs(wanted: ApiVersion): Boolean = major == wanted.major && minor >= wanted.minor

    override fun toString() = "$major.$minor"

    companion object {
        @JvmStatic
        fun parse(text: String): ApiVersion {
            val parts = text.split('.').map { it.toIntOrNull() }
            require(parts.size == 2 && parts.all { it != null && it >= 0 }) { "\"$text\" is not an API version (major.minor: 0.1)" }
            return ApiVersion(parts[0]!!, parts[1]!!)
        }
    }
}
