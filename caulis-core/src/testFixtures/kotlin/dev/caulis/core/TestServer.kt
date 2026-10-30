package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.protocol.Display
import dev.caulis.render.assumeGpu
import dev.caulis.render.gles.GlesRenderer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A real Caulis server for tests, running its event loop on its own thread
 * with a private runtime directory (so it never touches the user's session).
 * With [gpu] it draws with the GPU and accepts clients' GPU buffers.
 */
class TestServer(gpu: Boolean = false) : AutoCloseable {

    init {
        // Without a GPU to draw on, a test that wants one is skipped (not failed).
        if (gpu) assumeGpu()
    }

    val runtimeDir: Path = Files.createTempDirectory("caulis-test-runtime")

    /** Titles of windows that became visible (written on the server thread). */
    val mappedTitles: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** Size of the picture of every commit that showed one, e.g. "32x16" (written on the server thread). */
    val shownPictures: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val started = CompletableFuture<String>()
    private lateinit var loop: EventLoop

    private val thread = Thread {
        try {
            EventLoop().use { eventLoop ->
                loop = eventLoop
                val renderer = if (gpu) GlesRenderer.create() else null
                Display(eventLoop).use { display ->
                    val compositor = Compositor(display, renderer)
                    compositor.onNewSurface { surface ->
                        surface.onCommit {
                            if (surface.hasContent) shownPictures += "${surface.current.bufferWidth}x${surface.current.bufferHeight}"
                            // Like a screen that shows every frame at once: the client may draw the next one.
                            surface.sendFrameDone(0)
                        }
                    }
                    Shm(display)
                    if (renderer != null) LinuxDmabuf(display, renderer)
                    XdgShell(display).listener = object : XdgShell.Listener {
                        override fun windowMapped(window: Window) {
                            mappedTitles += window.title
                        }
                    }
                    Output(display, "HEADLESS-1", "Test output", 1920, 1080, 60_000)
                    started.complete(display.addSocket(runtimeDir))
                    eventLoop.run()
                }
                renderer?.close()
            }
        } catch (e: Throwable) {
            started.completeExceptionally(e)
        }
    }

    /** Name of the socket, e.g. "wayland-0". */
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
}
