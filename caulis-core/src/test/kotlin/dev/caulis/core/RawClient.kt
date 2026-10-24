package dev.caulis.core

import dev.caulis.ffi.Epoll
import dev.caulis.ffi.Fd
import dev.caulis.ffi.UnixSocket
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * A minimal hand-written Wayland client for tests. It builds messages byte by byte,
 * so tests check the real wire format, not just Caulis talking to itself.
 */
class RawClient(path: Path) : AutoCloseable {

    private val socket = UnixSocket.connect(path)
    private val arena = Arena.ofConfined()
    private val epoll = Epoll().also { it.add(socket.fd) }
    private val incoming = ByteBuffer.allocate(1 shl 20).order(ByteOrder.nativeOrder())

    /** One event from the server. */
    class Event(val objectId: Int, val opcode: Int, val args: ByteBuffer) {
        fun int(): Int = args.int
        fun string(): String {
            val length = args.int
            val bytes = ByteArray(length - 1)
            args.get(bytes)
            args.position(args.position() + 1 + ((4 - length % 4) % 4))
            return String(bytes)
        }
    }

    /** Sends a request: object, opcode, then 32-bit arguments and strings. */
    fun request(objectId: Int, opcode: Int, vararg args: Any, fds: List<Fd> = emptyList()) {
        val body = ByteBuffer.allocate(4096).order(ByteOrder.nativeOrder())
        for (arg in args) {
            when (arg) {
                is Int -> body.putInt(arg)
                is String -> {
                    val bytes = arg.toByteArray()
                    body.putInt(bytes.size + 1)
                    body.put(bytes)
                    repeat(4 - bytes.size % 4) { body.put(0) }
                }
                else -> error("unsupported argument $arg")
            }
        }
        val size = 8 + body.position()
        val message = arena.allocate(size.toLong())
        message.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0, objectId)
        message.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 4, (size shl 16) or opcode)
        MemorySegment.copy(MemorySegment.ofArray(body.array()), 0, message, 8, body.position().toLong())
        check(socket.send(message, fds) == size.toLong()) { "could not send" }
    }

    /** Reads events until [stop] returns true for one, or the server closes the connection. */
    fun readUntil(timeoutMillis: Int = 5000, stop: (Event) -> Boolean): List<Event> {
        val events = ArrayList<Event>()
        val buffer = arena.allocate(65536)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            epoll.wait(100) { _, _ -> }
            val count = socket.receive(buffer, mutableListOf())
            if (count == 0L) return events // connection closed
            if (count > 0) incoming.put(buffer.asSlice(0, count).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))
            incoming.flip()
            while (incoming.remaining() >= 8) {
                val start = incoming.position()
                val objectId = incoming.getInt(start)
                val size = incoming.getInt(start + 4) ushr 16
                val opcode = incoming.getInt(start + 4) and 0xFFFF
                if (incoming.remaining() < size) break
                val args = incoming.slice(start + 8, size - 8).order(ByteOrder.nativeOrder())
                incoming.position(start + size)
                val event = Event(objectId, opcode, args)
                events += event
                if (stop(Event(objectId, opcode, args.duplicate().order(ByteOrder.nativeOrder())))) {
                    incoming.compact()
                    return events
                }
            }
            incoming.compact()
        }
        error("timed out; got ${events.size} events")
    }

    override fun close() {
        epoll.close()
        socket.close()
        arena.close()
    }
}
