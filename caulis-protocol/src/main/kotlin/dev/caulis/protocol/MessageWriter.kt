package dev.caulis.protocol

import dev.caulis.ffi.Fd
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes one message: an event from the compositor to a client, or a request from our
 * client side to another compositor. Generated code uses it:
 * `event(OPCODE).int(x).string(title).end()`.
 *
 * Messages are collected in the connection's output buffer and sent in batches.
 */
class MessageWriter internal constructor(private val client: WireOutput) {

    private var buffer: ByteBuffer = ByteBuffer.allocate(0)
    private var start = 0
    private var messageId = 0
    private var messageOpcode = 0
    private var active = false

    // Starts a message: the 8-byte header is written at the end, once the size is known.
    internal fun begin(output: ByteBuffer, objectId: Int, opcode: Int): MessageWriter {
        // One left half-written (its sender failed in the middle) is taken back first: a
        // broken message never reaches the other side, nor blocks the ones after it.
        if (active) abandon()
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

    fun obj(value: dev.caulis.protocol.client.Proxy?): MessageWriter = int(value?.id ?: 0)

    fun newId(value: dev.caulis.protocol.client.Proxy): MessageWriter = int(value.id)

    /** Sends a file descriptor with this event; the client gets its own copy, [fd] stays yours. */
    fun fd(fd: Fd): MessageWriter {
        client.queueFd(fd, messageStart = start)
        return this
    }

    // Takes back the message being written: its bytes and its descriptors.
    private fun abandon() {
        buffer.position(start)
        active = false
        client.cancelMessage(start)
    }

    /** Finishes the event: writes its header and hands it to the client's queue. */
    fun end() {
        val size = buffer.position() - start
        if (size > MAX_MESSAGE_SIZE) {
            abandon()
            throw IllegalArgumentException("Event is too large: $size bytes (it was not sent)")
        }
        buffer.putInt(start, messageId)
        buffer.putInt(start + 4, (size shl 16) or messageOpcode)
        active = false
        client.messageWritten()
    }

    companion object {
        /** The header stores the size in 16 bits. */
        const val MAX_MESSAGE_SIZE = 0xFFFF

        internal fun newOutput(capacity: Int): ByteBuffer =
            ByteBuffer.allocate(capacity).order(ByteOrder.nativeOrder())
    }
}

/** Where a [MessageWriter] puts its bytes: a server-side [Client] or a client-side connection. */
internal interface WireOutput {
    /** Makes room for [extra] more bytes and returns the (possibly new) buffer. */
    fun growOutput(extra: Int): ByteBuffer

    /** Remembers a descriptor to send with the message starting at [messageStart]. */
    fun queueFd(fd: Fd, messageStart: Int)

    /** The message starting at [messageStart] is taken back: its descriptors are not sent. */
    fun cancelMessage(messageStart: Int)

    fun messageWritten()
}
