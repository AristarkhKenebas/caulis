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
    /** How it is started again after failing (the [Xwayland]'s settings). */
    private val restarts: Restarts = Restarts(),
    /** Called when Xwayland stopped (all its programs left, or it failed). */
    private val onStopped: (XServer) -> Unit = {},
) : AutoCloseable {

    // How Xwayland is started again after failing to start: waits growing from [delayMillis]
    // to [maxDelayMillis]; after [maxFailures] in a row X11 programs are turned away.
    class Restarts(val delayMillis: () -> Long = { 1000 }, val maxDelayMillis: () -> Long = { 60_000 }, val maxFailures: () -> Int = { 5 })

    // Failed starts in a row (Xwayland missing, quitting before it was ready, or crashing soon
    // after: sooner than the longest wait between tries).
    private var failures = 0

    // When the Xwayland running now said it is ready (System.nanoTime).
    private var readyAt = 0L

    // The wait before the next try after a failure, if one is going on.
    private var retryTimer: EventLoop.Timer? = null

    // True once Xwayland gave up for good: programs that connect are turned away at once.
    private var refusing = false

    // True once the Xwayland running now said it is ready.
    private var wasReady = false

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
            listOf(waylandOurs, wmOurs, readyOurs).forEach { it.close() }
            outputOurs.close()
            if (failures == 0) log.error("Xwayland could not be started (is it installed? the package is usually called xwayland or xorg-xwayland)", e)
            else log.warn("Xwayland could not be started again: ${e.message ?: e}")
            failed()
            return
        } finally {
            waylandTheirs.close()
            wmTheirs.close()
            readyTheirs.close()
            outputTheirs.close()
        }
        readOutput(outputOurs)
        process = started
        wasReady = false
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
        wasReady = true
        readyAt = System.nanoTime()
        ready?.let { socket ->
            loop.remove(socket.fd)
            socket.close()
        }
        ready = null
        readyLine.clear()
        val socket = wmSocket ?: return
        try {
            xwm = Xwm(XConnection(socket.fd.duplicate().value), desktop, loop, { serials[it] }, clipboard, pixelScale, display::capForwarded)
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
        val failedToStart = !wasReady && status != 0
        // One that crashes soon after it was ready (on the first X11 program) counts as a failure
        // too: else each new X11 program started it again at full speed.
        val ranMillis = if (wasReady) (System.nanoTime() - readyAt) / 1_000_000 else 0L
        val crashedSoon = wasReady && status != 0 && ranMillis < restarts.maxDelayMillis()
        if (status == 0) log.info { "$name stopped: no X11 programs left (the next one starts it again)" }
        else if (crashedSoon) log.warn("$name crashed $ranMillis ms after it was ready (exit status $status)")
        else if (!failedToStart) log.warn("$name stopped (exit status $status); the next X11 program starts it again")
        else log.warn("$name quit before it was ready (exit status $status)")
        // (Ran fine, or for long enough: the failures in a row are over.)
        if (!failedToStart && !crashedSoon) failures = 0
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
        if (failedToStart || crashedSoon) failed() else watchForFirstProgram()
        onStopped(this)
    }

    // Xwayland did not start: tried again after a wait that grows with each failure in a row
    // (a missing or broken Xwayland, with an X11 program waiting, started it again and again
    // at full speed); after too many, X11 programs are turned away until [retry].
    private fun failed() {
        failures++
        val max = restarts.maxFailures()
        if (failures >= max) {
            log.error("$name failed to start $failures times in a row: X11 programs are turned away from now on (fix Xwayland, then restart the compositor or retry)")
            refusing = true
            loop.add(listener.fd) { turnAway() }
            return
        }
        val delay = backoff(restarts.delayMillis(), restarts.maxDelayMillis(), failures)
        log.info { "$name: trying again in ${delay} ms (failure $failures of $max)" }
        retryTimer = loop.after(delay) {
            retryTimer = null
            watchForFirstProgram()
        }
    }

    // Programs waiting for an Xwayland that gave up get an error at once (else they hung).
    private fun turnAway() {
        while (true) {
            val waiting = listener.accept() ?: break
            waiting.close()
            log.trace { "An X11 program was turned away: $name gave up starting" }
        }
    }

    /** Tries again after giving up (or waiting) on a broken Xwayland: the next X11 program starts it. */
    fun retry() {
        if (process != null) return
        if (refusing || failures > 0) log.info { "$name: the next X11 program tries to start it again" }
        failures = 0
        refusing = false
        retryTimer?.cancel()
        retryTimer = null
        loop.remove(listener.fd)
        watchForFirstProgram()
    }

    override fun close() {
        retryTimer?.cancel()
        retryTimer = null
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

/**
 * The wait before try number [failures] + 1: [delay] doubled for each failure after the first,
 * at most [max] (never overflowing into a negative wait, however big the settings).
 */
internal fun backoff(delay: Long, max: Long, failures: Int): Long {
    val limit = max.coerceAtLeast(0)
    var wait = delay.coerceIn(0, limit)
    repeat((failures - 1).coerceAtLeast(0)) { wait = if (wait > limit / 2) limit else wait * 2 }
    return wait
}
