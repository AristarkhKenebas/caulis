package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.ffi.Fd
import dev.caulis.log.Logger
import dev.caulis.render.Dmabuf
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

/**
 * Waits for a program's GPU to finish a picture before its commit is applied: until then the
 * picture before stays on screen, and one slow program does not make the compositor wait inside
 * its own drawing (and miss the screen's refresh, for all windows).
 *
 * The kernel hands out what to wait for: DMA_BUF_IOCTL_EXPORT_SYNC_FILE, since Linux 6.0. On a
 * kernel or driver without it the commit is applied at once, as without this.
 */
internal class ClientFences(private val log: Logger) {

    private var unsupported = false

    /**
     * Descriptors (closed by the caller) that become readable when [dmabuf]'s pictures are done;
     * empty: nothing to wait for.
     */
    fun of(dmabuf: Dmabuf): List<Fd> {
        if (unsupported) return emptyList()
        val fences = ArrayList<Fd>(1)
        for (plane in dmabuf.planes) {
            val fence = exportSyncFile(plane.fd) ?: break
            if (fence.readableNow()) fence.close() else fences += fence
        }
        return fences
    }

    private fun exportSyncFile(dmabuf: Fd): Fd? = try {
        Arena.ofConfined().use { arena ->
            // struct dma_buf_export_sync_file { __u32 flags; __s32 fd; }
            val request = arena.allocate(8, 4)
            request.set(ValueLayout.JAVA_INT, 0, DMA_BUF_SYNC_READ)
            request.set(ValueLayout.JAVA_INT, 4, -1)
            dmabuf.ioctl(DMA_BUF_IOCTL_EXPORT_SYNC_FILE, request)
            Fd.adopt(request.get(ValueLayout.JAVA_INT, 4))
        }
    } catch (e: dev.caulis.ffi.NativeException) {
        unsupported = true
        log.info { "Programs' GPU work cannot be waited for here (commits go on at once): ${e.message}" }
        null
    }

    private companion object {
        const val DMA_BUF_SYNC_READ = 1
        // _IOWR('b', 2, struct dma_buf_export_sync_file)
        const val DMA_BUF_IOCTL_EXPORT_SYNC_FILE = 0xC0086202L
    }
}

/**
 * Commits of one surface waiting for their fences, oldest first: applied in the order they came.
 * More than [MAX_HELD] waiting (a program far ahead of its GPU): the oldest goes on regardless,
 * so nothing piles up without end.
 */
internal class HeldCommits(private val loop: EventLoop, private val apply: (SurfaceState) -> Unit) {
    private class Held(val state: SurfaceState, val fences: ArrayDeque<Fd>)

    private val held = ArrayDeque<Held>()
    private var waitingOn: Fd? = null

    val isEmpty: Boolean get() = held.isEmpty()

    fun add(state: SurfaceState, fences: List<Fd>) {
        held.addLast(Held(state, ArrayDeque(fences)))
        if (held.size > MAX_HELD) {
            stopWaiting()
            release(held.removeFirst())
        }
        next()
    }

    // The oldest's next fence, or (none left) it is applied and on to the next.
    private fun next() {
        while (waitingOn == null) {
            val first = held.firstOrNull() ?: return
            val fence = first.fences.firstOrNull()
            if (fence == null) {
                held.removeFirst()
                apply(first.state)
                continue
            }
            waitingOn = fence
            loop.add(fence, read = true) {
                first.fences.removeFirst()
                stopWaiting()
                next()
            }
        }
    }

    private fun stopWaiting() {
        val fence = waitingOn ?: return
        waitingOn = null
        loop.remove(fence)
        fence.close()
    }

    private fun release(one: Held) {
        one.fences.forEach { if (it.isOpen) it.close() }
        apply(one.state)
    }

    /** The surface is gone: nothing is applied any more. */
    fun forget() {
        stopWaiting()
        for (one in held) one.fences.forEach { if (it.isOpen) it.close() }
        held.clear()
    }

    private companion object {
        const val MAX_HELD = 4
    }
}
