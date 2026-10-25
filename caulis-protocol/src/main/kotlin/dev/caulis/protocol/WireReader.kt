package dev.caulis.protocol

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reads the basic argument types of one Wayland message, with bounds checks.
 * Shared by the server side ([MessageReader], requests from clients) and the client
 * side (events from a compositor we connect to).
 */
abstract class WireReader {

    private var data: MemorySegment = MemorySegment.NULL
    private var position = 0L

    /** Called when the message is broken; must throw. */
    protected abstract fun fail(message: String): Nothing

    internal fun reset(arguments: MemorySegment) {
        data = arguments
        position = 0
    }

    private fun need(bytes: Long) {
        if (position + bytes > data.byteSize()) fail("message is too short")
    }

    /** A signed 32-bit number (`int` in the XML). */
    fun int(): Int {
        need(4)
        val value = data.get(JAVA_INT_UNALIGNED, position)
        position += 4
        return value
    }

    /** An unsigned 32-bit number (`uint`), given as Int; use [Integer.toUnsignedLong] if it can exceed 2^31. */
    fun uint(): Int = int()

    /** A fixed-point number (`fixed`, 24.8 bits), e.g. a pointer position. */
    fun fixed(): Double = int() / 256.0

    /** A string that may not be null. */
    fun string(): String = readString(nullable = false)!!

    /** A string that may be null (`allow-null="true"` in the XML). */
    fun stringOrNull(): String? = readString(nullable = true)

    private fun readString(nullable: Boolean): String? {
        val length = Integer.toUnsignedLong(int())
        if (length == 0L) {
            if (nullable) return null
            fail("null string where a string is required")
        }
        val padded = (length + 3) and 3L.inv()
        need(padded)
        if (data.get(JAVA_BYTE, position + length - 1) != 0.toByte()) fail("string is not terminated")
        val bytes = data.asSlice(position, length - 1).toArray(JAVA_BYTE)
        position += padded
        // Wayland strings are UTF-8. Broken bytes (some apps send them in titles) are
        // replaced instead of disconnecting the app — by "?", one byte: the text never grows,
        // so passed on to another client it still fits in a message.
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith("?")
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    /** A byte array (`array`). */
    fun array(): ByteArray {
        val length = Integer.toUnsignedLong(int())
        val padded = (length + 3) and 3L.inv()
        need(padded)
        val bytes = data.asSlice(position, length).toArray(JAVA_BYTE)
        position += padded
        return bytes
    }

    /** Called after the last argument: extra bytes mean both sides disagree on the message. */
    fun end() {
        if (position != data.byteSize()) fail("message is longer than its arguments")
    }
}
