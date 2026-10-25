package dev.caulis.wm.config

import dev.caulis.log.InternalCaulisApi

/**
 * What a setting of the configuration file may be: read from JSON with plain messages
 * (and where), and described for Nix (typed options) and JSON Schema (editors) — one
 * description, so the limits are the same everywhere.
 */
@InternalCaulisApi
abstract class SettingType {

    /** The value of [json] in Kotlin terms, or null after adding what is wrong to [problems]. */
    abstract fun read(json: JsonValue, path: String, problems: Problems): Any?

    /** The Nix type (lib.types ...). */
    abstract fun nix(): String

    /** The JSON Schema of it. */
    abstract fun jsonSchema(): String

    /** What it is, in words (for messages and the generated documentation). */
    abstract val words: String
}

/** The mistakes found in a configuration, each with where it is. */
@InternalCaulisApi
class Problems {
    val all = ArrayList<String>()

    fun add(path: String, json: JsonValue?, message: String) {
        all += (if (json != null) "line ${json.line}: " else "") + (if (path.isEmpty()) "" else "$path: ") + message
    }

    fun isEmpty() = all.isEmpty()
}

internal fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** A whole number from [min] to [max]. */
@InternalCaulisApi
class IntegerType(val min: Int, val max: Int) : SettingType() {
    override val words get() = "a whole number from $min to $max"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val number = (json as? JsonValue.Num)?.value
        if (number == null || number != Math.floor(number)) return problems.add(path, json, "$words, not ${json.kind}").let { null }
        if (number < min || number > max) return problems.add(path, json, "$min to $max, not ${number.toLong()}").let { null }
        return number.toInt()
    }

    override fun nix() = "(lib.types.ints.between ($min) ($max))"
    override fun jsonSchema() = """{"type":"integer","minimum":$min,"maximum":$max}"""
}

/** A number from [min] to [max]. */
@InternalCaulisApi
class DecimalType(val min: Double, val max: Double) : SettingType() {
    override val words get() = "a number from ${fmt(min)} to ${fmt(max)}"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val number = (json as? JsonValue.Num)?.value ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
        if (number < min || number > max) return problems.add(path, json, "${fmt(min)} to ${fmt(max)}, not ${fmt(number)}").let { null }
        return number
    }

    override fun nix() = "(lib.types.addCheck lib.types.number (x: x >= ${fmt(min)} && x <= ${fmt(max)}) // { description = ${quote(words)}; })"
    override fun jsonSchema() = """{"type":"number","minimum":${fmt(min)},"maximum":${fmt(max)}}"""

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}

@InternalCaulisApi
object FlagType : SettingType() {
    override val words get() = "true or false"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? =
        (json as? JsonValue.Bool)?.value ?: problems.add(path, json, "true or false, not ${json.kind}").let { null }

    override fun nix() = "lib.types.bool"
    override fun jsonSchema() = """{"type":"boolean"}"""
}

/** Any text (not empty). */
@InternalCaulisApi
class TextType(private val description: String = "text") : SettingType() {
    override val words get() = description

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val text = (json as? JsonValue.Str)?.value ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
        if (text.isBlank()) return problems.add(path, json, "empty").let { null }
        return text
    }

    override fun nix() = "lib.types.str"
    override fun jsonSchema() = """{"type":"string","minLength":1}"""
}

/** A regular expression: checked here, so a broken one is a problem with its line, not a failure later. */
@InternalCaulisApi
object RegexType : SettingType() {
    override val words get() = "a regular expression"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val text = (json as? JsonValue.Str)?.value ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
        if (text.isBlank()) return problems.add(path, json, "empty").let { null }
        return try {
            Regex(text)
            text
        } catch (e: IllegalArgumentException) {
            problems.add(path, json, "not $words: ${e.message?.lineSequence()?.first()}").let { null }
        }
    }

    override fun nix() = "lib.types.str"
    override fun jsonSchema() = """{"type":"string","minLength":1,"format":"regex"}"""
}

/**
 * A file: a name next to the configuration, `~/...` or `/...` — and in Nix also a path
 * (`./aurora.glsl`), which goes into the store and so into config.json as its store path.
 */
@InternalCaulisApi
class FileType(private val description: String = "a file name") : SettingType() {
    private val text = TextType(description)
    override val words get() = description
    override fun read(json: JsonValue, path: String, problems: Problems): Any? = text.read(json, path, problems)
    override fun nix() = "(lib.types.either lib.types.str lib.types.path)"
    override fun jsonSchema() = text.jsonSchema()
}

/** One of some names (case and - or _ do not matter); read as written in [values]. */
@InternalCaulisApi
class ChoiceType(val values: List<String>) : SettingType() {
    override val words get() = "one of ${values.joinToString { "\"$it\"" }}"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val text = (json as? JsonValue.Str)?.value ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
        fun norm(s: String) = s.lowercase().replace("_", "-").replace(" ", "-")
        return values.firstOrNull { norm(it) == norm(text) } ?: problems.add(path, json, "\"$text\" is not $words").let { null }
    }

    override fun nix() = "(lib.types.enum [ ${values.joinToString(" ") { quote(it) }} ])"
    override fun jsonSchema() = """{"enum":[${values.joinToString(",") { quote(it) }}]}"""
}

/** A colour as "#rrggbb" or "#rrggbbaa". */
@InternalCaulisApi
object ColorType : SettingType() {
    private val pattern = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    override val words get() = "a colour, \"#rrggbb\" or \"#rrggbbaa\""

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val text = (json as? JsonValue.Str)?.value ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
        if (!pattern.matches(text)) return problems.add(path, json, "\"$text\" is not $words").let { null }
        return dev.caulis.render.Color.hex(text)
    }

    override fun nix() = """(lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")"""
    override fun jsonSchema() = """{"type":"string","pattern":"^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$"}"""
}

/** A list of [item], [min] to [max] long. */
@InternalCaulisApi
class ListType(val item: SettingType, val min: Int = 0, val max: Int = 1000) : SettingType() {
    override val words get() = if (min == max) "a list of $min (${item.words})" else "a list of ${item.words}"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val list = (json as? JsonValue.Arr)?.items ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
        if (list.size < min || list.size > max) {
            return problems.add(path, json, if (min == max) "$min items, not ${list.size}" else "$min to $max items, not ${list.size}").let { null }
        }
        val values = list.mapIndexed { i, value -> item.read(value, "$path[$i]", problems) }
        return if (values.any { it == null }) null else values
    }

    override fun nix(): String {
        val base = "(lib.types.listOf ${item.nix()})"
        return if (min == 0 && max == 1000) base else "(lib.types.addCheck $base (l: builtins.length l >= $min && builtins.length l <= $max))"
    }

    override fun jsonSchema() = """{"type":"array","items":${item.jsonSchema()},"minItems":$min,"maxItems":$max}"""
}

/** Names of the caller's choosing, each with a [value] (monitors by connector, keys and their actions). */
@InternalCaulisApi
class MapType(val value: SettingType, private val names: String = "name") : SettingType() {
    override val words get() = "$names → ${value.words}"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val fields = (json as? JsonValue.Obj)?.fields ?: emptyIfEmptyList(json) ?: return problems.add(path, json, "an object ($words), not ${json.kind}").let { null }
        val out = LinkedHashMap<String, Any?>()
        var ok = true
        for ((name, v) in fields) {
            val read = value.read(v, if (path.isEmpty()) name else "$path.\"$name\"", problems)
            if (read == null) ok = false else out[name] = read
        }
        return if (ok) out else null
    }

    override fun nix() = "(lib.types.attrsOf ${value.nix()})"
    override fun jsonSchema() = """{"type":"object","additionalProperties":${value.jsonSchema()}}"""
}

/** [first] or [second] (e.g. a size for all sides, or one per side). */
@InternalCaulisApi
class EitherType(val first: SettingType, val second: SettingType) : SettingType() {
    override val words get() = "${first.words}, or ${second.words}"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val trial = Problems()
        first.read(json, path, trial)?.let { return it }
        val trial2 = Problems()
        second.read(json, path, trial2)?.let { return it }
        // Say what is wrong with the one it looks more like.
        problems.all += if (looksLike(json, second)) trial2.all else trial.all
        return null
    }

    private fun looksLike(json: JsonValue, type: SettingType) = when (type) {
        is ListType -> json is JsonValue.Arr
        is SectionType, is MapType -> json is JsonValue.Obj
        else -> false
    }

    override fun nix() = "(lib.types.either ${first.nix()} ${second.nix()})"
    override fun jsonSchema() = """{"anyOf":[${first.jsonSchema()},${second.jsonSchema()}]}"""
}

/** A setting that can be turned off: `false` (or null in JSON) means off. */
@InternalCaulisApi
class OffOrType(val value: SettingType) : SettingType() {
    override val words get() = "${value.words}, or false (off)"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? = when {
        json is JsonValue.Null || (json is JsonValue.Bool && !json.value) -> Off
        else -> value.read(json, path, problems)
    }

    override fun nix() = "(lib.types.either (lib.types.enum [ false ]) ${value.nix()})"
    override fun jsonSchema() = """{"anyOf":[{"const":false},{"type":"null"},${value.jsonSchema()}]}"""

    /** What a turned-off setting reads as. */
    @InternalCaulisApi
    object Off
}

/** One field of a [SectionType]; a [required] one must be there. */
@InternalCaulisApi
class Field(val name: String, val type: SettingType, val description: String, val required: Boolean = false)

/** An object with known fields (all of them optional); unknown names are mistakes. */
@InternalCaulisApi
class SectionType(val fields: List<Field>, private val description: String = "a section") : SettingType() {
    override val words get() = description

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        // (An empty list is an empty section too: Lua writes both as {}.)
        val given = (json as? JsonValue.Obj)?.fields ?: emptyIfEmptyList(json) ?: return problems.add(path, json, "an object, not ${json.kind}").let { null }
        val out = LinkedHashMap<String, Any?>()
        val before = problems.all.size
        for ((name, value) in given) {
            val field = fields.firstOrNull { it.name == name }
            val at = if (path.isEmpty()) name else "$path.$name"
            if (field == null) {
                val near = fields.minByOrNull { distance(it.name.lowercase(), name.lowercase()) }?.takeIf { distance(it.name.lowercase(), name.lowercase()) <= 2 }
                problems.add(at, value, "there is no such setting" + (near?.let { " (did you mean \"${it.name}\"?)" } ?: " (there are: ${fields.joinToString { it.name }})"))
                continue
            }
            out[name] = field.type.read(value, at, problems) ?: continue
        }
        for (field in fields) if (field.required && field.name !in given) problems.add(path, json, "it needs \"${field.name}\"")
        return if (problems.all.size == before) out else null
    }

    override fun nix(): String = buildString {
        append("(lib.types.submodule { options = {\n")
        for (field in fields) {
            val type = field.type.nix().replace("\n", "\n  ")
            append("  ${quote(field.name)} = lib.mkOption { type = lib.types.nullOr $type; default = null; description = ${quote(field.description)}; };\n")
        }
        append("}; })")
    }

    override fun jsonSchema() =
        """{"type":"object","additionalProperties":false,"properties":{""" +
            fields.joinToString(",") { "${quote(it.name)}:" + it.type.jsonSchema().dropLast(1) + ""","description":${quote(it.description)}}""" } + "}" +
            fields.filter { it.required }.takeIf { it.isNotEmpty() }?.joinToString(",", ""","required":[""", "]") { quote(it.name) }.orEmpty() + "}"

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }
}

// [json] if it is an empty list, as an empty object's fields (else null).
private fun emptyIfEmptyList(json: JsonValue): Map<String, JsonValue>? =
    if (json is JsonValue.Arr && json.items.isEmpty()) emptyMap() else null
