package dev.caulis.xwayland

import dev.caulis.core.DataDeviceManager
import dev.caulis.core.Desktop
import dev.caulis.core.Output
import dev.caulis.core.Surface
import dev.caulis.event.EventLoop
import dev.caulis.ffi.ChildProcess
import dev.caulis.ffi.Fd
import dev.caulis.ffi.UnixListener
import dev.caulis.ffi.UnixSocket
import dev.caulis.log.Logger
import dev.caulis.protocol.Client
import dev.caulis.protocol.Display
import dev.caulis.protocol.xwaylandshellv1.XwaylandShellV1
import dev.caulis.protocol.xwaylandshellv1.XwaylandSurfaceV1
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * X11 programs in the compositor. Their display ([displayName], e.g. ":1", what DISPLAY
 * is set to) is open at once, but Xwayland itself is started only when the first X11
 * program connects — no memory or start-up time is spent while none runs. If Xwayland
 * stops, the next X11 program starts it again.
 *
 * Their windows are managed by [Xwm]: they tile, float, get title bars and effects, show
 * in docks and screen sharing like Wayland windows, and copy and paste works between X11
 * and Wayland programs (give the [clipboard]). Scaled screens: X works in the
 * screen's real pixels and programs are told the real DPI (Xft.dpi) and cursor size, so
 * they are as sharp and as big as Wayland programs.
 */
class Xwayland(
    private val display: Display,
    private val desktop: Desktop,
    private val loop: EventLoop,
    /** The clipboard X11 programs share with Wayland programs (none: they only share among themselves). */
    private val clipboard: DataDeviceManager? = null,
    /** Seconds Xwayland keeps running after the last X11 program closed (0: until the compositor stops). */
    private val idleSeconds: Int = 10,
) : AutoCloseable {

    private val log = Logger("xwayland")

    /** The X display X11 programs connect to, e.g. ":1" (set DISPLAY to it). */
    val displayName: String

    private val number: Int
    private val lockFile: Path
    private val listener: UnixListener

    private var process: ChildProcess? = null
    private var client: Client? = null
    private var ready: UnixSocket? = null
    private var wmSocket: UnixSocket? = null

    /** The window manager, while Xwayland runs. */
    var xwm: Xwm? = null
        private set

    // Surfaces Xwayland made, by the serial it gives the X window too (xwayland-shell).
    private val serials = HashMap<Long, Surface>()

    init {
        val (n, lock) = claimDisplay()
        number = n
        lockFile = lock
        displayName = ":$n"
        Files.createDirectories(SOCKET_DIR)
        listener = try {
            UnixListener.listen(SOCKET_DIR.resolve("X$n"))
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(lock) }
            throw e
        }
        watchForFirstProgram()
        // Only Xwayland may tie X windows to surfaces.
        display.addGlobal(XwaylandShellV1, 1, { it === client }) { shell ->
            shell.requests = object : XwaylandShellV1.Requests {
                override fun getXwaylandSurface(resource: XwaylandShellV1, id: XwaylandSurfaceV1, surface: dev.caulis.protocol.wayland.WlSurface) {
                    val target = surface.dataAs<Surface>() ?: return
                    if (!target.assignRole(Surface.Role.XWAYLAND, resource, XwaylandShellV1.Error.ROLE)) return
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

                override fun destroy(resource: XwaylandShellV1) {}
            }
        }
        // Xwayland works in pixels: it hears the monitors' size in pixels.
        Output.reportPixelsTo(display) { it === client }
        log.info { "X11 programs can use DISPLAY=$displayName (Xwayland starts with the first one)" }
    }

    /** True while Xwayland runs. */
    val running: Boolean get() = process != null

    // The first free display number, claimed with a lock file of ours right away (as X servers
    // do): until Xwayland runs, the number is still ours, and nobody else takes it meanwhile.
    // (Given the listening socket, Xwayland leaves the lock file to us.)
    // A lock of a process that is gone is stale; so is a socket nobody listens on.
    private fun claimDisplay(): Pair<Int, Path> {
        for (n in 1..32) {
            val lock = Path.of("/tmp/.X$n-lock")
            val socket = SOCKET_DIR.resolve("X$n")
            if (Files.exists(lock)) {
                val pid = runCatching { Files.readString(lock).trim().toLong() }.getOrNull()
                if (pid != null && ProcessHandle.of(pid).isPresent) continue
                runCatching { Files.delete(lock) }
            } else if (Files.exists(socket) && runCatching { UnixSocket.connect(socket).close() }.isSuccess) {
                continue // someone serves it without a lock
            }
            try {
                Files.writeString(lock, "%10d\n".format(ProcessHandle.current().pid()), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                continue // taken just now
            }
            Files.deleteIfExists(socket)
            return n to lock
        }
        error("no free X display number (1..32)")
    }

    private fun watchForFirstProgram() {
        loop.add(listener.fd) {
            loop.remove(listener.fd)
            start()
        }
    }

    // Starts Xwayland, handing it our sockets: Wayland (3), the window manager's (4), the X
    // display (5, where the first program waits) and "I am ready" (6); what it prints (2)
    // comes into our log.
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
                listOf(displayName, "-rootless", "-core", "-listenfd", "5", "-wm", "4", "-displayfd", "6") + terminateArguments,
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
        log.info { "Starting Xwayland on $displayName (process ${started.pid})" }
        loop.add(readyOurs.fd) { readyReadable() }
        loop.add(started.ended) { stopped() }
    }

    // Xwayland's messages, line by line: errors as warnings, the rest at level 3.
    private var output: Fd? = null

    private fun readOutput(fd: Fd) {
        output = fd
        fd.setNonBlocking()
        val line = java.io.ByteArrayOutputStream()
        val buffer = java.lang.foreign.Arena.ofAuto().allocate(4096)
        fun flushLine() {
            val text = line.toString(Charsets.UTF_8).trimEnd()
            line.reset()
            val message = text.removePrefix("(EE)").trim()
            if (message.isEmpty()) return
            if (text.startsWith("(EE)") || text.startsWith("Fatal")) log.warn("Xwayland: $message") else log.trace { "Xwayland: $text" }
        }
        loop.add(fd) {
            while (true) {
                val n = try {
                    fd.read(buffer)
                } catch (e: dev.caulis.ffi.NativeException) {
                    if (e.errno == dev.caulis.ffi.Errno.EINTR) continue
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
                    val byte = buffer.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i)
                    if (byte == '\n'.code.toByte()) flushLine() else line.write(byte.toInt())
                }
            }
        }
    }

    // Xwayland writes its display number (in two writes: the socket must stay open until the
    // line is complete, or the second fails and Xwayland quits). Then it accepts programs and
    // the window manager can start. Closed without a word: it did not start.
    private val readyLine = StringBuilder()

    private fun readyReadable() {
        val socket = ready ?: return
        val chunk = java.lang.foreign.Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(64)
            val n = try {
                socket.fd.read(buffer)
            } catch (e: dev.caulis.ffi.NativeException) {
                if (e.errno == dev.caulis.ffi.Errno.EAGAIN || e.errno == dev.caulis.ffi.Errno.EINTR) return
                -1L
            }
            if (n <= 0) null else buffer.asSlice(0, n).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE).decodeToString()
        }
        if (chunk == null) {
            loop.remove(socket.fd)
            socket.close()
            ready = null
            log.warn("Xwayland did not start (see its messages above); the next X11 program tries again")
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
            xwm = Xwm(XConnection(socket.fd.duplicate().value), desktop, loop, { serials[it] }, clipboard)
            for ((serial, surface) in serials) xwm?.surfaceAssociated(serial, surface)
            tellResources()
        } catch (e: Exception) {
            log.error("The X11 window manager could not start", e)
        }
    }

    // X11 programs learn the scale from resources: the DPI and the cursor size in pixels.
    private fun tellResources() {
        val theme = desktop.cursorTheme
        val resources = buildString {
            appendLine("Xft.dpi: ${Math.round(96 * desktop.scale)}")
            appendLine("Xcursor.size: ${Math.round(theme.size * desktop.scale)}")
            appendLine("Xcursor.theme: ${theme.name}")
        }
        try {
            val builder = ProcessBuilder("xrdb", "-merge").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.environment()["DISPLAY"] = displayName
            builder.environment().remove("WAYLAND_DISPLAY")
            val xrdb = builder.start()
            xrdb.outputStream.use { it.write(resources.toByteArray()) }
            log.trace { "X resources: ${resources.trim().replace('\n', ',')}" }
        } catch (e: Exception) {
            log.warn("xrdb is missing: X11 programs will not know the scale (install xorg-xrdb)", e)
        }
    }

    /** The scale changed: X11 windows follow; programs started from now on get the new DPI. */
    fun rescaled() {
        xwm?.rescaled()
        if (xwm != null) tellResources()
    }

    /** The cursor theme or size changed: X11 programs started from now on use it, and the default X cursor too. */
    fun cursorChanged() {
        xwm?.updateCursor()
        if (xwm != null) tellResources()
    }

    // Xwayland stops [idleSeconds] after the last X11 program (it starts again with the next):
    // "-terminate <seconds>" since Xwayland 23.1, plain "-terminate" (at once) before.
    private val terminateArguments: List<String> by lazy {
        if (idleSeconds <= 0) emptyList()
        else {
            val delay = runCatching {
                val help = ProcessBuilder("Xwayland", "-help").redirectErrorStream(true).start()
                help.inputStream.bufferedReader().readText().also { help.waitFor() }
            }.getOrDefault("").contains("-terminate [delay]")
            if (delay) listOf("-terminate", idleSeconds.toString()) else listOf("-terminate")
        }
    }

    private fun stopped() {
        val ended = process ?: return
        loop.remove(ended.ended)
        val status = ended.reap()
        if (status == 0) log.info { "Xwayland stopped: no X11 programs left (the next one starts it again)" }
        else log.warn("Xwayland stopped (exit status $status); the next X11 program starts it again")
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
        loop.remove(listener.fd)
        listener.close()
        runCatching { Files.deleteIfExists(SOCKET_DIR.resolve("X$number")) }
        runCatching { Files.deleteIfExists(lockFile) }
    }

    private companion object {
        val SOCKET_DIR: Path = Path.of("/tmp/.X11-unix")
    }
}
