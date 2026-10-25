package dev.caulis.wm.config

/**
 * A JSON value as read from a configuration file, with where it was (for messages that
 * point at the line): an object, a list, a string, a number, true/false or null.
 */
sealed class JsonValue {
    /** Line and column (from 1) where it starts in the file. */
    abstract val line: Int
    abstract val column: Int

    data class Obj(val fields: LinkedHashMap<String, JsonValue>, override val line: Int, override val column: Int) : JsonValue()
    data class Arr(val items: List<JsonValue>, override val line: Int, override val column: Int) : JsonValue()
    data class Str(val value: String, override val line: Int, override val column: Int) : JsonValue()
    data class Num(val value: Double, override val line: Int, override val column: Int) : JsonValue()
    data class Bool(val value: Boolean, override val line: Int, override val column: Int) : JsonValue()
    data class Null(override val line: Int, override val column: Int) : JsonValue()

    /** What it is, in words, for messages. */
    val kind: String
        get() = when (this) {
            is Obj -> "an object"
            is Arr -> "a list"
            is Str -> "text"
            is Num -> "a number"
            is Bool -> "true or false"
            is Null -> "null"
        }
}

/** A mistake in a JSON file: what, and where. */
class JsonException(message: String, val line: Int, val column: Int) : Exception("line $line, column $column: $message")

/**
 * Reads JSON (as Nix writes it, or by hand: comments with // and /* */ and a comma after
 * the last item are forgiven, as people write them).
 */
object Json {

    /** How deep lists and sections may be nested. */
    const val MAX_DEPTH = 128

    /** [value] as text, two spaces in per level (short lists of numbers stay on one line). */
    fun write(value: JsonValue): String = buildString { write(value, "") }

    /** [value] as text on one line (a line per event, for scripts). */
    fun writeLine(value: JsonValue): String = buildString { line(value) }

    private fun StringBuilder.line(value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> {
                append('{')
                value.fields.entries.forEachIndexed { i, (name, v) ->
                    if (i > 0) append(',')
                    append(quote(name)).append(':')
                    line(v)
                }
                append('}')
            }
            is JsonValue.Arr -> {
                append('[')
                value.items.forEachIndexed { i, v -> if (i > 0) append(','); line(v) }
                append(']')
            }
            else -> write(value, "")
        }
    }

    private fun StringBuilder.write(value: JsonValue, indent: String) {
        val inner = "$indent  "
        when (value) {
            is JsonValue.Obj -> if (value.fields.isEmpty()) append("{}") else {
                append("{\n")
                value.fields.entries.forEachIndexed { i, (name, v) ->
                    append(inner).append(quote(name)).append(": ")
                    write(v, inner)
                    append(if (i < value.fields.size - 1) ",\n" else "\n")
                }
                append(indent).append('}')
            }
            is JsonValue.Arr -> if (value.items.all { it !is JsonValue.Obj && it !is JsonValue.Arr }) {
                append('[')
                value.items.forEachIndexed { i, v -> if (i > 0) append(", "); write(v, inner) }
                append(']')
            } else {
                append("[\n")
                value.items.forEachIndexed { i, v ->
                    append(inner)
                    write(v, inner)
                    append(if (i < value.items.size - 1) ",\n" else "\n")
                }
                append(indent).append(']')
            }
            is JsonValue.Str -> append(quote(value.value))
            is JsonValue.Num -> append(if (value.value == Math.floor(value.value) && Math.abs(value.value) < 1e15) value.value.toLong().toString() else value.value.toString())
            is JsonValue.Bool -> append(value.value)
            is JsonValue.Null -> append("null")
        }
    }

    // Also escapes what JSON does not allow raw in a string.
    private fun quote(text: String) = buildString {
        append('"')
        for (ch in text) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }

    fun parse(text: String): JsonValue = Reader(text).run {
        val value = value()
        space()
        if (!end) fail("more after the end of the document")
        value
    }

    private class Reader(val text: String) {
        var at = 0
        var line = 1
        var column = 1
        val end get() = at >= text.length

        fun fail(message: String): Nothing = throw JsonException(message, line, column)

        fun next(): Char {
            val c = text[at++]
            if (c == '\n') {
                line++
                column = 1
            } else {
                column++
            }
            return c
        }

        fun peek(): Char = if (end) '\u0000' else text[at]

        fun space() {
            while (!end) {
                val c = peek()
                when {
                    c.isWhitespace() -> next()
                    text.startsWith("//", at) -> while (!end && peek() != '\n') next()
                    text.startsWith("/*", at) -> {
                        next(); next()
                        while (!end && !text.startsWith("*/", at)) next()
                        if (end) fail("a /* comment is not closed")
                        next(); next()
                    }
                    else -> return
                }
            }
        }

        // How deep lists and sections are nested now (a limit keeps a hostile text from
        // overflowing the stack).
        var depth = 0

        fun value(): JsonValue {
            space()
            if (end) fail("the file ends where a value should be")
            val l = line
            val c = column
            return when (val ch = peek()) {
                '{', '[' -> {
                    if (++depth > MAX_DEPTH) fail("nested more than $MAX_DEPTH deep")
                    try {
                        if (ch == '{') obj(l, c) else arr(l, c)
                    } finally {
                        depth--
                    }
                }
                '"' -> JsonValue.Str(string(), l, c)
                't' -> word("true", l, c) { JsonValue.Bool(true, l, c) }
                'f' -> word("false", l, c) { JsonValue.Bool(false, l, c) }
                'n' -> word("null", l, c) { JsonValue.Null(l, c) }
                else -> if (ch == '-' || ch.isDigit()) JsonValue.Num(number(), l, c) else fail("unexpected '$ch' (text goes in \"quotes\")")
            }
        }

        fun word(word: String, l: Int, c: Int, make: () -> JsonValue): JsonValue {
            if (!text.startsWith(word, at)) fail("unexpected '${peek()}' (text goes in \"quotes\")")
            repeat(word.length) { next() }
            return make()
        }

        fun obj(l: Int, c: Int): JsonValue {
            next() // {
            val fields = LinkedHashMap<String, JsonValue>()
            while (true) {
                space()
                if (peek() == '}') {
                    next()
                    return JsonValue.Obj(fields, l, c)
                }
                if (peek() != '"') fail("a name in \"quotes\" (or }) should be here")
                val nameLine = line
                val nameColumn = column
                val name = string()
                if (name in fields) throw JsonException("\"$name\" is given twice", nameLine, nameColumn)
                space()
                if (peek() != ':') fail("a : should follow \"$name\"")
                next()
                fields[name] = value()
                space()
                when (peek()) {
                    ',' -> next()
                    '}' -> {}
                    else -> fail("a , or } should be here")
                }
            }
        }

        fun arr(l: Int, c: Int): JsonValue {
            next() // [
            val items = ArrayList<JsonValue>()
            while (true) {
                space()
                if (peek() == ']') {
                    next()
                    return JsonValue.Arr(items, l, c)
                }
                items += value()
                space()
                when (peek()) {
                    ',' -> next()
                    ']' -> {}
                    else -> fail("a , or ] should be here")
                }
            }
        }

        fun string(): String {
            next() // "
            val out = StringBuilder()
            while (true) {
                if (end) fail("the text is not closed with \"")
                when (val c = next()) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (end) fail("the text is not closed with \"")
                        when (val e = next()) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                val hex = (1..4).map { if (end) fail("a \\u needs four hex digits") else next() }.joinToString("")
                                out.append(hex.toIntOrNull(16)?.toChar() ?: fail("\\u$hex is not four hex digits"))
                            }
                            else -> fail("unknown escape \\$e")
                        }
                    }
                    '\n' -> fail("text in quotes cannot go over a line end (write \\n)")
                    else -> out.append(c)
                }
            }
        }

        fun number(): Double {
            val start = at
            if (peek() == '-') next()
            while (!end && (peek().isDigit() || peek() in ".eE+-")) next()
            val text = text.substring(start, at)
            return text.toDoubleOrNull() ?: fail("\"$text\" is not a number")
        }
    }
}
