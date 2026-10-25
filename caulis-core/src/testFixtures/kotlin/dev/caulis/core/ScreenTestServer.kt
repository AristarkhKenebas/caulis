package dev.caulis.core

import dev.caulis.core.input.Keymap
import dev.caulis.core.input.Seat
import dev.caulis.event.EventLoop
import dev.caulis.protocol.Display
import dev.caulis.protocol.presentationtime.WpPresentationFeedback
import dev.caulis.render.gles.GlesRenderer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A Caulis server with a pretend 60 Hz screen: the real window manager ([Desktop]) draws
 * on the GPU into an off-screen picture, as on a monitor. For tests of what happens around
 * frames (presentation-time, hidden windows). Needs a GPU.
 */
class ScreenTestServer(
    /** How many pretend monitors (TEST-1, TEST-2, ...), each [WIDTH] x [HEIGHT], side by side. */
    private val monitorCount: Int = 1,
    /** How workspaces behave on them (set before they come). */
    private val workspaceMode: WorkspaceMode = WorkspaceMode.PER_MONITOR,
) : AutoCloseable {

    val runtimeDir: Path = Files.createTempDirectory("caulis-screen-test")
    private val started = CompletableFuture<String>()
    /** The server's event loop, display and clipboard: for adding more to the server (on its thread). */
    lateinit var loop: EventLoop
        private set
    lateinit var display: Display
        private set
    lateinit var clipboard: DataDeviceManager
        private set
    private lateinit var desktop: Desktop
    private lateinit var renderer: GlesRenderer
    private val outputs = ArrayList<Output>()

    /**
     * Called (on the server's thread) with every frame that reaches a pretend screen: the
     * monitor's name and its pixels, row by row from the top, 0xAARRGGBB — what is really
     * shown, after drawing only what changed.
     */
    @Volatile
    var onFrameShown: ((String, IntArray) -> Unit)? = null

    /** Scales the pretend screens (the desktop and its monitors), as a user's setting does. */
    fun setScale(scale: Double) = onServer { desktop ->
        desktop.scale = scale
    }

    /** Runs [action] on the server's thread with its window manager and returns the result. */
    fun <T> onServer(action: (Desktop) -> T): T {
        val result = CompletableFuture<T>()
        loop.post {
            try {
                result.complete(action(desktop))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result.get(5, TimeUnit.SECONDS)
    }

    /**
     * Draws the whole desktop as it looks now into a fresh picture and returns its pixels,
     * row by row from the top, each 0xAARRGGBB.
     */
    fun screenshot(monitor: Int = 0): IntArray = onServer { desktop ->
        renderer.createTarget(WIDTH, HEIGHT).use { target ->
            val view = desktop.createView(desktop.monitors[monitor])
            try {
                desktop.render(view, target, age = 0)
            } finally {
                desktop.removeView(view)
            }
            java.lang.foreign.Arena.ofConfined().use { arena ->
                val pixels = arena.allocate(WIDTH.toLong() * HEIGHT * 4)
                target.readPixels(pixels, WIDTH * 4)
                pixels.toArray(java.lang.foreign.ValueLayout.JAVA_INT)
            }
        }
    }

    /**
     * The picture screenshots and screen sharing get of a monitor ([Desktop.capture]), row
     * by row from the top, each 0xAARRGGBB.
     */
    fun capture(monitor: Int = 0): IntArray = onServer { desktop ->
        renderer.createTarget(WIDTH, HEIGHT).use { target ->
            desktop.capture(target, cursor = false, desktop.monitors[monitor])
            java.lang.foreign.Arena.ofConfined().use { arena ->
                val pixels = arena.allocate(WIDTH.toLong() * HEIGHT * 4)
                target.readPixels(pixels, WIDTH * 4)
                pixels.toArray(java.lang.foreign.ValueLayout.JAVA_INT)
            }
        }
    }

    /** Frames drawn so far (written on the server thread). */
    @Volatile
    var framesDrawn = 0
        private set

    /** Frames where a program's own buffer went to the screen directly (direct scanout). */
    @Volatile
    var framesShownDirectly = 0
        private set

    private val thread = Thread {
        try {
            EventLoop().use { eventLoop ->
                loop = eventLoop
                GlesRenderer.create().use { renderer ->
                    this.renderer = renderer
                    Display(eventLoop).use { display ->
                        this.display = display
                        Keymap.fromNames().use { keymap ->
                            Seat(display, keymap).use { seat ->
                                val compositor = Compositor(display, renderer)
                                Shm(display)
                                clipboard = DataDeviceManager(display, seat)
                                Presentation(display)
                                LinuxDmabuf(display, renderer)
                                desktop = Desktop(renderer, seat, compositor, WIDTH, HEIGHT)
                                // Windows are there at once, so pictures of the screen show them right
                                // away (tests of these animations turn them on).
                                desktop.animations = Animations(windowOpen = WindowAnimation.NONE, windowClose = WindowAnimation.NONE)
                                XdgShell(display).listener = desktop
                                LayerShell(display).listener = desktop
                                SessionLockManager(display).listener = desktop
                                dev.caulis.core.input.CursorShape(display, seat)
                                XdgOutputManager(display)
                                ServerDecorations(display, desktop)
                                ScreenCapture(display, desktop, renderer, ForeignToplevels(display, desktop))
                                desktop.workspaceMode = workspaceMode
                                for (n in 1..monitorCount) {
                                    val output = Output(display, "TEST-$n", "Test screen $n", WIDTH, HEIGHT, 60_000)
                                    outputs += output
                                    runScreen(eventLoop, renderer, desktop, desktop.addMonitor("TEST-$n", output, WIDTH, HEIGHT))
                                }
                                started.complete(display.addSocket(runtimeDir))
                                eventLoop.run()
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            started.completeExceptionally(e)
        }
    }

    // Refreshes every 16 ms, like a 60 Hz monitor, and reports each drawn frame as shown.
    private fun runScreen(loop: EventLoop, renderer: GlesRenderer, desktop: Desktop, monitor: Monitor) {
        val target = renderer.createTarget(WIDTH, HEIGHT)
        val view = desktop.createView(monitor)
        val rotator = Rotator(renderer, desktop) // (the screen shader, if a test sets one)
        var sequence = 0L
        fun tick() {
            // A pretend display that can show programs' buffers directly.
            val drew = desktop.refresh(view, { draw -> rotator.wrap(Transform.NORMAL, draw)(target, 1) }, presentDirect = { _ ->
                framesShownDirectly++
                true
            })
            if (drew) {
                framesDrawn++
                onFrameShown?.let { look ->
                    // What is on the pretend screen now (after drawing only what changed).
                    java.lang.foreign.Arena.ofConfined().use { arena ->
                        val pixels = arena.allocate(WIDTH.toLong() * HEIGHT * 4)
                        renderer.finish()
                        target.readPixels(pixels, WIDTH * 4)
                        look(monitor.name, pixels.toArray(java.lang.foreign.ValueLayout.JAVA_INT))
                    }
                }
                desktop.presented(view, System.nanoTime(), REFRESH_NANOS, ++sequence, WpPresentationFeedback.Kind.VSYNC)
            }
            loop.after(16) { tick() }
        }
        tick()
    }

    val socketName: String

    init {
        thread.start()
        socketName = started.get(10, TimeUnit.SECONDS)
    }

    // If the server's loop stands still for long, what it waits for is printed (for tests that fail rarely).
    @Volatile
    private var closing = false
    private val watchdog = Thread({
        var reported = 0L
        while (!closing) {
            Thread.sleep(500)
            val since = loop.lastIterationNanos
            if (System.nanoTime() - since > 2_000_000_000 && since != reported && !closing) {
                reported = since
                System.err.println("The test server's loop has stood still for 2 s, at:\n" + thread.stackTrace.joinToString("\n") { "    at $it" })
            }
        }
    }, "screen-test-watchdog").apply { isDaemon = true }

    init {
        watchdog.start()
    }

    val socketPath: Path get() = runtimeDir.resolve(socketName)

    override fun close() {
        closing = true
        loop.stop()
        thread.join(5000)
        runtimeDir.toFile().deleteRecursively()
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val REFRESH_NANOS = 16_666_666L
    }
}
