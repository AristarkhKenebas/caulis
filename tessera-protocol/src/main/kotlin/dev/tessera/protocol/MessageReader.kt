package dev.tessera.protocol

import dev.tessera.ffi.Fd
import dev.tessera.protocol.wayland.WlDisplay
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reads the arguments of one request a client sent.
 *
 * Every client is untrusted, so every read is checked: lengths, terminating zeros,
 * object ids, file descriptors. Any problem throws [ProtocolException], which
 * disconnects that client and nothing else.
 *
 * Generated code calls these functions in the order the arguments appear in the XML.
 */
class MessageReader internal constructor(private val client: Client) {

    private var data: MemorySegment = MemorySegment.NULL
    private var position = 0L

    // Set before each message is dispatched.
    internal fun reset(arguments: MemorySegment) {
        data = arguments
        position = 0
    }

    private fun fail(message: String): Nothing =
        throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, message)

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
        // replaced instead of disconnecting the app.
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(java.nio.ByteBuffer.wrap(bytes))
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

    /** A file descriptor (`fd`). The caller owns it and must close it. */
    fun fd(): Fd = client.takeReceivedFd() ?: fail("a file descriptor was expected but none was sent")

    /** The id of a new object the client wants created (`new_id`). */
    fun newId(): Int {
        val id = int()
        client.checkNewId(id)
        return id
    }

    /** An existing object of type [T] that may not be null. */
    fun <T : Resource> obj(type: Class<T>): T = readObject(type, nullable = false)!!

    /** An existing object of type [T] that may be null (`allow-null="true"`). */
    fun <T : Resource> objOrNull(type: Class<T>): T? = readObject(type, nullable = true)

    // Objects of the wrong type, unknown ids and destroyed objects are errors.
    private fun <T : Resource> readObject(type: Class<T>, nullable: Boolean): T? {
        val id = int()
        if (id == 0) {
            if (nullable) return null
            fail("null object where an object is required")
        }
        val resource = client.findResource(id)
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_OBJECT, "unknown object $id")
        if (!type.isInstance(resource)) {
            throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_OBJECT, "object $id is a ${resource.type.name}")
        }
        return type.cast(resource)
    }

    /** Called after the last argument: extra bytes mean the client and compositor disagree. */
    fun end() {
        if (position != data.byteSize()) fail("message is longer than its arguments")
    }
}
