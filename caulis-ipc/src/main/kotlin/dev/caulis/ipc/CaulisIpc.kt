package dev.caulis.ipc

import dev.caulis.ipc.v1.Event
import dev.caulis.ipc.v1.EventKind
import dev.caulis.ipc.v1.Message
import dev.caulis.ipc.v1.Reply
import dev.caulis.ipc.v1.Request
import dev.caulis.ipc.v1.ServerInfo
import dev.caulis.ipc.v1.State
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * A connection to a running compositor's IPC socket (see caulis.proto), for programs on the
 * JVM — a bar, a tool, a test:
 *
 * ```
 * CaulisIpc.connect().use { ipc ->
 *     ipc.run("workspace 2")
 *     println(ipc.state().windowsList.map { it.title })
 *     ipc.subscribe(EventKind.EVENT_KIND_FOCUS)
 *     while (true) println(ipc.nextEvent() ?: break)
 * }
 * ```
 *
 * Calls wait for their reply. Not for several threads at once.
 */
class CaulisIpc private constructor(private val channel: SocketChannel) : AutoCloseable {

    private val input = BufferedInputStream(Channels.newInputStream(channel))
    private val output = BufferedOutputStream(Channels.newOutputStream(channel))
    private var nextId = 1

    // Events that came while waiting for a reply.
    private val waitingEvents = ArrayDeque<Event>()

    /** Sends [request] (its id is set here) and returns the reply to it. */
    fun request(request: Request.Builder): Reply {
        val id = nextId++
        request.setId(id).build().writeDelimitedTo(output)
        output.flush()
        while (true) {
            val message = Message.parseDelimitedFrom(input) ?: throw IOException("The compositor closed the connection")
            when {
                message.hasEvent() -> waitingEvents.addLast(message.event)
                message.reply.id == id -> return message.reply
            }
        }
    }

    /** Who is there. */
    fun hello(): ServerInfo = request(Request.newBuilder().setHello(dev.caulis.ipc.v1.Hello.getDefaultInstance())).orFail().hello

    /**
     * Runs [action] as in config.json's "bind" ("close", "exec firefox", "workspace 3", ...),
     * on [window] (Window.id; 0: the focused one). Throws [IpcException] when it cannot.
     */
    @JvmOverloads
    fun run(action: String, window: Long = 0) {
        request(Request.newBuilder().setRun(dev.caulis.ipc.v1.Run.newBuilder().setAction(action).setWindow(window))).orFail()
    }

    /** What is on screen now. */
    fun state(): State = request(Request.newBuilder().setGetState(dev.caulis.ipc.v1.GetState.getDefaultInstance())).orFail().state

    /** What is wrong with the config.json [text] (empty: nothing). */
    fun checkConfig(text: String): List<String> =
        request(Request.newBuilder().setCheckConfig(dev.caulis.ipc.v1.CheckConfig.newBuilder().setText(text))).orFail().problems.problemsList

    /** The input devices (their names are what device settings match). */
    fun devices(): List<dev.caulis.ipc.v1.Device> =
        request(Request.newBuilder().setGetDevices(dev.caulis.ipc.v1.GetDevices.getDefaultInstance())).orFail().devices.devicesList

    /**
     * Runs [code] in the configuration's [language] ("lua": in config.lua's own state) and
     * returns what it returned, as text. Throws [IpcException] with the mistake.
     */
    @JvmOverloads
    fun eval(code: String, language: String = "lua"): String =
        request(Request.newBuilder().setEval(dev.caulis.ipc.v1.Eval.newBuilder().setLanguage(language).setCode(code))).orFail().output

    /** Events of these [kinds] from now on (none: all); read them with [nextEvent]. */
    fun subscribe(vararg kinds: EventKind) {
        request(Request.newBuilder().setSubscribe(dev.caulis.ipc.v1.Subscribe.newBuilder().addAllKinds(kinds.toList()))).orFail()
    }

    /** The next event (waits for it); null when the compositor went away. */
    fun nextEvent(): Event? {
        waitingEvents.removeFirstOrNull()?.let { return it }
        while (true) {
            val message = try {
                Message.parseDelimitedFrom(input)
            } catch (e: IOException) {
                null
            } ?: return null
            if (message.hasEvent()) return message.event
        }
    }

    override fun close() = channel.close()

    private fun Reply.orFail(): Reply = if (hasError()) throw IpcException(error) else this

    companion object {
        /** The environment variable with the socket's path (set for programs the compositor starts). */
        const val SOCKET_VARIABLE = "CAULIS_SOCKET"

        /** Connects to [socket] (by default: see [findSocket]). */
        @JvmStatic
        @JvmOverloads
        fun connect(socket: Path = findSocket()): CaulisIpc {
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                channel.connect(UnixDomainSocketAddress.of(socket))
            } catch (e: IOException) {
                channel.close()
                throw IOException("Cannot connect to $socket: ${e.message}", e)
            }
            return CaulisIpc(channel)
        }

        /**
         * The socket of the compositor this program runs in: `$CAULIS_SOCKET`, else the one
         * for `$WAYLAND_DISPLAY` in `$XDG_RUNTIME_DIR`, else the only one there — of the
         * compositor called [name] (its configName), when given.
         */
        @JvmStatic
        @JvmOverloads
        fun findSocket(environment: Map<String, String> = System.getenv(), name: String? = null): Path {
            environment[SOCKET_VARIABLE]?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
            val runtime = environment["XDG_RUNTIME_DIR"]?.takeIf { it.startsWith("/") }?.let { Path.of(it) }
                ?: throw IOException("Neither $SOCKET_VARIABLE nor XDG_RUNTIME_DIR is set: where is the compositor?")
            val all = Files.list(runtime).use { files -> files.filter { it.fileName.toString().endsWith(".sock") && Files.isRegularFile(it).not() }.toList() }
                .filter { it.fileName.toString().matches(Regex(".+-wayland-\\d+\\.sock")) }
                .filter { name == null || it.fileName.toString().startsWith("$name-wayland-") }
            val display = environment["WAYLAND_DISPLAY"]?.takeIf { it.isNotBlank() && '/' !in it }
            display?.let { d -> all.firstOrNull { it.fileName.toString().endsWith("-$d.sock") }?.let { return it } }
            return all.singleOrNull()
                ?: throw IOException(if (all.isEmpty()) "No compositor socket in $runtime" else "Several compositors run (${all.joinToString { it.fileName.toString() }}): set $SOCKET_VARIABLE")
        }
    }
}

/** The compositor could not do what was asked; [message] says why. */
class IpcException(message: String) : Exception(message)
