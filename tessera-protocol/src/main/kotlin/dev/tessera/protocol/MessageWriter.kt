package dev.tessera.protocol

import dev.tessera.ffi.Fd
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes one event to a client. Generated `sendXxx` functions use it:
 * `event(OPCODE).int(x).string(title).end()`.
 *
 * Events are collected in the client's output buffer and sent in batches.
 */
class MessageWriter internal constructor(private val client: Client) {

    private var buffer: ByteBuffer = ByteBuffer.allocate(0)
    private var start = 0
    private var messageId = 0
    private var messageOpcode = 0
    private var active = false

    // Starts a message: the 8-byte header is written at the end, once the size is known.
    internal fun begin(output: ByteBuffer, objectId: Int, opcode: Int): MessageWriter {
        check(!active) { "Previous event was not finished with end()" }
        buffer = output
        start = output.position()
        messageId = objectId
        messageOpcode = opcode
        active = true
        reserve(8)
        buffer.position(start + 8)
        return this
    }

    private fun reserve(bytes: Int) {
        if (buffer.remaining() < bytes) buffer = client.growOutput(bytes)
    }

    fun int(value: Int): MessageWriter {
        reserve(4)
        buffer.putInt(value)
        return this
    }

    fun uint(value: Int): MessageWriter = int(value)

    fun fixed(value: Double): MessageWriter = int(Math.round(value * 256.0).toInt())

    fun string(value: String?): MessageWriter {
        if (value == null) return int(0)
        val bytes = value.toByteArray(Charsets.UTF_8)
        val length = bytes.size + 1 // with the terminating zero
        val padded = (length + 3) and 3.inv()
        reserve(4 + padded)
        buffer.putInt(length)
        buffer.put(bytes)
        repeat(padded - bytes.size) { buffer.put(0) }
        return this
    }

    fun array(value: ByteArray): MessageWriter {
        val padded = (value.size + 3) and 3.inv()
        reserve(4 + padded)
        buffer.putInt(value.size)
        buffer.put(value)
        repeat(padded - value.size) { buffer.put(0) }
        return this
    }

    fun obj(value: Resource?): MessageWriter = int(value?.id ?: 0)

    fun newId(value: Resource): MessageWriter = int(value.id)

    /** Sends a file descriptor with this event; the client gets its own copy, [fd] stays yours. */
    fun fd(fd: Fd): MessageWriter {
        client.queueFd(fd, messageStart = start)
        return this
    }

    /** Finishes the event: writes its header and hands it to the client's queue. */
    fun end() {
        val size = buffer.position() - start
        require(size <= MAX_MESSAGE_SIZE) { "Event is too large: $size bytes" }
        buffer.putInt(start, messageId)
        buffer.putInt(start + 4, (size shl 16) or messageOpcode)
        active = false
        client.eventWritten()
    }

    companion object {
        /** The header stores the size in 16 bits. */
        const val MAX_MESSAGE_SIZE = 0xFFFF

        internal fun newOutput(capacity: Int): ByteBuffer =
            ByteBuffer.allocate(capacity).order(ByteOrder.nativeOrder())
    }
}
