package dev.caulis.xwayland

import dev.caulis.core.DataDeviceManager
import dev.caulis.core.Desktop
import dev.caulis.core.Output
import dev.caulis.core.Surface
import dev.caulis.event.EventLoop
import dev.caulis.ffi.UnixListener
import dev.caulis.ffi.UnixSocket
import dev.caulis.log.Logger
import dev.caulis.protocol.Display
import dev.caulis.protocol.xwaylandshellv1.XwaylandShellV1
import dev.caulis.protocol.xwaylandshellv1.XwaylandSurfaceV1
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * X11 programs in the compositor. Their display ([displayName], e.g. ":1", what DISPLAY
 * is set to) is open at once, but Xwayland itself is started only when the first X11
 * program connects — no memory or start-up time is spent while none runs — and stops by
 * itself [idleSeconds] after the last one left; the next starts it again.
 *
 * Their windows are managed by [Xwm]: they tile, float, get title bars and effects, show
 * in docks and screen sharing like Wayland windows, and copy and paste works between X11
 * and Wayland programs (give the [clipboard]).
 *
 * Scaled screens: X works in the screen's real pixels and programs are told the real DPI
 * (Xft.dpi) and cursor size, so they are as sharp and as big as Wayland programs. Programs
 * that only know whole scales — Java (Swing, AWT) — cannot draw at 1.5: with
 * [wholeScaleWorlds], they get an Xwayland of their own that works in a whole number of
 * pixels per unit (their scale, e.g. 2), and are shown at exactly the screen's scale —
 * sharp (made smaller, never larger), the right size, every part clickable. The display
 * then is a door ([XRouter]) that joins each program to its Xwayland.
 */
class Xwayland(
    private val display: Display,
    private val desktop: Desktop,
    private val loop: EventLoop,
    /** The clipboard X11 programs share with Wayland programs (none: they only share among themselves). */
    private val clipboard: DataDeviceManager? = null,
    /** Seconds Xwayland keeps running after the last X11 program closed (0: until the compositor stops). */
    private val idleSeconds: Int = 10,
    /** Give programs that only know whole scales an Xwayland of their own (see above). */
    wholeScaleWorlds: Boolean = true,
    /**
     * Which whole scale a program (by process) draws at, if it only knows whole ones — null
     * for programs that draw at any scale. By default: Java programs, at their J2D_UISCALE.
     */
    wholeScaleOf: (pid: Int) -> Int? = WholeScalePrograms::scaleOf,
) : AutoCloseable {

    private val log = Logger("xwayland")

    /** The X display X11 programs connect to, e.g. ":1" (set DISPLAY to it). */
    val displayName: String

    private val number: Int
    private val lockFile: Path
    private val publicPath: Path

    // The Xwaylands, by how many X pixels one desktop unit is for them (each keeps its scale
    // as long as it runs), and the door in front of them. Without the door: one Xwayland,
    // which follows the scale.
    private val worlds = HashMap<Double, XServer>()
    private var direct: XServer? = null
    private val router: XRouter?
    private val privateDir: Path?
    private val publicListener: UnixListener

    // The scale, as the router (on its own thread) sees it.
    @Volatile
    private var scaleNow = desktop.largestScale

    // The Xwayland ordinary programs go to now: the one at the screen's scale.
    private val usual: XServer get() = direct ?: worlds.getValue(scaleNow)

    /** The window manager of the Xwayland ordinary programs use now, while it runs. */
    val xwm: Xwm? get() = usual.xwm

    /** True while an Xwayland runs. */
    val running: Boolean get() = servers.any { it.running }

    private val servers: List<XServer> get() = listOfNotNull(direct) + worlds.values

    init {
        val (n, lock) = claimDisplay()
        number = n
        lockFile = lock
        displayName = ":$n"
        Files.createDirectories(SOCKET_DIR)
        publicPath = SOCKET_DIR.resolve("X$n")
        val public = try {
            UnixListener.listen(publicPath)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(lock) }
            throw e
        }
        publicListener = public
        if (wholeScaleWorlds) {
            // Each Xwayland listens privately; programs come in through the door. The whole
            // scales are there from the start (the door may need one at any time).
            privateDir = privateDirectory()
            for (scale in 1..MAX_WHOLE_SCALE) world(scale.toDouble())
            world(scaleNow)
            router = XRouter(public) { who -> routeOf(who.pid, wholeScaleOf).listener.path }
        } else {
            privateDir = null
            direct = server("X11", public, { desktop.largestScale }, onStopped = {})
            router = null
        }
        // Only our Xwaylands may tie X windows to surfaces.
        display.addGlobal(XwaylandShellV1, 1, { client -> servers.any { it.client === client } }) { shell ->
            shell.requests = object : XwaylandShellV1.Requests {
                override fun getXwaylandSurface(resource: XwaylandShellV1, id: XwaylandSurfaceV1, surface: dev.caulis.protocol.wayland.WlSurface) {
                    val target = surface.dataAs<Surface>() ?: return
                    servers.firstOrNull { it.client === resource.client }?.shellSurface(resource, id, target)
                }

                override fun destroy(resource: XwaylandShellV1) {}
            }
        }
        // Xwayland works in pixels: the screen's real ones, or a whole number per unit.
        Output.reportScaledTo(display) { client, _ ->
            // One pixel space for the whole X world: every monitor in its pixels (and so its place).
            servers.firstOrNull { it.client === client }?.pixelScale?.invoke()
        }
        log.info { "X11 programs can use DISPLAY=$displayName (Xwayland starts with the first one)" }
    }

    private fun server(name: String, listener: UnixListener, pixelScale: () -> Double, onStopped: (XServer) -> Unit) =
        XServer(name, displayName, display, desktop, loop, clipboard, listener, pixelScale, { terminateArguments }, onStopped)

    // The Xwayland at [scale] (made if there is none: it starts with its first program).
    private fun world(scale: Double): XServer = worlds.getOrPut(scale) {
        val label = if (scale == Math.floor(scale)) scale.toInt().toString() else scale.toString()
        server("X11 at scale $label", listenPrivately(privateDir!!.resolve("X$number-$label")), { scale }, onStopped = ::forgetIfOld)
    }

    // An Xwayland of an earlier scale is closed once its last program left.
    private fun forgetIfOld(server: XServer) {
        val scale = worlds.entries.firstOrNull { it.value === server }?.key ?: return
        if (scale == scaleNow || isWholeKept(scale) || server.running) return
        synchronized(worlds) { worlds.remove(scale) }
        server.close()
        log.info { "${server.name} closed: the screen's scale is $scaleNow now" }
    }

    private fun isWholeKept(scale: Double) = scale == Math.floor(scale) && scale.toInt() in 1..MAX_WHOLE_SCALE

    // Where a program goes (asked on the door's thread): to the Xwayland at the screen's scale,
    // or, if it draws only at whole scales, to the one at its own whole scale — it draws there
    // at that scale and is shown at the screen's (made smaller: sharp, and the right size).
    private fun routeOf(pid: Int, wholeScaleOf: (Int) -> Int?): XServer {
        val now = scaleNow
        val whole = runCatching { wholeScaleOf(pid) }.getOrNull()?.coerceIn(1, MAX_WHOLE_SCALE)
        val scale = whole?.toDouble() ?: now
        val world = synchronized(worlds) { worlds[scale] ?: worlds[now] } ?: return usual
        if (whole != null && scale != now) log.info { "X11 program $pid draws only at whole scales: it gets ${world.name}, shown at $now" }
        return world
    }

    // The display number is ours: a socket left by an earlier session that crashed is stale.
    private fun listenPrivately(path: Path): UnixListener {
        Files.deleteIfExists(path)
        return UnixListener.listen(path)
    }

    // A folder only we can use for the Xwaylands' own sockets.
    private fun privateDirectory(): Path {
        val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val dir = if (runtime != null) runtime.resolve("caulis-x11") else Files.createTempDirectory("caulis-x11")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        return dir
    }

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

    /**
     * The scale changed. X11 programs running keep drawing at their scale (they could not
     * change it anyway) and are shown at the same size; programs started from now on get an
     * Xwayland at the new scale and draw at it exactly. Without the door, the one Xwayland
     * follows the scale (its programs keep their DPI until they are restarted).
     */
    fun rescaled() {
        if (direct == null) synchronized(worlds) { world(desktop.largestScale) }
        scaleNow = desktop.largestScale
        for (server in servers) server.rescaled()
        if (direct == null) for (server in worlds.values.toList()) if (!server.running) forgetIfOld(server)
    }

    /** The cursor theme or size changed: X11 programs started from now on use it, and the default X cursor too. */
    fun cursorChanged() {
        for (server in servers) server.cursorChanged()
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

    override fun close() {
        router?.close()
        for (server in servers) server.close()
        if (router != null) publicListener.close()
        privateDir?.let { dir -> runCatching { Files.deleteIfExists(dir) } }
        runCatching { Files.deleteIfExists(lockFile) }
    }

    private companion object {
        val SOCKET_DIR: Path = Path.of("/tmp/.X11-unix")
        const val MAX_WHOLE_SCALE = 4
    }
}
