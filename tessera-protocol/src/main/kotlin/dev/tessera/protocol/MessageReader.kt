package dev.tessera.protocol

import dev.tessera.ffi.Fd
import dev.tessera.protocol.wayland.WlDisplay

/**
 * Reads the arguments of one request a client sent.
 *
 * Every client is untrusted, so every read is checked: lengths, terminating zeros,
 * object ids, file descriptors. Any problem throws [ProtocolException], which
 * disconnects that client and nothing else.
 *
 * Generated code calls these functions in the order the arguments appear in the XML.
 */
class MessageReader internal constructor(private val client: Client) : WireReader() {

    override fun fail(message: String): Nothing =
        throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, message)

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
}
