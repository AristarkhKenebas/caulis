package dev.caulis.xwayland

import dev.caulis.event.EventLoop
import dev.caulis.ffi.Fd
import dev.caulis.ffi.NativeException
import dev.caulis.ffi.UnixListener
import dev.caulis.ffi.UnixSocket
import dev.caulis.log.Logger
import java.lang.foreign.Arena
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * The X display's door: X11 programs connect here, and each is joined to the Xwayland that
 * suits it ([route]: a path to that Xwayland's own socket) — programs that know only whole
 * scales to one that works in a whole number of pixels, the others to the usual one.
 *
 * Xwayland cannot be handed a connection, so the router passes the bytes (and the file
 * descriptors that come with them) between the two, in both directions, as they come. It
 * never reads what they say: it only carries them. A side that is slower to take them makes
 * the other wait (nothing piles up). Only programs of the same user are let in.
 *
 * Runs on a thread of its own: a busy or stuck X11 program never holds up the compositor.
 */
internal class XRouter(
    private val listener: UnixListener,
    private val route: (UnixSocket.Credentials) -> Path?,
) : AutoCloseable {

    private val log = Logger("xwayland")
    private val user = java.nio.file.Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int
    private val links = HashSet<Link>()
    private val loop = EventLoop()
    private val started = CountDownLatch(1)

    private val thread = Thread({
        loop.add(listener.fd) { acceptAll() }
        started.countDown()
        try {
            loop.run()
        } finally {
            for (link in links.toList()) link.close()
            loop.close()
        }
    }, "caulis-x11-router").apply {
        isDaemon = true
        start()
    }

    init {
        started.await()
    }

    /** Connections joined now (for the log and tests). */
    val connections: Int get() = links.size

    private fun acceptAll() {
        while (true) {
            val program = try {
                listener.accept() ?: return
            } catch (e: NativeException) {
                log.warn("An X11 program could not be let in: ${e.message}")
                return
            }
            join(program)
        }
    }

    private fun join(program: UnixSocket) {
        val who = try {
            program.peerCredentials()
        } catch (e: NativeException) {
            program.close()
            return
        }
        if (who.uid != user) {
            log.warn("An X11 program of another user (uid ${who.uid}, process ${who.pid}) was turned away")
            program.close()
            return
        }
        val path = runCatching { route(who) }.getOrNull()
        val server = path?.let { runCatching { UnixSocket.connect(it) }.getOrNull() }
        if (server == null) {
            log.warn("The X11 program ${who.pid} could not be joined to its Xwayland")
            program.close()
            return
        }
        log.trace { "X11 program ${who.pid} joined to ${path.fileName}" }
        links += Link(program, server)
    }

    /** One program's connection and its Xwayland's, carried both ways. */
    private inner class Link(private val program: UnixSocket, private val server: UnixSocket) {
        private val up = Flow(program, server)   // the program's requests
        private val down = Flow(server, program) // Xwayland's replies and events
        private var closed = false

        init {
            loop.add(program.fd) { ready -> handle(program, ready.readable, ready.writable, ready.hangUp || ready.error) }
            loop.add(server.fd) { ready -> handle(server, ready.readable, ready.writable, ready.hangUp || ready.error) }
        }

        private fun handle(socket: UnixSocket, readable: Boolean, writable: Boolean, broken: Boolean) {
            val (from, to) = if (socket === program) up to down else down to up
            // `from` reads this socket, `to` writes into it.
            if (writable && to.waiting) to.write()
            if (readable && !from.waiting) from.read()
            if (closed) return
            if (broken && !readable) return close()
            watch()
        }

        // Read a socket only while what came from it has been passed on; write when something waits.
        private fun watch() {
            if (closed) return
            loop.update(program.fd, read = !up.waiting, write = down.waiting)
            loop.update(server.fd, read = !down.waiting, write = up.waiting)
        }

        private inner class Flow(private val source: UnixSocket, private val target: UnixSocket) {
            private val memory = Arena.ofShared()
            private val buffer = memory.allocate(BUFFER.toLong())
            private val fds = ArrayList<Fd>()
            private var length = 0L
            private var sent = 0L

            /** Bytes read but not passed on yet. */
            val waiting: Boolean get() = sent < length

            fun read() {
                val count = try {
                    source.receive(buffer, fds)
                } catch (e: NativeException) {
                    return close()
                }
                if (count == 0L) return close() // that side closed the connection
                if (count < 0L) return
                length = count
                sent = 0
                write()
            }

            fun write() {
                while (sent < length) {
                    val count = try {
                        // The descriptors go with the first bytes they came with.
                        target.send(buffer.asSlice(sent, length - sent), if (sent == 0L) fds else emptyList())
                    } catch (e: NativeException) {
                        return close()
                    }
                    if (count < 0L) return // full for now: on when it can take more
                    if (sent == 0L) clearFds()
                    sent += count
                }
            }

            // Passed on: the other side has its own copies.
            private fun clearFds() {
                for (fd in fds) fd.close()
                fds.clear()
            }

            fun release() {
                clearFds()
                memory.close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            loop.remove(program.fd)
            loop.remove(server.fd)
            program.close()
            server.close()
            up.release()
            down.release()
            links -= this
        }
    }

    override fun close() {
        loop.post { loop.remove(listener.fd) }
        loop.stop()
        thread.join(2000)
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
