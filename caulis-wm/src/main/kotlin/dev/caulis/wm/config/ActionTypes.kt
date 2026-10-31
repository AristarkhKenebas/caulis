package dev.caulis.wm.config

import dev.caulis.log.InternalCaulisApi
import dev.caulis.core.input.KeyBindings
import dev.caulis.wm.Caulis

/** A key's action as text (see [Actions]), checked when read. */
@InternalCaulisApi
object ActionType : SettingType() {
    override val words get() = "an action (\"close\", \"exec firefox\", \"workspace 3\", ...)"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val text = (json as? JsonValue.Str)?.value ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
        // The name of the field is the key combination: checked too.
        val combination = path.substringAfterLast(".\"", "").removeSuffix("\"")
        if (combination.isNotEmpty()) {
            try {
                KeyBindings.parse(combination)
            } catch (e: IllegalArgumentException) {
                problems.add(path, json, e.message ?: "not a key combination")
                return null
            }
        }
        return try {
            Actions.parse(text)
        } catch (e: IllegalArgumentException) {
            problems.add(path, json, e.message ?: "not an action")
            null
        }
    }

    override fun nix() = "lib.types.str"
    override fun jsonSchema() = """{"type":"string","minLength":1}"""
}

/**
 * One of your keys in a file: an action ("exec firefox"), or one with how it behaves:
 * `{ "action": "exec wpctl ...", "repeat": true, "locked": true, "description": "louder" }`.
 * The combination (the name it is given under) is checked too.
 */
@InternalCaulisApi
object BindType : SettingType() {
    /** What a key does, and how it behaves. */
    @InternalCaulisApi
    class Bound(val action: Caulis.() -> Unit, val options: dev.caulis.wm.BindOptions)

    private val details = SectionType(
        listOf(
            Field("action", TextType("an action"), "what it does (\"exec firefox\", \"workspace 3\", ...)"),
            Field("repeat", FlagType, "repeats while held"),
            Field("release", FlagType, "runs when the key is let go"),
            Field("locked", FlagType, "works on a locked screen too"),
            Field("description", TextType(), "what it does, in words"),
        ),
        "a key",
    )

    override val words get() = "an action (\"exec firefox\"), or { action, repeat, release, locked, description }"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val combination = path.substringAfterLast(".\"", "").removeSuffix("\"")
        if (combination.isNotEmpty()) {
            try {
                KeyBindings.parseAllowingTap(combination)
            } catch (e: IllegalArgumentException) {
                problems.add(path, json, e.message ?: "not a key combination")
                return null
            }
        }
        val (text, options) = when (json) {
            is JsonValue.Str -> json.value to dev.caulis.wm.BindOptions()
            is JsonValue.Obj -> {
                @Suppress("UNCHECKED_CAST")
                val m = details.read(json, path, problems) as Map<String, Any?>? ?: return null
                val action = m["action"] as String? ?: return problems.add(path, json, "it needs an \"action\"").let { null }
                action to dev.caulis.wm.BindOptions(
                    repeat = m["repeat"] == true,
                    release = m["release"] == true,
                    whileLocked = m["locked"] == true,
                    description = m["description"] as String?,
                )
            }
            else -> return problems.add(path, json, "$words, not ${json.kind}").let { null }
        }
        return try {
            Bound(Actions.parse(text), options)
        } catch (e: IllegalArgumentException) {
            problems.add(path, json, e.message ?: "not an action")
            null
        }
    }

    override fun nix() = "(lib.types.either lib.types.str ${details.nix().replace("\n", "\n  ")})"
    override fun jsonSchema() = """{"anyOf":[{"type":"string","minLength":1},${details.jsonSchema()}]}"""
}

/** A key combination ("Super+Shift+Q"), or only modifiers ("Super+Shift") for a group of keys. */
@InternalCaulisApi
class CombinationType(private val modifiersOnly: Boolean) : SettingType() {
    override val words get() = if (modifiersOnly) "modifiers (\"Super\", \"Super+Shift\")" else "a key combination (\"Super+Q\")"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val text = (json as? JsonValue.Str)?.value ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
        return try {
            if (modifiersOnly) require(KeyBindings.modifiers(text).isNotEmpty()) { "\"$text\" has no modifier" } else KeyBindings.parse(text)
            text
        } catch (e: IllegalArgumentException) {
            problems.add(path, json, e.message ?: "not $words")
            null
        }
    }

    override fun nix() = "lib.types.str"
    override fun jsonSchema() = """{"type":"string","minLength":1}"""
}
