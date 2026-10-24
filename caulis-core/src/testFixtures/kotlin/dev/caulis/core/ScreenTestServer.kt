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
class ScreenTestServer : AutoCloseable {

    val runtimeDir: Path = Files.createTempDirectory("caulis-screen-test")
    private val started = CompletableFuture<String>()
    private lateinit var loop: EventLoop
    private lateinit var desktop: Desktop
    private lateinit var renderer: GlesRenderer

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
    fun screenshot(): IntArray = onServer { desktop ->
        renderer.createTarget(WIDTH, HEIGHT).use { target ->
            val view = desktop.createView()
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
                        Keymap.fromNames().use { keymap ->
                            Seat(display, keymap).use { seat ->
                                val compositor = Compositor(display, renderer)
                                Shm(display)
                                Presentation(display)
                                LinuxDmabuf(display, renderer)
                                desktop = Desktop(renderer, seat, compositor, WIDTH, HEIGHT)
                                XdgShell(display).listener = desktop
                                LayerShell(display).listener = desktop
                                SessionLockManager(display).listener = desktop
                                dev.caulis.core.input.CursorShape(display, seat)
                                Output(display, "TEST-1", "Test screen", WIDTH, HEIGHT, 60_000)
                                runScreen(eventLoop, renderer, desktop)
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
    private fun runScreen(loop: EventLoop, renderer: GlesRenderer, desktop: Desktop) {
        val target = renderer.createTarget(WIDTH, HEIGHT)
        val view = desktop.createView()
        var sequence = 0L
        fun tick() {
            // A pretend display that can show programs' buffers directly.
            val drew = desktop.refresh(view, { draw -> draw(target, 1) }, presentDirect = { _ ->
                framesShownDirectly++
                true
            })
            if (drew) {
                framesDrawn++
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

    val socketPath: Path get() = runtimeDir.resolve(socketName)

    override fun close() {
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
