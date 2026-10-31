package dev.caulis.core.dbus

import dev.caulis.ffi.Fd
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

// Writes values by their D-Bus signature (little-endian), aligned as D-Bus wants.
internal class Writer {
    private val out = ByteArrayOutputStream()
    val size: Int get() = out.size()

    fun bytes(): ByteArray = out.toByteArray()

    fun pad(to: Int) {
        while (out.size() % to != 0) out.write(0)
    }

    fun byte(value: Int) = out.write(value and 0xff)

    fun u32(value: Int) {
        pad(4)
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xff)
    }

    private fun u64(value: Long) {
        pad(8)
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xff).toInt())
    }

    private fun string(value: String) {
        require('\u0000' !in value) { "a D-Bus string cannot hold a zero character" }
        val bytes = value.toByteArray()
        u32(bytes.size)
        out.write(bytes)
        out.write(0)
    }

    private fun signature(value: String) {
        val bytes = value.toByteArray()
        byte(bytes.size)
        out.write(bytes)
        out.write(0)
    }

    fun writeAll(signature: String, values: List<Any?>) {
        val types = splitSignature(signature)
        require(types.size == values.size) { "signature $signature has ${types.size} values, not ${values.size}" }
        types.zip(values).forEach { (type, value) -> write(type, value) }
    }

    fun write(type: String, value: Any?) {
        when (type[0]) {
            'y' -> byte((value as Number).toInt())
            'b' -> u32(if (value as Boolean) 1 else 0)
            'n', 'q' -> {
                pad(2)
                val v = (value as Number).toInt()
                out.write(v and 0xff)
                out.write((v ushr 8) and 0xff)
            }
            'i', 'u' -> u32((value as Number).toInt())
            'h' -> u32((value as Number).toInt()) // (an index into the message's descriptors)
            'x', 't' -> u64((value as Number).toLong())
            'd' -> u64(java.lang.Double.doubleToLongBits((value as Number).toDouble()))
            's' -> string(value as String)
            'o' -> string((value as ObjectPath).value)
            'g' -> signature(value as String)
            'v' -> {
                val variant = value as Variant
                signature(variant.signature)
                write(variant.signature, variant.value)
            }
            'a' -> {
                val element = type.substring(1)
                pad(4)
                val lengthAt = out.size()
                u32(0)
                pad(alignment(element[0]))
                val start = out.size()
                // A dict ("a{sv}") from a Map too.
                val items = if (element[0] == '{' && value is Map<*, *>) value.entries.toList() else value as List<*>
                for (item in items) write(element, item)
                val length = out.size() - start
                val all = out.toByteArray()
                for (i in 0 until 4) all[lengthAt + i] = ((length ushr (8 * i)) and 0xff).toByte()
                out.reset()
                out.write(all)
            }
            '(' -> {
                pad(8)
                val inner = splitSignature(type.substring(1, type.length - 1))
                val items = value as List<*>
                require(items.size == inner.size) { "the struct $type has ${inner.size} values, not ${items.size}" }
                items.zip(inner).forEach { (item, t) -> write(t, item) }
            }
            '{' -> {
                pad(8)
                val (keyType, valueType) = splitSignature(type.substring(1, type.length - 1))
                val (k, v) = when (value) {
                    is Map.Entry<*, *> -> value.key to value.value
                    is Pair<*, *> -> value.first to value.second
                    is List<*> -> value[0] to value[1]
                    else -> error("a dict entry is a Map entry, a Pair or a list of two, not $value")
                }
                write(keyType, k)
                write(valueType, v)
            }
            else -> error("D-Bus type $type is not supported here")
        }
    }
}

// Reads values by their D-Bus signature.
internal class Reader(private val data: ByteBuffer, private val messageFds: List<Fd>) {

    fun position(at: Int) {
        data.position(at)
    }

    fun align(to: Int) {
        val at = data.position()
        val aligned = (at + to - 1) / to * to
        data.position(aligned)
    }

    fun byte(): Int = data.get().toInt() and 0xff

    fun u32(): Int {
        align(4)
        return data.getInt()
    }

    fun readAll(signature: String): List<Any?> = splitSignature(signature).map { read(it) }

    fun read(type: String): Any? = when (type[0]) {
        'y' -> byte()
        'b' -> u32() != 0
        'n' -> { align(2); data.getShort().toInt() }
        'q' -> { align(2); data.getShort().toInt() and 0xffff }
        'i', 'u' -> u32()
        'h' -> messageFds.getOrNull(u32()) // (an index into the message's descriptors)
        'x', 't' -> { align(8); data.getLong() }
        'd' -> { align(8); data.getDouble() }
        's' -> string()
        'o' -> ObjectPath(string())
        'g' -> signature()
        'v' -> {
            val signature = signature()
            Variant(signature, read(signature))
        }
        'a' -> {
            val element = type.substring(1)
            val length = u32()
            align(alignment(element[0]))
            if (element == "y") {
                // Bytes (a picture, a file's contents: megabytes) kept as they came, one byte
                // each — not a boxed number each, made on the compositor's thread.
                val bytes = ByteArray(length)
                data.get(bytes)
                UnsignedBytes(bytes)
            } else {
                val end = data.position() + length
                buildList<Any?> { while (data.position() < end) add(read(element)) }
            }
        }
        '(', '{' -> {
            align(8)
            splitSignature(type.substring(1, type.length - 1)).map { read(it) }
        }
        else -> error("D-Bus type $type is not supported here")
    }

    private fun string(): String {
        val length = u32()
        val bytes = ByteArray(length)
        data.get(bytes)
        data.get() // the zero
        return String(bytes)
    }

    private fun signature(): String {
        val length = byte()
        val bytes = ByteArray(length)
        data.get(bytes)
        data.get()
        return String(bytes)
    }
}

// "sa{sv}(ii)" -> ["s", "a{sv}", "(ii)"].
internal fun splitSignature(signature: String): List<String> {
    val types = ArrayList<String>()
    var i = 0
    fun one(): String {
        val start = i
        when (signature[i]) {
            'a' -> {
                i++
                one()
            }
            '(', '{' -> {
                var depth = 0
                do {
                    when (signature[i]) {
                        '(', '{' -> depth++
                        ')', '}' -> depth--
                    }
                    i++
                } while (depth > 0)
            }
            else -> i++
        }
        return signature.substring(start, i)
    }
    while (i < signature.length) types += one()
    return types
}
