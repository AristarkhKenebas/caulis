package dev.caulis.xwayland

import dev.caulis.core.DataDeviceManager
import dev.caulis.core.Desktop
import dev.caulis.core.Surface
import dev.caulis.event.EventLoop
import dev.caulis.ffi.ChildProcess
import dev.caulis.ffi.Errno
import dev.caulis.ffi.Fd
import dev.caulis.ffi.NativeException
import dev.caulis.ffi.UnixListener
import dev.caulis.ffi.UnixSocket
import dev.caulis.log.Logger
import dev.caulis.protocol.Client
import dev.caulis.protocol.Display
import dev.caulis.protocol.xwaylandshellv1.XwaylandShellV1
import dev.caulis.protocol.xwaylandshellv1.XwaylandSurfaceV1
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_BYTE

/**
 * One Xwayland: started when the first program connects to [listener], stopped by itself
 * some time after the last one left, started again with the next. Its programs work in
 * X pixels of [pixelScale] per desktop unit — the screen's real pixels, or a whole number
 * of them for programs that know no other scale.
 */
internal class XServer(
    /** For the log: "X11", "X11 at scale 2", ... */
    val name: String,
    private val displayName: String,
    private val display: Display,
    private val desktop: Desktop,
    private val loop: EventLoop,
    private val clipboard: DataDeviceManager?,
    /** Where its programs connect: the public display, or a private socket behind the router. */
    val listener: UnixListener,
    val pixelScale: () -> Double,
    private val extraArguments: () -> List<String>,
    /** Called when Xwayland stopped (all its programs left, or it failed). */
    private val onStopped: (XServer) -> Unit = {},
) : AutoCloseable {

    private val log = Logger("xwayland")

    private var process: ChildProcess? = null
    private var ready: UnixSocket? = null
    private var wmSocket: UnixSocket? = null
    private var output: Fd? = null
    private val readyLine = StringBuilder()

    /** Xwayland's Wayland connection, while it runs. */
    var client: Client? = null
        private set

    /** The window manager, while Xwayland runs. */
    var xwm: Xwm? = null
        private set

    /** True while Xwayland runs. */
    val running: Boolean get() = process != null

    // Surfaces Xwayland made, by the serial it gives the X window too (xwayland-shell).
    private val serials = HashMap<Long, Surface>()

    init {
        watchForFirstProgram()
    }

    /** xwayland-shell: Xwayland ties [id] (and so [target]) to an X window by a serial. */
    fun shellSurface(shell: XwaylandShellV1, id: XwaylandSurfaceV1, target: Surface) {
        if (!target.assignRole(Surface.Role.XWAYLAND, shell, XwaylandShellV1.Error.ROLE)) return
        var associated = false
        id.requests = object : XwaylandSurfaceV1.Requests {
            override fun setSerial(resource: XwaylandSurfaceV1, serialLo: Int, serialHi: Int) {
                if (associated) {
                    resource.postError(XwaylandSurfaceV1.Error.ALREADY_ASSOCIATED, "the surface already has a serial")
                    return
                }
                val serial = (serialLo.toLong() and 0xffffffffL) or (serialHi.toLong() shl 32)
                if (serial == 0L) {
                    resource.postError(XwaylandSurfaceV1.Error.INVALID_SERIAL, "serial 0")
                    return
                }
                associated = true
                serials[serial] = target
                target.resource.onDestroy { serials.remove(serial) }
                xwm?.surfaceAssociated(serial, target)
            }

            override fun destroy(resource: XwaylandSurfaceV1) {}
        }
    }

    private fun watchForFirstProgram() {
        loop.add(listener.fd) {
            loop.remove(listener.fd)
            start()
        }
    }

    // Starts Xwayland, handing it our sockets: Wayland (3), the window manager's (4), where
    // its programs connect (5, where the first one waits) and "I am ready" (6); what it prints
    // (2) comes into our log.
    private fun start() {
        val (waylandOurs, waylandTheirs) = UnixSocket.pair()
        val (wmOurs, wmTheirs) = UnixSocket.pair()
        val (readyOurs, readyTheirs) = UnixSocket.pair()
        val (outputOurs, outputTheirs) = Fd.pipe()
        val environment = System.getenv().toMutableMap()
        environment["WAYLAND_SOCKET"] = "3"
        environment.remove("DISPLAY")
        val started = try {
            ChildProcess.start(
                "Xwayland",
                listOf(displayName, "-rootless", "-core", "-listenfd", "5", "-wm", "4", "-displayfd", "6") + extraArguments(),
                environment,
                mapOf(2 to outputTheirs, 3 to waylandTheirs.fd, 4 to wmTheirs.fd, 5 to listener.fd, 6 to readyTheirs.fd),
            )
        } catch (e: Exception) {
            log.error("Xwayland could not be started (is it installed? the package is usually called xwayland or xorg-xwayland)", e)
            listOf(waylandOurs, wmOurs, readyOurs).forEach { it.close() }
            outputOurs.close()
            watchForFirstProgram()
            return
        } finally {
            waylandTheirs.close()
            wmTheirs.close()
            readyTheirs.close()
            outputTheirs.close()
        }
        readOutput(outputOurs)
        process = started
        client = display.addClient(waylandOurs, "Xwayland[${started.pid}]")
        wmSocket = wmOurs
        ready = readyOurs
        log.info { "Starting $name (Xwayland, process ${started.pid})" }
        loop.add(readyOurs.fd) { readyReadable() }
        loop.add(started.ended) { stopped() }
    }

    // Xwayland's messages, line by line: errors as warnings, the rest at level 3.
    private fun readOutput(fd: Fd) {
        output = fd
        fd.setNonBlocking()
        val line = java.io.ByteArrayOutputStream()
        val buffer = Arena.ofAuto().allocate(4096)
        fun flushLine() {
            val text = line.toString(Charsets.UTF_8).trimEnd()
            line.reset()
            val message = text.removePrefix("(EE)").trim()
            if (message.isEmpty()) return
            if (text.startsWith("(EE)") || text.startsWith("Fatal")) log.warn("$name: $message") else log.trace { "$name: $text" }
        }
        loop.add(fd) {
            while (true) {
                val n = try {
                    fd.read(buffer)
                } catch (e: NativeException) {
                    if (e.errno == Errno.EINTR) continue
                    break // EAGAIN: the rest comes later
                }
                if (n == 0L) {
                    flushLine()
                    loop.remove(fd)
                    fd.close()
                    if (output === fd) output = null
                    break
                }
                for (i in 0 until n) {
                    val byte = buffer.get(JAVA_BYTE, i)
                    if (byte == '\n'.code.toByte()) flushLine() else line.write(byte.toInt())
                }
            }
        }
    }

    // Xwayland writes its display number (in two writes: the socket must stay open until the
    // line is complete, or the second fails and Xwayland quits). Then it accepts programs and
    // the window manager can start. Closed without a word: it did not start.
    private fun readyReadable() {
        val socket = ready ?: return
        val chunk = Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(64)
            val n = try {
                socket.fd.read(buffer)
            } catch (e: NativeException) {
                if (e.errno == Errno.EAGAIN || e.errno == Errno.EINTR) return
                -1L
            }
            if (n <= 0) null else buffer.asSlice(0, n).toArray(JAVA_BYTE).decodeToString()
        }
        if (chunk == null) {
            loop.remove(socket.fd)
            socket.close()
            ready = null
            log.warn("$name did not start (see its messages above); the next X11 program tries again")
            return
        }
        readyLine.append(chunk)
        if ('\n' in readyLine) becameReady()
    }

    private fun becameReady() {
        ready?.let { socket ->
            loop.remove(socket.fd)
            socket.close()
        }
        ready = null
        readyLine.clear()
        val socket = wmSocket ?: return
        try {
            xwm = Xwm(XConnection(socket.fd.duplicate().value), desktop, loop, { serials[it] }, clipboard, pixelScale)
            for ((serial, surface) in serials) xwm?.surfaceAssociated(serial, surface)
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            log.error("The X11 window manager of $name could not start", e)
        }
    }

    /** The scale changed: X11 windows follow; programs started from now on get the new DPI. */
    fun rescaled() {
        xwm?.rescaled()
    }

    /** The cursor theme or size changed. */
    fun cursorChanged() {
        xwm?.updateCursor()
        xwm?.tellResources()
    }

    private fun stopped() {
        val ended = process ?: return
        loop.remove(ended.ended)
        val status = ended.reap()
        if (status == 0) log.info { "$name stopped: no X11 programs left (the next one starts it again)" }
        else log.warn("$name stopped (exit status $status); the next X11 program starts it again")
        ended.close()
        process = null
        xwm?.close()
        xwm = null
        wmSocket?.close()
        wmSocket = null
        ready?.let { loop.remove(it.fd); it.close() }
        ready = null
        client?.disconnect()
        client = null
        serials.clear()
        watchForFirstProgram()
        onStopped(this)
    }

    override fun close() {
        process?.let { running ->
            loop.remove(running.ended)
            running.terminate()
            // A moment to quit (so it leaves no zombie behind); it is not waited for longer.
            val deadline = System.nanoTime() + 1_000_000_000
            while (running.reap() == null && System.nanoTime() < deadline) Thread.sleep(5)
            running.close()
        }
        process = null
        ready?.let { loop.remove(it.fd); it.close() }
        ready = null
        output?.let { loop.remove(it); it.close() }
        output = null
        xwm?.close()
        xwm = null
        wmSocket?.close()
        client?.disconnect()
        client = null
        loop.remove(listener.fd)
        listener.close()
    }
}
