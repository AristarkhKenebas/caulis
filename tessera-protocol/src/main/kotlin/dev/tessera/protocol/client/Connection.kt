package dev.tessera.protocol.client

import dev.tessera.event.EventLoop
import dev.tessera.ffi.Epoll
import dev.tessera.ffi.Fd
import dev.tessera.ffi.NativeException
import dev.tessera.ffi.UnixSocket
import dev.tessera.protocol.Incoming
import dev.tessera.protocol.Log
import dev.tessera.protocol.MessageWriter
import dev.tessera.protocol.Outgoing
import dev.tessera.protocol.WireOutput
import dev.tessera.protocol.client.wayland.WlCallback
import dev.tessera.protocol.client.wayland.WlDisplay
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.file.Path

/**
 * A connection to another Wayland compositor, as its client.
 *
 * ```
 * val connection = Connection.connect()          // uses $WAYLAND_DISPLAY
 * val registry = connection.display.getRegistry()
 * registry.events = object : WlRegistry.Events { ... }
 * connection.roundtrip()                         // wait until the globals arrived
 * ```
 */
class Connection private constructor(private val socket: UnixSocket) : WireOutput, AutoCloseable {

    val log = Log("tessera.wayland.client")

    private val arena = Arena.ofShared()
    private val incoming = Incoming(socket, arena)
    private val outgoing = Outgoing(socket, arena)
    private val reader = EventReader(this)
    private val writer = MessageWriter(this)

    private val proxies = HashMap<Int, Proxy>()
    private val freeIds = ArrayDeque<Int>()
    private var nextId = 2

    /** The other compositor's wl_display (object 1). */
    val display: WlDisplay = WlDisplay.create(this, 1, 1)

    /** Set when the other compositor reported a fatal error or the connection broke. */
    var error: String? = null
        private set

    init {
        proxies[1] = display
        display.events = object : WlDisplay.Events {
            override fun error(proxy: WlDisplay, objectId: Proxy?, code: Int, message: String) {
                fail("compositor error on $objectId (code $code): $message")
            }

            override fun deleteId(proxy: WlDisplay, id: Int) {
                proxies.remove(id)?.isAlive = false
                freeIds += id
            }
        }
    }

    // --- objects ----------------------------------------------------------------------

    /** Creates a new object of ours; generated request functions call this for `new_id`. */
    internal fun <T : Proxy> createProxy(type: ProxyType<T>, version: Int): T {
        val id = freeIds.removeFirstOrNull() ?: nextId++
        val proxy = type.create(this, id, version)
        proxies[id] = proxy
        return proxy
    }

    internal fun <T : Proxy> registerServerProxy(type: ProxyType<T>, id: Int, version: Int): T {
        val proxy = type.create(this, id, version)
        proxies[id] = proxy
        return proxy
    }

    /** After a destructor request: the id stays reserved until the compositor confirms (delete_id). */
    internal fun destroyProxy(proxy: Proxy) {
        proxy.isAlive = false
    }

    internal fun findProxy(id: Int): Proxy? = proxies[id]?.takeIf { it.isAlive }

    internal fun takeReceivedFd(): Fd? = incoming.takeFd()

    // --- receiving --------------------------------------------------------------------

    /** Reads and handles everything the compositor sent. Throws [ConnectionException] if broken. */
    fun dispatch() {
        error?.let { throw ConnectionException(it) }
        while (true) {
            val result = try {
                incoming.read()
            } catch (e: NativeException) {
                fail("connection broken: ${e.message}")
            }
            when (result) {
                Incoming.Read.NOTHING_NOW -> return
                Incoming.Read.CLOSED -> fail("the compositor closed the connection")
                Incoming.Read.TOO_MANY_FDS -> fail("the compositor sent too many file descriptors")
                Incoming.Read.GOT_DATA -> {
                    val wellFormed = incoming.forEachMessage { objectId, opcode, arguments ->
                        handleEvent(objectId, opcode, arguments)
                        error == null
                    }
                    if (!wellFormed) fail("malformed message from the compositor")
                    error?.let { throw ConnectionException(it) }
                }
            }
        }
    }

    private fun handleEvent(objectId: Int, opcode: Int, arguments: MemorySegment) {
        val proxy = proxies[objectId] ?: return // destroyed on our side; the event is outdated
        try {
            reader.reset(arguments)
            proxy.dispatchEvent(opcode, reader)
        } catch (e: StaleEvent) {
            // refers to an object we already destroyed: ignore
        }
    }

    private fun fail(message: String): Nothing {
        if (error == null) {
            error = message
            log.error(message)
        }
        throw ConnectionException(message)
    }

    // --- sending ----------------------------------------------------------------------

    internal fun beginRequest(proxy: Proxy, opcode: Int): MessageWriter = writer.begin(outgoing.buffer, proxy.id, opcode)

    override fun growOutput(extra: Int): ByteBuffer = outgoing.grow(extra)

    override fun queueFd(fd: Fd, messageStart: Int) = outgoing.queueFd(fd, messageStart)

    override fun messageWritten() {}

    /** Sends queued requests. Returns false if the socket is full (try again when writable). */
    fun flush(): Boolean = when (outgoing.flush()) {
        Outgoing.Result.SENT_ALL -> true
        Outgoing.Result.SOCKET_FULL -> false
        Outgoing.Result.BROKEN -> fail("connection broken while sending")
    }

    /**
     * Sends everything and waits until the compositor handled it (wl_display.sync).
     * Only for setup: it blocks, so never call it from inside the running event loop.
     */
    fun roundtrip(timeoutMillis: Int = 5000) {
        var done = false
        display.sync().events = object : WlCallback.Events {
            override fun done(proxy: WlCallback, callbackData: Int) {
                done = true
            }
        }
        Epoll().use { epoll ->
            epoll.add(socket.fd)
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (!done) {
                flush()
                val left = (deadline - System.currentTimeMillis()).toInt()
                if (left <= 0) fail("no answer from the compositor within $timeoutMillis ms")
                epoll.wait(left) { _, _ -> }
                dispatch()
            }
        }
    }

    /** Lets [loop] handle this connection: events are dispatched and requests flushed automatically. */
    fun attach(loop: EventLoop) {
        loop.add(socket.fd) { dispatch() }
        loop.beforeWait { flush() }
    }

    val fd: Fd get() = socket.fd

    override fun close() {
        proxies.clear()
        incoming.close()
        outgoing.close()
        socket.close()
        arena.close()
    }

    companion object {
        /** Connects to `$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY` (or [path] if given). */
        fun connect(path: Path? = null): Connection {
            val target = path ?: run {
                val runtime = System.getenv("XDG_RUNTIME_DIR") ?: error("XDG_RUNTIME_DIR is not set")
                val name = System.getenv("WAYLAND_DISPLAY") ?: "wayland-0"
                if (name.startsWith("/")) Path.of(name) else Path.of(runtime, name)
            }
            return Connection(UnixSocket.connect(target))
        }
    }
}
