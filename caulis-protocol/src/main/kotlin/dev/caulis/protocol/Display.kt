package dev.caulis.protocol

import dev.caulis.event.EventLoop
import dev.caulis.ffi.Device
import dev.caulis.ffi.Errno
import dev.caulis.ffi.Fd
import dev.caulis.ffi.FileLock
import dev.caulis.ffi.NativeException
import dev.caulis.ffi.UnixListener
import dev.caulis.ffi.UnixSocket
import dev.caulis.protocol.wayland.WlCallback
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlRegistry
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Wayland server: the socket programs connect to, the connected [Client]s, and the
 * list of [Global]s — the features the compositor offers (wl_compositor, wl_seat, ...).
 *
 * ```
 * val display = Display(loop)
 * val name = display.addSocket()                 // e.g. "wayland-1"
 * display.addGlobal(WlCompositor, 6) { compositor -> compositor.requests = ... }
 * loop.run()
 * ```
 */
class Display(val loop: EventLoop) : AutoCloseable {

    val log = dev.caulis.log.Logger("clients")

    private val sockets = ArrayList<Pair<UnixListener, FileLock>>()
    private val connected = ArrayList<Client>()
    private val globals = LinkedHashMap<Int, Global>()

    // Globals just taken away: clients may still bind them for a moment (they had not heard
    // yet), as libwayland's users do it — never an error for a client's honest race.
    private val leaving = HashMap<Int, Global>()

    /**
     * What parts built on this display keep about it (by a key of their own): it lives and
     * goes with the display — never in a static map that would keep the display alive.
     */
    val attachments: MutableMap<Any, Any> = java.util.concurrent.ConcurrentHashMap()
    private val registries = ArrayList<WlRegistry>()
    private var nextGlobalName = 1

    private val needFlush = LinkedHashSet<Client>()
    private val toDisconnect = ArrayList<Client>()

    /** Connected clients. */
    val clients: List<Client> get() = connected

    private var serial = 0

    /**
     * A new serial number. Events like key presses and configure carry one, so clients
     * can refer to "that click" later (e.g. to start moving a window).
     */
    fun nextSerial(): Int = ++serial

    /**
     * One file descriptor kept in reserve. If the process runs out of descriptors, a
     * waiting program could not be accepted and the socket would stay "ready" forever,
     * spinning the event loop at 100% CPU. Instead the reserve is freed for a moment to
     * accept that program and close it at once (it sees a clean refusal).
     */
    private var reserveFd: Fd? = openReserve()

    // Sockets we stopped watching because even the reserve is gone; resumed when a client leaves.
    private val pausedListeners = ArrayList<UnixListener>()

    init {
        loop.beforeWait { flushClients() }
    }

    /**
     * Opens a socket `wayland-N` in [runtimeDir] (normally `$XDG_RUNTIME_DIR`) and returns
     * its name. Programs started with `WAYLAND_DISPLAY=<name>` connect to it.
     */
    fun addSocket(runtimeDir: Path = defaultRuntimeDir()): String {
        for (number in 0..32) {
            val name = "wayland-$number"
            // The lock file says "taken"; a socket without a lock is left over from a crash.
            val lock = FileLock.tryLock(runtimeDir.resolve("$name.lock")) ?: continue
            val path = runtimeDir.resolve(name)
            Files.deleteIfExists(path)
            val listener = UnixListener.listen(path)
            sockets += listener to lock
            loop.add(listener.fd) { acceptClients(listener) }
            log.info("Listening on $path")
            return name
        }
        error("No free wayland-N socket name in $runtimeDir")
    }

    private fun acceptClients(listener: UnixListener) {
        while (true) {
            val socket = try {
                listener.accept() ?: return
            } catch (e: NativeException) {
                when (e.errno) {
                    Errno.ECONNABORTED -> continue // that program is gone already; take the next one
                    Errno.EMFILE, Errno.ENFILE -> {
                        refuseOneClient(listener)
                        return
                    }
                    else -> {
                        log.warn("Cannot accept a new client: ${e.message}")
                        return
                    }
                }
            }
            serve(Client(this, socket))
        }
    }

    // Starts reading a client's requests (and writing its events when the socket is full).
    private fun serve(client: Client) {
        val socket = client.socket
        connected += client
        log.info { "${client.name} connected (user ${client.uid})" }
        loop.add(socket.fd) { ready ->
            if (ready.readable || ready.hangUp || ready.error) client.dispatch()
            if (client.isConnected && ready.writable && client.flush()) {
                loop.update(socket.fd, read = true, write = false)
            }
        }
    }

    // Out of file descriptors: use the reserve to accept one waiting program and close it.
    private fun refuseOneClient(listener: UnixListener) {
        log.warn("Out of file descriptors: refusing a new client")
        reserveFd?.close()
        reserveFd = null
        try {
            listener.accept()?.close()
        } catch (e: NativeException) {
            // Still none free (the whole system is out): stop listening until a client leaves.
            loop.update(listener.fd, read = false)
            pausedListeners += listener
        }
        reserveFd = openReserve()
    }

    private fun openReserve(): Fd? = try {
        Device.open(Path.of("/dev/null"))
    } catch (e: NativeException) {
        null
    }

    /** Offers a feature to every client, now and in the future. */
    fun <T : Resource> addGlobal(type: ResourceType<T>, version: Int, onBind: (T) -> Unit): Global =
        addGlobal(type, version, { true }, onBind)

    /**
     * Offers a feature only to the clients [visibleTo] accepts (e.g. one only Xwayland may
     * use): the others neither see nor can bind it.
     */
    /**
     * Which clients may use the privileged features — screenshots and screen sharing,
     * clipboard managers, panels and lock screens, arranging monitors, gamma, managing other
     * programs' windows. Every client by default (as most compositors do); decide it before
     * clients connect, e.g. by the program behind a client (Client.pid).
     */
    var privileged: (Client) -> Boolean = { true }

    /** A feature only [privileged] clients see (see there). */
    fun <T : Resource> addPrivilegedGlobal(type: ResourceType<T>, version: Int, onBind: (T) -> Unit): Global =
        addGlobal(type, version, { client -> privileged(client) }, onBind)

    fun <T : Resource> addGlobal(type: ResourceType<T>, version: Int, visibleTo: (Client) -> Boolean, onBind: (T) -> Unit): Global {
        require(version in 1..type.version) {
            "$type: version $version is not supported by the generated code (max ${type.version})"
        }
        val global = Global(nextGlobalName++, type, version, visibleTo) { client, id, boundVersion ->
            onBind(client.register(type.create(client, id, boundVersion)))
        }
        globals[global.name] = global
        for (registry in registries) if (visibleTo(registry.client)) registry.sendGlobal(global.name, type.name, version)
        return global
    }

    /**
     * Serves a client already connected through [socket] (e.g. one end of a socket pair
     * given to a program the compositor starts, such as Xwayland), called [name] in logs
     * (the kernel would name the compositor itself: it made the pair). Returns it.
     */
    fun addClient(socket: UnixSocket, name: String? = null): Client {
        val client = Client(this, socket, name)
        serve(client)
        return client
    }

    /** Stops offering a feature. Clients that already use it keep their objects. */
    fun removeGlobal(global: Global) {
        if (globals.remove(global.name) == null) return
        for (registry in registries) if (global.visibleTo(registry.client)) registry.sendGlobalRemove(global.name)
        leaving[global.name] = global
        loop.after(LEAVING_MILLIS) { leaving.remove(global.name) }
    }

    // --- wl_display and wl_registry, implemented here -------------------------------

    internal val displayRequests = object : WlDisplay.Requests {
        override fun sync(resource: WlDisplay, callback: WlCallback) {
            // Requests are handled in order, so by now all earlier ones are done.
            callback.sendDone(0)
            callback.destroy()
        }

        override fun getRegistry(resource: WlDisplay, registry: WlRegistry) {
            registry.requests = registryRequests
            registries += registry
            registry.onDestroy { registries -= registry }
            for (global in globals.values) {
                if (global.visibleTo(resource.client)) registry.sendGlobal(global.name, global.type.name, global.version)
            }
        }
    }

    private val registryRequests = object : WlRegistry.Requests {
        override fun bind(resource: WlRegistry, name: Int, interfaceName: String, version: Int, id: Int) {
            val global = (globals[name] ?: leaving[name])?.takeIf { it.visibleTo(resource.client) }
            if (global == null || global.type.name != interfaceName) {
                throw ProtocolException(resource.client.displayObject, WlDisplay.Error.INVALID_OBJECT,
                    "no global $name of type $interfaceName")
            }
            if (version < 1 || version > global.version) {
                throw ProtocolException(resource.client.displayObject, WlDisplay.Error.INVALID_OBJECT,
                    "$interfaceName version $version requested, ${global.version} offered")
            }
            global.bind(resource.client, id, version)
        }
    }

    // --- sending events ---------------------------------------------------------------

    internal fun clientHasOutput(client: Client) {
        needFlush += client
    }

    internal fun disconnectLater(client: Client) {
        toDisconnect += client
    }

    internal fun clientDisconnected(client: Client) {
        connected -= client
        needFlush -= client
        loop.remove(client.socket.fd)
        // Descriptors were freed: accept new programs again.
        if (reserveFd == null) reserveFd = openReserve()
        for (listener in pausedListeners) loop.update(listener.fd, read = true)
        pausedListeners.clear()
    }

    // Runs once per loop iteration: send everything queued, all at once.
    private fun flushClients() {
        for (client in toDisconnect) client.disconnect()
        toDisconnect.clear()
        for (client in needFlush.toList()) {
            if (!client.isConnected) continue
            if (!client.flush()) loop.update(client.socket.fd, read = true, write = true)
        }
        needFlush.clear()
    }

    override fun close() {
        for (client in connected.toList()) client.disconnect()
        for ((listener, lock) in sockets) {
            loop.remove(listener.fd)
            listener.close()
            lock.close()
        }
        sockets.clear()
        reserveFd?.close()
    }

    companion object {
        // How long a global taken away can still be bound.
        private const val LEAVING_MILLIS = 5000L

        fun defaultRuntimeDir(): Path = Path.of(
            System.getenv("XDG_RUNTIME_DIR") ?: error("XDG_RUNTIME_DIR is not set"),
        )
    }
}

/** One feature offered to clients (see [Display.addGlobal]). */
class Global internal constructor(
    /** The number clients use to bind it. */
    val name: Int,
    val type: ResourceType<*>,
    val version: Int,
    internal val visibleTo: (Client) -> Boolean,
    internal val bind: (client: Client, id: Int, version: Int) -> Unit,
)
