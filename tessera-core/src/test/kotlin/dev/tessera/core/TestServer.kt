package dev.tessera.core

import dev.tessera.event.EventLoop
import dev.tessera.protocol.Display
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A real Tessera server for tests, running its event loop on its own thread
 * with a private runtime directory (so it never touches the user's session).
 */
class TestServer : AutoCloseable {

    val runtimeDir: Path = Files.createTempDirectory("tessera-test-runtime")

    /** Titles of windows that became visible (written on the server thread). */
    val mappedTitles: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val started = CompletableFuture<String>()
    private lateinit var loop: EventLoop

    private val thread = Thread {
        try {
            EventLoop().use { eventLoop ->
                loop = eventLoop
                Display(eventLoop).use { display ->
                    Compositor(display, renderer = null)
                    Shm(display)
                    XdgShell(display).listener = object : XdgShell.Listener {
                        override fun windowMapped(window: Window) {
                            mappedTitles += window.title
                        }
                    }
                    Output(display, "HEADLESS-1", "Test output", 1920, 1080, 60_000)
                    started.complete(display.addSocket(runtimeDir))
                    eventLoop.run()
                }
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
