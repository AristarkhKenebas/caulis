package dev.tessera.protocol

import dev.tessera.ffi.Fd
import dev.tessera.ffi.NativeException
import dev.tessera.ffi.UnixSocket
import dev.tessera.protocol.wayland.WlDisplay
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.nio.ByteBuffer

/**
 * One connected program.
 *
 * Reads requests from its socket, finds the object each request is for and calls it,
 * and sends events back. If the client breaks the protocol, or the code handling its
 * request fails, only this client is disconnected (with an error message it can show).
 */
class Client internal constructor(
    /** The server this client is connected to. */
    val server: Display,
    internal val socket: UnixSocket,
) {
    /** Process id of the program (from the kernel, cannot be faked). */
    val pid: Int
    val uid: Int

    init {
        val credentials = socket.peerCredentials()
        pid = credentials.pid
        uid = credentials.uid
    }

    // Object id 1 is always the client's wl_display.
    internal val displayObject: WlDisplay = WlDisplay.create(this, 1, 1)

    private val resources = HashMap<Int, Resource>()
    private var nextServerId = FIRST_SERVER_ID

    /** False after the client disconnected or was disconnected. */
    var isConnected = true
        private set

    private var closeAfterFlush = false

    // --- input ------------------------------------------------------------------------

    private val arena = Arena.ofShared()
    private val input = arena.allocate(INPUT_SIZE.toLong())
    private var inputFilled = 0L
    private val receivedFds = ArrayDeque<Fd>()
    private val receivedFdsScratch = ArrayList<Fd>()
    private val reader = MessageReader(this)

    // --- output -----------------------------------------------------------------------

    private var output: ByteBuffer = MessageWriter.newOutput(4096)
    private val writer = MessageWriter(this)
    private val sendBuffer = arena.allocate(SEND_CHUNK.toLong())
    private var sentTotal = 0L // bytes sent since the connection started

    // A queued descriptor and where in the output stream its message starts.
    private class QueuedFd(val fd: Fd, val messageStart: Long)

    private val queuedFds = ArrayDeque<QueuedFd>()

    init {
        resources[1] = displayObject
        displayObject.requests = server.displayRequests
    }

    /** True if events are waiting to be sent. */
    val hasPendingOutput: Boolean get() = output.position() > 0

    /**
     * Reads everything the client sent and handles each complete request.
     * Returns false if the client is gone (closed the connection or was disconnected).
     */
    internal fun dispatch(): Boolean {
        while (isConnected && !closeAfterFlush) {
            val room = input.asSlice(inputFilled)
            val count = try {
                socket.receive(room, receivedFdsScratch)
            } catch (e: NativeException) {
                disconnect()
                return false
            }
            for (fd in receivedFdsScratch) {
                if (receivedFds.size >= MAX_QUEUED_FDS) {
                    fd.close()
                    postError(displayObject, WlDisplay.Error.NO_MEMORY, "too many file descriptors")
                } else {
                    receivedFds += fd
                }
            }
            receivedFdsScratch.clear()

            if (count == -1L) break      // nothing more right now
            if (count == 0L) {           // the client closed the connection
                disconnect()
                return false
            }
            inputFilled += count
            handleMessages()
        }
        return isConnected
    }

    // Handles every complete message in the input buffer, keeps an incomplete tail.
    private fun handleMessages() {
        var offset = 0L
        while (isConnected && !closeAfterFlush && inputFilled - offset >= HEADER_SIZE) {
            val objectId = input.get(JAVA_INT_UNALIGNED, offset)
            val sizeAndOpcode = input.get(JAVA_INT_UNALIGNED, offset + 4)
            val size = (sizeAndOpcode ushr 16).toLong()
            val opcode = sizeAndOpcode and 0xFFFF
            if (size < HEADER_SIZE || size % 4 != 0L) {
                postError(displayObject, WlDisplay.Error.INVALID_METHOD, "malformed message size $size")
                return
            }
            if (inputFilled - offset < size) break // the rest of this message has not arrived yet

            handleMessage(objectId, opcode, input.asSlice(offset + HEADER_SIZE, size - HEADER_SIZE))
            offset += size
        }
        if (!isConnected) return // the buffer is already freed
        // Move the unfinished tail to the start of the buffer.
        if (offset > 0) {
            MemorySegment.copy(input, offset, input, 0, inputFilled - offset)
            inputFilled -= offset
        }
    }

    private fun handleMessage(objectId: Int, opcode: Int, arguments: MemorySegment) {
        val target = resources[objectId]
        if (target == null) {
            // The client may still talk to an object it destroyed a moment ago; libwayland
            // ignores such messages too. A never-used id is an error.
            if (objectId != 0 && objectId in recentlyDestroyed) return
            postError(displayObject, WlDisplay.Error.INVALID_OBJECT, "unknown object $objectId")
            return
        }
        try {
            reader.reset(arguments)
            target.dispatch(opcode, reader)
        } catch (e: ProtocolException) {
            postError(e.resource, e.code, e.message ?: "protocol error")
        } catch (e: Exception) {
            // A bug in the compositor (or in user code) while handling this request.
            // Only this client is affected.
            server.log.error("Error while handling ${target.type.name} request $opcode from client $pid", e)
            postError(displayObject, WlDisplay.Error.IMPLEMENTATION, "compositor error: ${e.message}")
        }
    }

    // --- objects ----------------------------------------------------------------------

    private val recentlyDestroyed = HashSet<Int>()

    internal fun findResource(id: Int): Resource? = resources[id]?.takeIf { it.isAlive }

    /** Checks an id the client chose for a new object. */
    internal fun checkNewId(id: Int) {
        if (id == 0 || isServerId(id)) {
            throw ProtocolException(displayObject, WlDisplay.Error.INVALID_OBJECT, "invalid new id $id")
        }
        if (resources.containsKey(id)) {
            throw ProtocolException(displayObject, WlDisplay.Error.INVALID_OBJECT, "id $id is already used")
        }
        if (resources.size >= MAX_OBJECTS) {
            throw ProtocolException(displayObject, WlDisplay.Error.NO_MEMORY, "too many objects")
        }
    }

    /** Registers an object created on the client's request (generated code calls this). */
    internal fun <T : Resource> register(resource: T): T {
        recentlyDestroyed.remove(resource.id)
        resources[resource.id] = resource
        return resource
    }

    /** Creates an object the compositor itself introduces to the client (e.g. a data offer). */
    fun <T : Resource> createServerResource(type: ResourceType<T>, version: Int): T {
        val id = nextServerId++
        return register(type.create(this, id, version))
    }

    internal fun destroyResource(resource: Resource) {
        if (!resource.isAlive) return
        resources.remove(resource.id)
        resource.markDestroyed()
        // Tell the client the id may be reused (only for ids the client chose).
        if (!isServerId(resource.id) && isConnected) {
            recentlyDestroyed += resource.id
            displayObject.sendDeleteId(resource.id)
        }
    }

    internal fun takeReceivedFd(): Fd? = receivedFds.removeFirstOrNull()

    // --- errors and disconnecting -----------------------------------------------------

    /** Sends a protocol error about [resource] and disconnects the client after it is sent. */
    fun postError(resource: Resource, code: Int, message: String) {
        if (!isConnected || closeAfterFlush) return
        server.log.warn("Client $pid: ${resource.type.name}@${resource.id} error $code: $message")
        displayObject.sendError(resource, code, message)
        closeAfterFlush = true
        flush()
        disconnect()
    }

    /** Closes the connection and destroys every object of this client. */
    fun disconnect() {
        if (!isConnected) return
        isConnected = false
        for (resource in resources.values.toList()) resource.markDestroyed()
        resources.clear()
        receivedFds.forEach { it.close() }
        receivedFds.clear()
        queuedFds.forEach { it.fd.close() }
        queuedFds.clear()
        server.clientDisconnected(this)
        socket.close()
        arena.close()
    }

    // --- events -----------------------------------------------------------------------

    internal fun beginEvent(resource: Resource, opcode: Int): MessageWriter =
        writer.begin(output, resource.id, opcode)

    internal fun eventWritten() {
        server.clientHasOutput(this)
    }

    internal fun growOutput(extra: Int): ByteBuffer {
        val needed = output.position() + extra
        if (needed > MAX_OUTPUT) {
            // The client does not read its events. Keeping everything would let one
            // stuck program use all memory, so it is disconnected instead.
            closeAfterFlush = true
            server.log.warn("Client $pid does not read its events, disconnecting")
            server.disconnectLater(this)
        }
        var capacity = output.capacity()
        while (capacity < needed) capacity *= 2
        val bigger = MessageWriter.newOutput(capacity)
        output.flip()
        bigger.put(output)
        output = bigger
        return bigger
    }

    internal fun queueFd(fd: Fd, messageStart: Int) {
        // The client gets its own copy; we keep a duplicate until it is sent.
        queuedFds += QueuedFd(fd.duplicate(), sentTotal + messageStart)
    }

    /**
     * Sends queued events. Returns true when everything was sent, false if the socket
     * is full (then the server waits until it is writable and calls this again).
     */
    internal fun flush(): Boolean {
        if (!isConnected) return true
        while (output.position() > 0) {
            val available = output.position()
            var length = minOf(available, SEND_CHUNK)

            // At most MAX_FDS descriptors fit in one send. Never send a message's bytes
            // before its descriptors, so stop before the message of the first one left out.
            val fdsNow = queuedFds.take(UnixSocket.MAX_FDS)
            if (queuedFds.size > UnixSocket.MAX_FDS) {
                val nextFdMessage = queuedFds.elementAt(UnixSocket.MAX_FDS).messageStart - sentTotal
                length = minOf(length.toLong(), nextFdMessage).toInt()
            }

            MemorySegment.copy(MemorySegment.ofArray(output.array()), 0, sendBuffer, 0, length.toLong())
            val sent = try {
                socket.send(sendBuffer.asSlice(0, length.toLong()), fdsNow.map { it.fd })
            } catch (e: NativeException) {
                disconnect()
                return true
            }
            if (sent < 0) return false // socket full, try again when writable

            repeat(fdsNow.size) { queuedFds.removeFirst().fd.close() }
            // Drop the sent bytes from the front of the buffer.
            output.flip()
            output.position(sent.toInt())
            output.compact()
            sentTotal += sent
        }
        return true
    }

    internal val wantsClose: Boolean get() = closeAfterFlush

    companion object {
        /** Ids from here up are chosen by the compositor, below by the client. */
        const val FIRST_SERVER_ID: Int = 0xFF000000.toInt()

        /**
         * True for ids the compositor chose. Ids are unsigned in the protocol, but Kotlin's
         * Int is signed (0xFF000000 is negative), so they must be compared as unsigned.
         */
        fun isServerId(id: Int): Boolean = Integer.compareUnsigned(id, FIRST_SERVER_ID) >= 0

        private const val HEADER_SIZE = 8L
        private const val INPUT_SIZE = 2 * 65536    // always room for the largest message
        private const val SEND_CHUNK = 65536
        private const val MAX_OUTPUT = 4 * 1024 * 1024
        private const val MAX_OBJECTS = 100_000
        private const val MAX_QUEUED_FDS = 256
    }
}
