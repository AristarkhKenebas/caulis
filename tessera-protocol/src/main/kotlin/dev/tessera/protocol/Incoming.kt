package dev.tessera.protocol

import dev.tessera.ffi.Fd
import dev.tessera.ffi.UnixSocket
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED

/**
 * Bytes and file descriptors received over one socket, cut into whole messages.
 * Used by both sides of the protocol.
 */
internal class Incoming(private val socket: UnixSocket, arena: Arena) {

    private val buffer = arena.allocate(SIZE.toLong())
    private var filled = 0L
    private val fds = ArrayDeque<Fd>()
    private val scratch = ArrayList<Fd>()

    enum class Read { GOT_DATA, NOTHING_NOW, CLOSED, TOO_MANY_FDS }

    /** Reads what the socket has (throws NativeException if the connection broke). */
    fun read(): Read {
        val count = socket.receive(buffer.asSlice(filled), scratch)
        var tooMany = false
        for (fd in scratch) {
            if (fds.size >= MAX_FDS) {
                fd.close()
                tooMany = true
            } else {
                fds += fd
            }
        }
        scratch.clear()
        return when {
            tooMany -> Read.TOO_MANY_FDS
            count == -1L -> Read.NOTHING_NOW
            count == 0L -> Read.CLOSED
            else -> {
                filled += count
                Read.GOT_DATA
            }
        }
    }

    /**
     * Calls [handle] for every complete message (object id, opcode, arguments).
     * [handle] returns false to stop (e.g. after a fatal error).
     * Returns false if a message header is malformed.
     */
    fun forEachMessage(handle: (objectId: Int, opcode: Int, arguments: MemorySegment) -> Boolean): Boolean {
        var offset = 0L
        var ok = true
        while (filled - offset >= HEADER_SIZE) {
            val objectId = buffer.get(JAVA_INT_UNALIGNED, offset)
            val sizeAndOpcode = buffer.get(JAVA_INT_UNALIGNED, offset + 4)
            val size = (sizeAndOpcode ushr 16).toLong()
            if (size < HEADER_SIZE || size % 4 != 0L) {
                ok = false
                break
            }
            if (filled - offset < size) break // the rest of this message has not arrived yet
            val keepGoing = handle(objectId, sizeAndOpcode and 0xFFFF, buffer.asSlice(offset + HEADER_SIZE, size - HEADER_SIZE))
            offset += size
            if (!keepGoing) return ok
        }
        // Move the unfinished tail to the start of the buffer.
        if (offset > 0) {
            MemorySegment.copy(buffer, offset, buffer, 0, filled - offset)
            filled -= offset
        }
        return ok
    }

    fun takeFd(): Fd? = fds.removeFirstOrNull()

    fun close() {
        fds.forEach { it.close() }
        fds.clear()
    }

    private companion object {
        const val HEADER_SIZE = 8L
        const val SIZE = 2 * 65536 // always room for the largest message (64 KiB)
        const val MAX_FDS = 256
    }
}
