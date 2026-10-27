package dev.caulis.protocol

import dev.caulis.ffi.Fd
import dev.caulis.ffi.NativeException
import dev.caulis.ffi.UnixSocket
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer

/**
 * Messages waiting to be sent over one socket, with the file descriptors that go with them.
 * Used by both sides of the protocol.
 */
internal class Outgoing(private val socket: UnixSocket, private val arena: Arena) {

    /** Bytes not sent yet. */
    var buffer: ByteBuffer = MessageWriter.newOutput(4096)
        private set

    private val sendBuffer = arena.allocate(SEND_CHUNK.toLong())
    private var sentTotal = 0L // bytes sent since the connection started

    // A queued descriptor and where in the byte stream its message starts.
    private class QueuedFd(val fd: Fd, val messageStart: Long)

    private val fds = ArrayDeque<QueuedFd>()

    val hasPending: Boolean get() = buffer.position() > 0

    /** How many descriptors wait to be sent (each one is open in our process until then). */
    val queuedFds: Int get() = fds.size

    /** Size the buffer would need for [extra] more bytes. */
    fun sizeAfter(extra: Int): Int = buffer.position() + extra

    fun grow(extra: Int): ByteBuffer {
        val needed = buffer.position() + extra
        var capacity = buffer.capacity()
        while (capacity < needed) capacity *= 2
        val bigger = MessageWriter.newOutput(capacity)
        buffer.flip()
        bigger.put(buffer)
        buffer = bigger
        return bigger
    }

    /** The receiver gets its own copy of [fd]; a duplicate is kept until it is sent. */
    fun queueFd(fd: Fd, messageStart: Int) {
        fds += QueuedFd(fd.duplicate(), sentTotal + messageStart)
    }

    /** Drops (and closes) the descriptors of the message starting at [messageStart]. */
    fun cancelFds(messageStart: Int) {
        val at = sentTotal + messageStart
        fds.removeAll { queued -> (queued.messageStart == at).also { if (it) queued.fd.close() } }
    }

    enum class Result { SENT_ALL, SOCKET_FULL, BROKEN }

    /** Sends as much as the socket takes. */
    fun flush(): Result {
        while (buffer.position() > 0) {
            var length = minOf(buffer.position(), SEND_CHUNK)

            // At most MAX_FDS descriptors fit in one send. A message's descriptors must
            // arrive no later than its bytes, so stop before the message of the first
            // descriptor that has to wait for the next send.
            val fdsNow = fds.take(UnixSocket.MAX_FDS)
            if (fds.size > UnixSocket.MAX_FDS) {
                val nextFdMessage = fds.elementAt(UnixSocket.MAX_FDS).messageStart - sentTotal
                length = minOf(length.toLong(), nextFdMessage).toInt()
            }

            MemorySegment.copy(MemorySegment.ofArray(buffer.array()), 0, sendBuffer, 0, length.toLong())
            val sent = try {
                socket.send(sendBuffer.asSlice(0, length.toLong()), fdsNow.map { it.fd })
            } catch (e: NativeException) {
                return Result.BROKEN
            }
            if (sent < 0) return Result.SOCKET_FULL

            repeat(fdsNow.size) { fds.removeFirst().fd.close() }
            // Drop the sent bytes from the front of the buffer.
            buffer.flip()
            buffer.position(sent.toInt())
            buffer.compact()
            sentTotal += sent
        }
        return Result.SENT_ALL
    }

    fun close() {
        fds.forEach { it.fd.close() }
        fds.clear()
    }

    private companion object {
        const val SEND_CHUNK = 65536
    }
}
