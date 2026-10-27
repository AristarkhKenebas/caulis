package dev.caulis.protocol

import dev.caulis.ffi.Fd
import dev.caulis.ffi.NativeException
import dev.caulis.ffi.UnixSocket
import dev.caulis.protocol.wayland.WlDisplay
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
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
    private val givenName: String? = null,
) : WireOutput {
    /** Process id of the program (from the kernel, cannot be faked). */
    val pid: Int
    val uid: Int

    init {
        val credentials = socket.peerCredentials()
        pid = credentials.pid
        uid = credentials.uid
    }

    /** A short name for logs: the program's name and process id, e.g. "foot[4123]". */
    val name: String by lazy { givenName ?: "$program[$pid]" }

    /** Its program's name, as `ps` shows it ("grim", "waybar"), or "client" if unknown. */
    val program: String by lazy {
        try {
            java.nio.file.Files.readString(java.nio.file.Path.of("/proc/$pid/comm")).trim()
        } catch (_: Exception) {
            "client"
        }
    }

    private val arena = Arena.ofShared()
    private val incoming = Incoming(socket, arena)
    private val outgoing = Outgoing(socket, arena)
    private val reader = MessageReader(this)
    private val writer = MessageWriter(this)

    // Object id 1 is always the client's wl_display.
    internal val displayObject: WlDisplay = WlDisplay.create(this, 1, 1)

    private val resources = HashMap<Int, Resource>()
    // Ids the client destroyed lately (it may still talk to them a moment): the oldest are
    // forgotten past a limit, so a client cycling through ids cannot grow this for ever.
    private val recentlyDestroyed = LinkedHashSet<Int>()
    private var nextServerId = FIRST_SERVER_ID

    // Ids of our own objects that are gone, to be used again (else a clipboard manager getting
    // an offer at every copy ran through the id range in a long session).
    private val freeServerIds = ArrayDeque<Int>()

    /** False after the client disconnected or was disconnected. */
    var isConnected = true
        private set

    private var closing = false // an error was sent; nothing more is read

    init {
        resources[1] = displayObject
        displayObject.requests = server.displayRequests
    }

    /** True if events are waiting to be sent. */
    val hasPendingOutput: Boolean get() = outgoing.hasPending

    /**
     * Reads everything the client sent and handles each complete request.
     * Returns false if the client is gone (closed the connection or was disconnected).
     */
    internal fun dispatch(): Boolean {
        while (isConnected && !closing) {
            val result = try {
                incoming.read()
            } catch (e: NativeException) {
                disconnect()
                return false
            }
            when (result) {
                Incoming.Read.NOTHING_NOW -> break
                Incoming.Read.CLOSED -> {
                    disconnect()
                    return false
                }
                Incoming.Read.TOO_MANY_FDS -> {
                    postError(displayObject, WlDisplay.Error.NO_MEMORY, "too many file descriptors")
                    return false
                }
                Incoming.Read.GOT_DATA -> {
                    val wellFormed = incoming.forEachMessage { objectId, opcode, arguments ->
                        handleMessage(objectId, opcode, arguments)
                        isConnected && !closing
                    }
                    if (!wellFormed) postError(displayObject, WlDisplay.Error.INVALID_METHOD, "malformed message header")
                }
            }
        }
        return isConnected
    }

    private fun handleMessage(objectId: Int, opcode: Int, arguments: MemorySegment) {
        val target = resources[objectId]
        if (target == null) {
            // The client may still talk to an object it destroyed a moment ago; libwayland
            // ignores such messages too. A never-used id is an error.
            if (objectId in recentlyDestroyed) return
            postError(displayObject, WlDisplay.Error.INVALID_OBJECT, "unknown object $objectId")
            return
        }
        try {
            reader.reset(arguments)
            target.dispatch(opcode, reader)
            reader.requestHandled()
        } catch (e: ProtocolException) {
            reader.requestFailed()
            postError(e.resource, e.code, e.message ?: "protocol error")
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            reader.requestFailed()
            // A bug in the compositor (or in user code) while handling this request — also
            // an Error such as a stack overflow. Only this client is affected.
            server.log.error("Error while handling ${target.type.name} request $opcode from $name", e)
            postError(displayObject, WlDisplay.Error.IMPLEMENTATION, "compositor error: ${e.message}")
        }
    }

    // --- objects ----------------------------------------------------------------------

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
        if (resources.size >= MAX_OBJECTS && !closing) {
            // It keeps every object we give it (offers it never destroys): it goes.
            closing = true
            server.log.warn("$name keeps too many objects ($MAX_OBJECTS), disconnecting")
            server.disconnectLater(this)
        }
        // (The oldest freed id, and only once many are free: a message the client sent to the
        // old object meanwhile cannot reach a new one.)
        val id = if (freeServerIds.size > REUSE_SERVER_IDS_AFTER) freeServerIds.removeFirst() else nextServerId++
        return register(type.create(this, id, version))
    }

    internal fun destroyResource(resource: Resource) {
        if (!resource.isAlive) return
        resources.remove(resource.id)
        resource.markDestroyed()
        if (isServerId(resource.id)) freeServerIds += resource.id
        // Tell the client the id may be reused (only for ids the client chose).
        if (!isServerId(resource.id) && isConnected) {
            recentlyDestroyed += resource.id
            if (recentlyDestroyed.size > MAX_RECENTLY_DESTROYED) recentlyDestroyed.remove(recentlyDestroyed.first())
            displayObject.sendDeleteId(resource.id)
        }
    }

    internal fun takeReceivedFd(): Fd? = incoming.takeFd()

    // --- errors and disconnecting -----------------------------------------------------

    /** Sends a protocol error about [resource] and disconnects the client. */
    fun postError(resource: Resource, code: Int, message: String) {
        if (!isConnected || closing) return
        server.log.warn("$name: ${resource.type.name}@${resource.id} error $code: $message")
        closing = true
        try {
            displayObject.sendError(resource, code, message)
            outgoing.flush()
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            // (The message could not be written: the client goes without it.)
            server.log.warn("$name: the error could not be sent ($e)")
        } finally {
            disconnect()
        }
    }

    /** Closes the connection and destroys every object of this client. */
    fun disconnect() {
        if (!isConnected) return
        isConnected = false
        server.log.info { "$name left" }
        // Each object on its own: one whose destroy listener fails leaves the others to go too.
        for (resource in resources.values.toList()) {
            try {
                resource.markDestroyed()
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) throw e
                server.log.error("$name: cleaning up ${resource.type.name}@${resource.id} failed", e)
            }
        }
        resources.clear()
        incoming.close()
        outgoing.close()
        server.clientDisconnected(this)
        socket.close()
        arena.close()
    }

    // --- events -----------------------------------------------------------------------

    internal fun beginEvent(resource: Resource, opcode: Int): MessageWriter =
        writer.begin(outgoing.buffer, resource.id, opcode)

    override fun messageWritten() {
        server.clientHasOutput(this)
    }

    override fun growOutput(extra: Int): ByteBuffer {
        if (outgoing.sizeAfter(extra) > MAX_OUTPUT && !closing) {
            // The client does not read its events. Keeping everything would let one
            // stuck program use all memory, so it is disconnected instead.
            closing = true
            server.log.warn("$name does not read its events, disconnecting")
            server.disconnectLater(this)
        }
        return outgoing.grow(extra)
    }

    override fun queueFd(fd: Fd, messageStart: Int) {
        if (outgoing.queuedFds >= MAX_QUEUED_FDS) {
            // The client does not read, and every descriptor waiting for it stays open here:
            // a stuck program could use up all the compositor's descriptors. It goes instead.
            if (!closing) {
                closing = true
                server.log.warn("$name does not take the file descriptors sent to it ($MAX_QUEUED_FDS waiting), disconnecting")
                server.disconnectLater(this)
            }
            return
        }
        outgoing.queueFd(fd, messageStart)
    }

    override fun cancelMessage(messageStart: Int) = outgoing.cancelFds(messageStart)

    /**
     * Sends queued events. Returns true when everything was sent, false if the socket
     * is full (then the server waits until it is writable and calls this again).
     */
    internal fun flush(): Boolean {
        if (!isConnected) return true
        return when (outgoing.flush()) {
            Outgoing.Result.SENT_ALL -> true
            Outgoing.Result.SOCKET_FULL -> false
            Outgoing.Result.BROKEN -> {
                disconnect()
                true
            }
        }
    }

    companion object {
        /** Ids from here up are chosen by the compositor, below by the client. */
        const val FIRST_SERVER_ID: Int = 0xFF000000.toInt()

        private const val MAX_RECENTLY_DESTROYED = 4096

        /**
         * True for ids the compositor chose. Ids are unsigned in the protocol, but Kotlin's
         * Int is signed (0xFF000000 is negative), so they must be compared as unsigned.
         */
        fun isServerId(id: Int): Boolean = Integer.compareUnsigned(id, FIRST_SERVER_ID) >= 0

        private const val MAX_OUTPUT = 4 * 1024 * 1024
        private const val MAX_OBJECTS = 100_000

        // How many of our own ids must be free before the oldest is used again.
        private const val REUSE_SERVER_IDS_AFTER = 4096

        // File descriptors waiting for one client (keymaps, clipboard pipes, ...): far more
        // than a working program ever has waiting, far fewer than the compositor may open.
        private const val MAX_QUEUED_FDS = 1024
    }
}
