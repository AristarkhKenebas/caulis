package dev.caulis.core.libinput

import dev.caulis.core.input.EarlyPointer
import dev.caulis.event.EventLoop
import dev.caulis.ffi.Upcalls
import dev.caulis.ffi.c.input.Input

/**
 * Input read on a thread of its own: batches handed to the compositor's thread, movements merged
 * while it is busy.
 */
internal class InputThread(private val inputDevices: InputDevices) {

    internal var thread: Thread? = null

    private var inputLoop: EventLoop? = null

    // What the compositor's thread is to do, in order: events for the callbacks, devices to
    // open or close. One queue (FIFO), emptied by one task posted when it was empty.
    internal val forMain = MainQueue(inputDevices.loop::post)

    // Runs [task] on the compositor's thread: at once there, else queued (in order).
    internal fun toMain(task: () -> Unit) {
        if (Thread.currentThread() === thread) forMain.add(task) else task()
    }

    // Runs [task] where libinput is used: on the input thread (queued) or here.
    internal fun onInputThread(task: () -> Unit) {
        val other = inputLoop
        if (other != null && Thread.currentThread() !== thread) other.post(task) else task()
    }

    // Runs [task] on the input thread and waits for it (at most PAUSE_WAIT_MILLIS), doing what
    // it hands to this thread meanwhile; here at once without one.
    internal fun onInputThreadAndWait(task: () -> Unit) {
        val other = inputLoop
        if (other == null || Thread.currentThread() === thread) return task()
        val done = java.util.concurrent.CompletableFuture<Unit>()
        other.post {
            try {
                task()
            } finally {
                done.complete(Unit)
            }
        }
        val until = System.nanoTime() + PAUSE_WAIT_MILLIS * 1_000_000
        while (!done.isDone && System.nanoTime() < until) {
            forMain.runAll()
            try {
                done.get(5, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
            }
        }
        if (!done.isDone) inputDevices.log.warn("The input thread did not let go of the devices in time")
        forMain.runAll()
    }

    internal fun motion(time: Int, dx: Double, dy: Double, rawDx: Double, rawDy: Double) {
        if (Thread.currentThread() !== thread) return inputDevices.onPointerMotion(time, dx, dy, rawDx, rawDy)
        val early = inputDevices.earlyPointer
        if (early != null) {
            try {
                early.movedEarly(dx, dy)
            } catch (e: Exception) {
                Upcalls.report("early pointer", e)
            }
        }
        forMain.add(Motion(time, dx, dy, rawDx, rawDy, early))
    }

    // A relative movement waiting for the compositor's thread; the next ones still waiting
    // are summed into it (one handled, the cursor where all of them took it).
    internal inner class Motion(
        private var time: Int, private var dx: Double, private var dy: Double,
        private var rawDx: Double, private var rawDy: Double, private val early: EarlyPointer?,
    ) : MainQueue.Merging() {
        override fun take(next: MainQueue.Merging): Boolean {
            if (next !is Motion || next.early !== early) return false
            time = next.time
            dx += next.dx
            dy += next.dy
            rawDx += next.rawDx
            rawDy += next.rawDy
            return true
        }

        override fun run() {
            if (count > 1) inputDevices.log.trace { "$count pointer movements waited: handled as one" }
            // (Caught up even when handling the motion failed: else the early cursor never
            // took the compositor's place again — it jumped back after each warp.)
            try {
                inputDevices.onPointerMotion(time, dx, dy, rawDx, rawDy)
            } finally {
                if (early != null) {
                    try {
                        early.caughtUp(count)
                    } catch (e: Exception) {
                        Upcalls.report("early pointer", e)
                    }
                }
            }
        }
    }

    // An absolute movement (a tablet, a virtual machine's pointer) waiting: the next ones
    // still waiting replace its place (only the last place counts).
    internal inner class AbsoluteMotion(private var time: Int, private var x: Double, private var y: Double) : MainQueue.Merging() {
        override fun take(next: MainQueue.Merging): Boolean {
            if (next !is AbsoluteMotion) return false
            time = next.time
            x = next.x
            y = next.y
            return true
        }

        override fun run() = inputDevices.onPointerMotionAbsolute(time, x, y)
    }

    internal fun startThread() {
        val other = EventLoop()
        // (Its own stall reports would say "the main loop": it has none; errors are logged.)
        other.stallMillis = 0
        other.onError = { what, error -> inputDevices.log.error("Input thread: $what failed (it goes on): $error", error) }
        inputDevices.loop.remove(inputDevices.fd)
        other.add(inputDevices.fd) { dispatchBatch() }
        inputLoop = other
        val started = Thread.ofPlatform().name("caulis-input").daemon().unstarted {
            inputDevices.log.info { "Input is read on a thread of its own" }
            try {
                other.run()
            } catch (e: Throwable) {
                inputDevices.log.error("The input thread ended: $e", e)
            }
        }
        thread = started
        started.start()
        // (What came meanwhile: read there.)
        other.post { dispatchBatch() }
    }

    internal fun stopThread() {
        val running = thread ?: return
        val other = inputLoop ?: return
        // Until it ended: what it waits for here (a device being opened) is done meanwhile.
        // (Told again each time: it may not have begun to run when first told.)
        while (running.isAlive) {
            other.stop()
            other.post {} // (wakes it to notice)
            forMain.runAll()
            running.join(10)
        }
        thread = null
        inputLoop = null
        forMain.runAll()
        other.remove(inputDevices.fd)
        other.close()
        inputDevices.loop.add(inputDevices.fd) { inputDevices.dispatch() }
        inputDevices.log.info { "Input is read on the compositor's thread" }
        inputDevices.dispatch()
    }

    // Reads libinput on the input thread; then the cursor is shown where the pointer is now.
    private fun dispatchBatch() {
        inputDevices.dispatch()
        val early = inputDevices.earlyPointer ?: return
        try {
            early.showEarly()
        } catch (e: Exception) {
            Upcalls.report("early pointer", e)
        }
    }
}
