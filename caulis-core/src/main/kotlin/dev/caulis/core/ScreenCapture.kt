package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.protocol.Client
import dev.caulis.protocol.Display
import dev.caulis.protocol.extforeigntoplevellistv1.ExtForeignToplevelHandleV1
import dev.caulis.protocol.extimagecapturesourcev1.ExtForeignToplevelImageCaptureSourceManagerV1
import dev.caulis.protocol.extimagecapturesourcev1.ExtImageCaptureSourceV1
import dev.caulis.protocol.extimagecapturesourcev1.ExtOutputImageCaptureSourceManagerV1
import dev.caulis.protocol.extimagecopycapturev1.ExtImageCopyCaptureCursorSessionV1
import dev.caulis.protocol.extimagecopycapturev1.ExtImageCopyCaptureFrameV1
import dev.caulis.protocol.extimagecopycapturev1.ExtImageCopyCaptureManagerV1
import dev.caulis.protocol.extimagecopycapturev1.ExtImageCopyCaptureSessionV1
import dev.caulis.protocol.wayland.WlBuffer
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlPointer
import dev.caulis.protocol.wlrscreencopyunstablev1.ZwlrScreencopyFrameV1
import dev.caulis.protocol.wlrscreencopyunstablev1.ZwlrScreencopyManagerV1
import dev.caulis.render.Dmabuf
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Screenshots and screen sharing:
 *
 * - wlr-screencopy (grim, wf-recorder, OBS, xdg-desktop-portal-wlr for browsers): pictures
 *   of the screen or a part of it;
 * - ext-image-copy-capture: the screen or **one window** — also when it is hidden or on
 *   another workspace (it keeps drawing at full speed while it is shared).
 *
 * Pictures go into the program's memory, or straight into its GPU buffer (no copy through
 * the processor: cheap enough to record at the screen's refresh rate). A recording gets a
 * new picture only when something changed, and is told what did.
 *
 * [allowed] decides which programs may capture (all by default). While the screen is
 * locked, pictures show the lock, and windows are not given out at all.
 */
class ScreenCapture(
    display: Display,
    private val desktop: Desktop,
    private val renderer: Renderer,
    private val toplevels: ForeignToplevels? = null,
) {
    /** Which programs may take pictures of the screen or of windows. */
    var allowed: (Client) -> Boolean = { true }

    /**
     * True while the person is being asked whether this program may take pictures (a
     * permission's "ask"): its pictures wait for the answer, then [allowed] decides. Call
     * [answered] when the answer is there. Never by default.
     */
    var asking: (Client) -> Boolean = { false }

    /** An answer [asking] waited for is there: the pictures waiting are given (or refused) now. */
    fun answered() {
        desktop.requestFrame()
    }

    // A picture of [client] waits for the person's answer (true), or is refused (false: then it fails); null: go on.
    private fun waitOrRefuse(client: Client): Boolean? = when {
        asking(client) -> true
        !allowed(client) -> false.also { log.trace { "${client.name} may not take pictures (it was asked, and the answer was no)" } }
        else -> null
    }

    private val log = Logger("capture")

    // Pictures waiting for something to show (or for the next refresh), checked after every refresh.
    private val waiting = ArrayList<Pending>()

    private abstract inner class Pending {
        /** Tries to deliver; true when done (delivered or failed), false to keep waiting. */
        abstract fun service(): Boolean
    }

    init {
        desktop.frameListeners += ::serviceWaiting
        desktop.commitListeners += { surface -> windowSessions.forEach { it.committed(surface) } }

        display.addPrivilegedGlobal(ZwlrScreencopyManagerV1, WLR_VERSION, "screencopy") { manager ->
            val damageViews = HashMap<Pair<Boolean, Monitor>, DesktopView>() // per cursor choice and monitor: what this program got last
            manager.onDestroy { damageViews.values.forEach(desktop::removeCaptureView) }
            manager.requests = object : ZwlrScreencopyManagerV1.Requests {
                override fun captureOutput(resource: ZwlrScreencopyManagerV1, frame: ZwlrScreencopyFrameV1, overlayCursor: Int, output: WlOutput) {
                    val screen = Source.Screen(output.dataAs<Output>())
                    WlrFrame(frame, overlayCursor != 0, null, screen) { m -> damageViews.getOrPut((overlayCursor != 0) to m) { desktop.createCaptureView(m) } }.start()
                }

                override fun captureOutputRegion(
                    resource: ZwlrScreencopyManagerV1, frame: ZwlrScreencopyFrameV1, overlayCursor: Int, output: WlOutput,
                    x: Int, y: Int, width: Int, height: Int,
                ) {
                    val screen = Source.Screen(output.dataAs<Output>())
                    WlrFrame(frame, overlayCursor != 0, Rect(x, y, width, height), screen) { m -> damageViews.getOrPut((overlayCursor != 0) to m) { desktop.createCaptureView(m) } }.start()
                }

                override fun destroy(resource: ZwlrScreencopyManagerV1) {}
            }
        }

        display.addPrivilegedGlobal(ExtOutputImageCaptureSourceManagerV1, 1, "screencopy") { manager ->
            manager.requests = object : ExtOutputImageCaptureSourceManagerV1.Requests {
                override fun createSource(resource: ExtOutputImageCaptureSourceManagerV1, source: ExtImageCaptureSourceV1, output: WlOutput) =
                    source(source, Source.Screen(output.dataAs<Output>()))

                override fun destroy(resource: ExtOutputImageCaptureSourceManagerV1) {}
            }
        }
        display.addPrivilegedGlobal(ExtForeignToplevelImageCaptureSourceManagerV1, 1, "screencopy") { manager ->
            manager.requests = object : ExtForeignToplevelImageCaptureSourceManagerV1.Requests {
                override fun createSource(resource: ExtForeignToplevelImageCaptureSourceManagerV1, source: ExtImageCaptureSourceV1, toplevelHandle: ExtForeignToplevelHandleV1) =
                    source(source, toplevels?.windowOf(toplevelHandle)?.let(Source::OneWindow) ?: Source.Gone)

                override fun destroy(resource: ExtForeignToplevelImageCaptureSourceManagerV1) {}
            }
        }
        display.addPrivilegedGlobal(ExtImageCopyCaptureManagerV1, 1, "screencopy") { manager ->
            manager.requests = object : ExtImageCopyCaptureManagerV1.Requests {
                override fun createSession(resource: ExtImageCopyCaptureManagerV1, session: ExtImageCopyCaptureSessionV1, source: ExtImageCaptureSourceV1, options: Int) {
                    if (options and ExtImageCopyCaptureManagerV1.Options.PAINT_CURSORS.inv() != 0) {
                        resource.postError(ExtImageCopyCaptureManagerV1.Error.INVALID_OPTION, "unknown options $options")
                        return
                    }
                    Session(session, source.dataAs<Source>() ?: Source.Gone, options and ExtImageCopyCaptureManagerV1.Options.PAINT_CURSORS != 0)
                }

                override fun createPointerCursorSession(resource: ExtImageCopyCaptureManagerV1, session: ExtImageCopyCaptureCursorSessionV1, source: ExtImageCaptureSourceV1, pointer: WlPointer) {
                    // The cursor as a picture of its own is not offered: its session stops at once.
                    session.requests = object : ExtImageCopyCaptureCursorSessionV1.Requests {
                        override fun destroy(resource: ExtImageCopyCaptureCursorSessionV1) {}
                        override fun getCaptureSession(resource: ExtImageCopyCaptureCursorSessionV1, session: ExtImageCopyCaptureSessionV1) {
                            session.requests = object : ExtImageCopyCaptureSessionV1.Requests {
                                override fun createFrame(resource: ExtImageCopyCaptureSessionV1, frame: ExtImageCopyCaptureFrameV1) = deadFrame(frame)
                                override fun destroy(resource: ExtImageCopyCaptureSessionV1) {}
                            }
                            session.sendStopped()
                        }
                    }
                }

                override fun destroy(resource: ExtImageCopyCaptureManagerV1) {}
            }
        }
    }

    private fun source(resource: ExtImageCaptureSourceV1, source: Source) {
        resource.data = source
        resource.requests = object : ExtImageCaptureSourceV1.Requests {
            override fun destroy(resource: ExtImageCaptureSourceV1) {}
        }
    }

    // What a picture shows.
    private sealed class Source {
        /** A monitor (its output; null or unknown: the first). */
        class Screen(val output: Output?) : Source()
        class OneWindow(val window: Window) : Source()
        object Gone : Source() // a window that closed before it was asked for
    }

    // --- making pictures --------------------------------------------------------------------

    // The whole desktop, drawn for pictures (kept between pictures; the size follows the desktop).
    private var screen: GpuTarget? = null
    private var windowPicture: GpuTarget? = null
    private var readBack: Arena? = null
    private var readBackMemory: MemorySegment? = null

    // The monitor a picture of the screen shows.
    private fun monitorOf(screen: Source.Screen): Monitor = screen.output?.let(desktop::monitorOf) ?: desktop.monitors.first()

    private fun screenTarget(monitor: Monitor): GpuTarget {
        val current = screen
        if (current != null && current.width == monitor.pixelWidth && current.height == monitor.pixelHeight) return current
        current?.close()
        return renderer.createTarget(monitor.pixelWidth, monitor.pixelHeight).also { screen = it }
    }

    private fun windowTarget(width: Int, height: Int): GpuTarget {
        val current = windowPicture
        if (current != null && current.width == width && current.height == height) return current
        current?.close()
        return renderer.createTarget(width, height).also { windowPicture = it }
    }

    // Our own memory to read pictures into (never the program's: see GpuTexture.upload).
    private fun readBackMemory(bytes: Long): MemorySegment {
        val current = readBackMemory
        if (current != null && current.byteSize() >= bytes) return current
        readBack?.close()
        val arena = Arena.ofShared()
        readBack = arena
        return arena.allocate(bytes).also { readBackMemory = it }
    }

    /** The size a picture of [source] has, in pixels (null if there is nothing to show). */
    private fun sizeOf(source: Source): Pair<Int, Int>? = when (source) {
        is Source.Screen -> monitorOf(source).let { it.pixelWidth to it.pixelHeight }
        is Source.OneWindow -> source.window.takeIf { it in desktop.allWindows }?.let(desktop::windowPixelSize)
        Source.Gone -> null
    }

    // What changed (desktop units) as pixels of a picture of [part] (pixels) of [monitor].
    private fun changedPixels(changed: Rect, part: Rect, monitor: Monitor): Rect =
        monitor.toPixels(changed).intersect(part)?.translate(-part.x, -part.y) ?: Rect(0, 0, 1, 1)

    /**
     * Draws [source] ([part] of it, or all) into [buffer]. Returns null if done, or why the
     * buffer does not fit.
     */
    private fun copyInto(source: Source, part: Rect?, cursor: Boolean, buffer: WlBuffer): String? {
        val picture = when (source) {
            is Source.Screen -> monitorOf(source).let { monitor -> screenTarget(monitor).also { desktop.capture(it, cursor, monitor) } }
            is Source.OneWindow -> {
                val (w, h) = sizeOf(source) ?: return "the window is gone"
                windowTarget(w, h).also { desktop.captureWindow(source.window, it) }
            }
            Source.Gone -> return "the window is gone"
        }
        val region = part ?: Rect(0, 0, picture.width, picture.height)
        val shm = buffer.dataAs<ShmBuffer>()
        val dmabuf = buffer.dataAs<DmabufBuffer>()
        when {
            shm != null -> {
                if (shm.width != region.width || shm.height != region.height) return "the buffer is ${shm.width}x${shm.height}, not ${region.width}x${region.height}"
                if (shm.format != SHM_ARGB8888 && shm.format != SHM_XRGB8888) return "the buffer format ${shm.format} is not ARGB8888 or XRGB8888"
                if (shm.stride < region.width * 4) return "the buffer's rows are too short"
                // (Read back into our memory at the buffer's stride: rows far longer than a picture
                // needs — up to the pool's 2 GB — would keep that much of ours for good.)
                if (shm.stride > region.width * 4 + MAX_STRIDE_PADDING) return "the buffer's rows are ${shm.stride} bytes, far more than ${region.width * 4}"
                if (!shm.pool.writable) return "the buffer's memory is read-only"
                val bytes = shm.stride.toLong() * (region.height - 1) + region.width * 4L
                val memory = readBackMemory(bytes)
                picture.readPixels(memory, shm.stride, region)
                try {
                    shm.pool.write { pool ->
                        if (shm.offset + bytes > pool.byteSize()) error("the buffer lies outside its memory")
                        MemorySegment.copy(memory, 0, pool, shm.offset, bytes)
                    }
                } catch (e: Exception) {
                    return "its memory cannot be written: ${e.message}"
                }
            }
            dmabuf != null -> {
                if (dmabuf.width != region.width || dmabuf.height != region.height) return "the buffer is ${dmabuf.width}x${dmabuf.height}, not ${region.width}x${region.height}"
                val target = dmabuf.attachment(CaptureTarget::class) { CaptureTarget(renderer.importDmabufTarget(dmabuf.dmabuf)) }.target
                    ?: return "the GPU cannot draw into this buffer"
                renderer.copy(picture, region, target)
                renderer.finish() // the program may read it as soon as it hears "ready"
            }
            else -> return "not a buffer we can write into"
        }
        return null
    }

    // A program's GPU buffer prepared for drawing (kept with the buffer, freed with it).
    private class CaptureTarget(val target: GpuTarget?) : AutoCloseable {
        override fun close() {
            target?.close()
        }
    }

    private fun serviceWaiting() {
        if (waiting.isEmpty()) return
        // (A copy: delivering one may end a recording, which takes its frame out of the list.)
        val done = waiting.toList().filter { pending ->
            try {
                pending.service()
            } catch (e: Exception) {
                log.error("A picture for a screen recording failed", e)
                true
            }
        }
        waiting.removeAll(done.toSet())
    }

    // The time a picture was taken, as the protocols want it (monotonic clock).
    private fun now(): Triple<Int, Int, Int> {
        val nanos = System.nanoTime()
        val seconds = nanos / 1_000_000_000
        return Triple((seconds ushr 32).toInt(), seconds.toInt(), (nanos % 1_000_000_000).toInt())
    }

    // --- wlr-screencopy ------------------------------------------------------------------------

    private inner class WlrFrame(
        val resource: ZwlrScreencopyFrameV1,
        val cursor: Boolean,
        val part: Rect?,
        val screen: Source.Screen,
        val damageView: (Monitor) -> DesktopView,
    ) : Pending() {
        private val monitor = monitorOf(screen)
        private var buffer: WlBuffer? = null
        private var withDamage = false
        private var used = false
        // The part asked for is in desktop units from the monitor's corner; the picture is in pixels.
        private val region: Rect = Rect(0, 0, monitor.pixelWidth, monitor.pixelHeight).let { all ->
            part?.let { monitor.toPixels(it.translate(monitor.area.x, monitor.area.y)).intersect(all) ?: Rect(0, 0, 0, 0) } ?: all
        }

        fun start() {
            resource.data = this
            resource.requests = object : ZwlrScreencopyFrameV1.Requests {
                override fun copy(resource: ZwlrScreencopyFrameV1, buffer: WlBuffer) = copy(buffer, damage = false)
                override fun copyWithDamage(resource: ZwlrScreencopyFrameV1, buffer: WlBuffer) = copy(buffer, damage = true)
                override fun destroy(resource: ZwlrScreencopyFrameV1) {}
            }
            resource.onDestroy { waiting.remove(this) }
            if (!allowed(resource.client) || region.isEmpty) {
                log.info { "${resource.client.name} may not take a picture of the screen${if (region.isEmpty) " (an empty part)" else ""}" }
                resource.sendFailed()
                return
            }
            // What the picture can go into: the program's memory, or (version 3) a GPU buffer.
            resource.sendBuffer(SHM_XRGB8888, region.width, region.height, region.width * 4)
            if (resource.version >= 3) {
                if (renderer.renderNode != null) resource.sendLinuxDmabuf(Dmabuf.XRGB8888, region.width, region.height)
                resource.sendBufferDone()
            }
        }

        private fun copy(buffer: WlBuffer, damage: Boolean) {
            if (used) {
                resource.postError(ZwlrScreencopyFrameV1.Error.ALREADY_USED, "this frame was already copied")
                return
            }
            used = true
            this.buffer = buffer
            withDamage = damage
            log.trace { "${resource.client.name} wants a picture of the screen${if (part != null) " part $region" else ""}${if (damage) " when something changes" else ""}" }
            waiting += this
            desktop.requestFrame()
        }

        override fun service(): Boolean {
            if (!resource.isAlive) return true
            val target = buffer ?: return true
            when (waitOrRefuse(resource.client)) {
                true -> return false
                false -> {
                    resource.sendFailed()
                    return true
                }
                null -> {}
            }
            // A recording waits until something changed; a screenshot takes the picture now.
            val changed = desktop.captureChanges(damageView(monitor), cursor)
            if (withDamage && changed == null) return false
            val problem = copyInto(screen, region, cursor, target)
            if (problem != null) {
                log.warn("Cannot give ${resource.client.name} a picture: $problem")
                resource.sendFailed()
                return true
            }
            log.trace { "Gave ${resource.client.name} a picture of the screen" }
            resource.sendFlags(0)
            if (withDamage) {
                val damage = changed?.let { changedPixels(it, region, monitor) } ?: Rect(0, 0, region.width, region.height)
                resource.sendDamage(damage.x, damage.y, damage.width, damage.height)
            }
            val (hi, lo, nanos) = now()
            resource.sendReady(hi, lo, nanos)
            return true
        }
    }

    // --- ext-image-copy-capture ----------------------------------------------------------------

    private val windowSessions = ArrayList<Session>()

    private inner class Session(val resource: ExtImageCopyCaptureSessionV1, val source: Source, val cursor: Boolean) {
        var frame: ExtFrame? = null
        private val view = (source as? Source.Screen)?.let { desktop.createCaptureView(monitorOf(it)) }
        private var keepRunning: AutoCloseable? = null
        private var size: Pair<Int, Int>? = null
        private var stopped = false

        // A window: something of it changed since its last picture (the first picture: yes).
        var windowChanged = true

        init {
            resource.data = this
            resource.requests = object : ExtImageCopyCaptureSessionV1.Requests {
                override fun createFrame(resource: ExtImageCopyCaptureSessionV1, frame: ExtImageCopyCaptureFrameV1) {
                    if (this@Session.frame?.resource?.isAlive == true) {
                        resource.postError(ExtImageCopyCaptureSessionV1.Error.DUPLICATE_FRAME, "the previous frame is not finished")
                        return
                    }
                    if (stopped) return deadFrame(frame)
                    this@Session.frame = ExtFrame(frame, this@Session)
                }

                override fun destroy(resource: ExtImageCopyCaptureSessionV1) {}
            }
            resource.onDestroy { end() }
            when {
                !allowed(resource.client) -> {
                    log.info { "${resource.client.name} may not record the screen" }
                    stop()
                }
                sizeOf(source) == null -> stop()
                else -> {
                    log.info {
                        "${resource.client.name} starts recording " +
                            if (source is Source.OneWindow) "the window ${source.window}" else "the screen"
                    }
                    if (source is Source.OneWindow) {
                        // A shared window must not freeze while it is hidden.
                        keepRunning = desktop.keepUpdating(source.window.surface)
                        windowSessions += this
                    }
                    sendConstraints()
                }
            }
        }

        // What buffers fit: the size, the memory formats and the GPU buffer formats.
        fun sendConstraints() {
            val current = sizeOf(source) ?: return stop()
            size = current
            resource.sendBufferSize(current.first, current.second)
            resource.sendShmFormat(SHM_ARGB8888)
            resource.sendShmFormat(SHM_XRGB8888)
            renderer.renderNode?.let { node ->
                runCatching { Files.getAttribute(node, "unix:rdev") as Long }.getOrNull()?.let { device ->
                    resource.sendDmabufDevice(longs(listOf(device)))
                    for (format in listOf(Dmabuf.XRGB8888, Dmabuf.ARGB8888)) {
                        val modifiers = renderer.dmabufFormats.filter { it.format == format }.map { it.modifier }
                        if (modifiers.isNotEmpty()) resource.sendDmabufFormat(format, longs(modifiers))
                    }
                }
            }
            resource.sendDone()
        }

        /** The size still fits (a window may have been resized: then new constraints). */
        fun sizeStillFits(): Boolean {
            val current = sizeOf(source) ?: run {
                stop()
                return false
            }
            if (current == size) return true
            sendConstraints()
            return false
        }

        fun committed(surface: Surface) {
            val window = (source as? Source.OneWindow)?.window ?: return
            var root = surface
            while (true) root = root.subsurface?.parent ?: break
            if (root === window.surface) windowChanged = true
        }

        /** What changed since the last picture, or null if nothing did (screen: a rectangle). */
        fun changes(): Rect? = when (source) {
            is Source.Screen -> view!!.monitor.let { monitor -> desktop.captureChanges(view, cursor)?.let { changedPixels(it, Rect(0, 0, monitor.pixelWidth, monitor.pixelHeight), monitor) } }
            is Source.OneWindow -> if (windowChanged && !desktop.locked) {
                windowChanged = false
                sizeOf(source)?.let { Rect(0, 0, it.first, it.second) }
            } else {
                null
            }
            Source.Gone -> null
        }

        fun stop() {
            if (stopped) return
            stopped = true
            frame?.fail(ExtImageCopyCaptureFrameV1.FailureReason.STOPPED)
            if (resource.isAlive) resource.sendStopped()
            end()
        }

        private fun end() {
            keepRunning?.close()
            keepRunning = null
            windowSessions -= this
            view?.let(desktop::removeCaptureView)
        }
    }

    private inner class ExtFrame(val resource: ExtImageCopyCaptureFrameV1, val session: Session) : Pending() {
        private var buffer: WlBuffer? = null
        private var captured = false
        private var failed = false

        init {
            resource.requests = object : ExtImageCopyCaptureFrameV1.Requests {
                override fun attachBuffer(resource: ExtImageCopyCaptureFrameV1, buffer: WlBuffer) {
                    this@ExtFrame.buffer = buffer
                }

                override fun damageBuffer(resource: ExtImageCopyCaptureFrameV1, x: Int, y: Int, width: Int, height: Int) {
                    if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                        resource.postError(ExtImageCopyCaptureFrameV1.Error.INVALID_BUFFER_DAMAGE, "invalid damage $x,$y ${width}x$height")
                    }
                    // We always fill the whole buffer, so what the program says is stale does not matter.
                }

                override fun capture(resource: ExtImageCopyCaptureFrameV1) {
                    when {
                        captured -> resource.postError(ExtImageCopyCaptureFrameV1.Error.ALREADY_CAPTURED, "this frame was already captured")
                        buffer == null -> resource.postError(ExtImageCopyCaptureFrameV1.Error.NO_BUFFER, "no buffer attached")
                        else -> {
                            captured = true
                            waiting += this@ExtFrame
                            desktop.requestFrame()
                        }
                    }
                }

                override fun destroy(resource: ExtImageCopyCaptureFrameV1) {}
            }
            resource.onDestroy {
                waiting.remove(this)
                if (session.frame === this) session.frame = null
            }
        }

        override fun service(): Boolean {
            if (!resource.isAlive || failed) return true
            when (waitOrRefuse(resource.client)) {
                true -> return false
                false -> {
                    fail(ExtImageCopyCaptureFrameV1.FailureReason.UNKNOWN)
                    return true
                }
                null -> {}
            }
            if (!session.sizeStillFits()) {
                // (If the recording stopped, this frame already failed with "stopped".)
                fail(ExtImageCopyCaptureFrameV1.FailureReason.BUFFER_CONSTRAINTS)
                return true
            }
            val changed = session.changes() ?: return false
            val problem = copyInto(session.source, null, session.cursor, buffer!!)
            if (problem != null) {
                log.warn("Cannot give ${resource.client.name} a picture: $problem")
                fail(ExtImageCopyCaptureFrameV1.FailureReason.BUFFER_CONSTRAINTS)
                return true
            }
            resource.sendTransform(WlOutput.Transform.NORMAL)
            resource.sendDamage(changed.x, changed.y, changed.width, changed.height)
            val (hi, lo, nanos) = now()
            resource.sendPresentationTime(hi, lo, nanos)
            resource.sendReady()
            session.frame = null
            return true
        }

        fun fail(reason: Int) {
            waiting.remove(this)
            // Once only: a recording that stops while this frame is delivered fails it already.
            if (failed) return
            failed = true
            if (resource.isAlive) resource.sendFailed(reason)
            if (session.frame === this) session.frame = null
        }
    }

    // A frame of a session that cannot give pictures: it fails as soon as it is used.
    private fun deadFrame(frame: ExtImageCopyCaptureFrameV1) {
        frame.requests = object : ExtImageCopyCaptureFrameV1.Requests {
            override fun attachBuffer(resource: ExtImageCopyCaptureFrameV1, buffer: WlBuffer) {}
            override fun damageBuffer(resource: ExtImageCopyCaptureFrameV1, x: Int, y: Int, width: Int, height: Int) {}
            override fun capture(resource: ExtImageCopyCaptureFrameV1) = resource.sendFailed(ExtImageCopyCaptureFrameV1.FailureReason.STOPPED)
            override fun destroy(resource: ExtImageCopyCaptureFrameV1) {}
        }
    }

    private fun longs(values: List<Long>): ByteArray {
        val bytes = ByteBuffer.allocate(values.size * 8).order(ByteOrder.nativeOrder())
        values.forEach { bytes.putLong(it) }
        return bytes.array()
    }

    companion object {
        const val WLR_VERSION = 3
        private const val SHM_ARGB8888 = 0
        private const val SHM_XRGB8888 = 1
        // How much longer than its pixels a row of a client's buffer may be (alignment).
        private const val MAX_STRIDE_PADDING = 4096
    }
}
