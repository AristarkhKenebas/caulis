package dev.caulis.core.nested

import dev.caulis.core.input.Gesture
import dev.caulis.event.EventLoop
import dev.caulis.ffi.SharedMemory
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.linuxdmabufv1.ZwpLinuxDmabufV1
import dev.caulis.protocol.client.pointergesturesunstablev1.ZwpPointerGestureHoldV1
import dev.caulis.protocol.client.pointergesturesunstablev1.ZwpPointerGesturePinchV1
import dev.caulis.protocol.client.pointergesturesunstablev1.ZwpPointerGestureSwipeV1
import dev.caulis.protocol.client.pointergesturesunstablev1.ZwpPointerGesturesV1
import dev.caulis.protocol.client.wayland.WlBuffer
import dev.caulis.protocol.client.wayland.WlCallback
import dev.caulis.protocol.client.wayland.WlCompositor
import dev.caulis.protocol.client.wayland.WlKeyboard
import dev.caulis.protocol.client.wayland.WlPointer
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.protocol.client.wayland.WlSeat
import dev.caulis.protocol.client.wayland.WlShm
import dev.caulis.protocol.client.wayland.WlShmPool
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.protocol.client.xdgshell.XdgSurface
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import dev.caulis.protocol.client.xdgshell.XdgWmBase
import dev.caulis.render.GpuBuffer
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer

/**
 * Caulis's window inside an existing Wayland session (the "nested" backend).
 *
 * Frames are drawn with [renderer] and handed to the outer compositor without copying
 * (GPU buffers through linux-dmabuf) when it supports that on the same GPU; otherwise
 * they are copied through shared memory. Frames are paced by the outer compositor:
 * [onFrame] is called when it is a good moment to draw the next one.
 */
class NestedWindow(
    loop: EventLoop,
    private val renderer: Renderer,
    title: String = "Caulis",
    /** Lets the outer session recognise the window (icon, rules). */
    appId: String = "dev.caulis.Nested",
    /** Socket of the outer session; null = the one in WAYLAND_DISPLAY. */
    outerSocket: java.nio.file.Path? = null,
) : AutoCloseable {

    val connection: Connection = Connection.connect(outerSocket)

    // Globals of the outer compositor.
    private val compositor: WlCompositor
    private val shm: WlShm
    private val hostDmabuf: HostDmabuf?

    /** True if frames go to the outer compositor as GPU buffers (no copying). */
    val sharesGpuBuffers: Boolean get() = hostDmabuf != null
    private val wmBase: XdgWmBase

    /** The outer session's seat (keyboard and mouse), if it has one. */
    val seat: WlSeat?

    private val surface: WlSurface
    private val xdgSurface: XdgSurface
    private val toplevel: XdgToplevel

    /** Size of the window in pixels; changes when the user resizes it. */
    var width = DEFAULT_WIDTH
        private set
    var height = DEFAULT_HEIGHT
        private set

    /** Called when the window got a new size (before the next [onFrame]). */
    var onResize: (width: Int, height: Int) -> Unit = { _, _ -> }

    /** Called when the outer compositor is ready for a new frame: draw it and call [present]. */
    var onFrame: () -> Unit = {}

    /**
     * Called when the outer compositor is ready for the next frame, which roughly means the
     * previous one is on screen (`nanos`, monotonic clock). Only approximate: the outer
     * compositor does not say exactly when it showed our picture.
     */
    var onPresented: (nanos: Long) -> Unit = {}

    /** Called when the user closes the window. */
    var onClose: () -> Unit = {}

    /** Input from the outer session, in window pixels. Times are in milliseconds. */
    var onPointerMotion: (timeMillis: Int, x: Double, y: Double) -> Unit = { _, _, _ -> }
    var onPointerButton: (timeMillis: Int, button: Int, pressed: Boolean) -> Unit = { _, _, _ -> }
    var onPointerAxis: (timeMillis: Int, vertical: Boolean, value: Double, value120: Int) -> Unit = { _, _, _, _ -> }
    var onKey: (timeMillis: Int, evdevCode: Int, pressed: Boolean) -> Unit = { _, _, _ -> }

    /** The outer session took the keyboard away (all keys count as released). */
    var onKeyboardLeave: () -> Unit = {}

    /** Touchpad gestures from the outer session (if it passes them on). */
    var onGesture: (Gesture) -> Unit = {}

    // The outer session's gesture support (bound before the pointer appears, see init).
    private var hostGestures: ZwpPointerGesturesV1? = null

    // Gesture handlers. Declared before init: the pointer (and its gestures) may appear during init.
    private val swipeEvents = object : ZwpPointerGestureSwipeV1.Events {
        override fun begin(proxy: ZwpPointerGestureSwipeV1, serial: Int, time: Int, surface: WlSurface, fingers: Int) = onGesture(Gesture.SwipeBegin(time, fingers))
        override fun update(proxy: ZwpPointerGestureSwipeV1, time: Int, dx: Double, dy: Double) = onGesture(Gesture.SwipeUpdate(time, dx, dy))
        override fun end(proxy: ZwpPointerGestureSwipeV1, serial: Int, time: Int, cancelled: Int) = onGesture(Gesture.SwipeEnd(time, cancelled != 0))
    }
    private val pinchEvents = object : ZwpPointerGesturePinchV1.Events {
        override fun begin(proxy: ZwpPointerGesturePinchV1, serial: Int, time: Int, surface: WlSurface, fingers: Int) = onGesture(Gesture.PinchBegin(time, fingers))
        override fun update(proxy: ZwpPointerGesturePinchV1, time: Int, dx: Double, dy: Double, scale: Double, rotation: Double) =
            onGesture(Gesture.PinchUpdate(time, dx, dy, scale, rotation))
        override fun end(proxy: ZwpPointerGesturePinchV1, serial: Int, time: Int, cancelled: Int) = onGesture(Gesture.PinchEnd(time, cancelled != 0))
    }
    private val holdEvents = object : ZwpPointerGestureHoldV1.Events {
        override fun begin(proxy: ZwpPointerGestureHoldV1, serial: Int, time: Int, surface: WlSurface, fingers: Int) = onGesture(Gesture.HoldBegin(time, fingers))
        override fun end(proxy: ZwpPointerGestureHoldV1, serial: Int, time: Int, cancelled: Int) = onGesture(Gesture.HoldEnd(time, cancelled != 0))
    }

    private var configured = false
    private var frameRequested = false
    private val buffers = ArrayList<Buffer>()

    // Input handlers. Declared before init on purpose: Kotlin initializes properties in
    // order, and the outer compositor may announce its keyboard during init already.
    private var pendingValue120 = intArrayOf(0, 0)

    private val keyboardEvents = object : WlKeyboard.Events {
        // We use our own keymap; the outer one is not needed.
        override fun keymap(proxy: WlKeyboard, format: Int, fd: dev.caulis.ffi.Fd, size: Int) = fd.close()

        override fun leave(proxy: WlKeyboard, serial: Int, surface: WlSurface) = onKeyboardLeave()

        override fun key(proxy: WlKeyboard, serial: Int, time: Int, key: Int, state: Int) =
            onKey(time, key, state == WlKeyboard.KeyState.PRESSED)
    }

    private val pointerEvents = object : WlPointer.Events {
        override fun enter(proxy: WlPointer, serial: Int, surface: WlSurface, surfaceX: Double, surfaceY: Double) {
            // Hide the outer cursor over our window: Caulis draws its own.
            proxy.setCursor(serial, null, 0, 0)
            onPointerMotion(0, surfaceX, surfaceY)
        }

        override fun motion(proxy: WlPointer, time: Int, surfaceX: Double, surfaceY: Double) =
            onPointerMotion(time, surfaceX, surfaceY)

        override fun button(proxy: WlPointer, serial: Int, time: Int, button: Int, state: Int) =
            onPointerButton(time, button, state == WlPointer.ButtonState.PRESSED)

        override fun axisValue120(proxy: WlPointer, axis: Int, value120: Int) {
            pendingValue120[axis.coerceIn(0, 1)] += value120
        }

        override fun axis(proxy: WlPointer, time: Int, axis: Int, value: Double) {
            val index = axis.coerceIn(0, 1)
            onPointerAxis(time, axis == WlPointer.Axis.VERTICAL_SCROLL, value, pendingValue120[index])
            pendingValue120[index] = 0
        }
    }

    init {
        var compositorName = 0
        var shmName = 0
        var wmBaseName = 0
        var seatName = 0
        var seatVersion = 0
        var dmabufName = 0
        var dmabufVersion = 0
        var gesturesName = 0
        var gesturesVersion = 0
        var inhibitName = 0
        val registry = connection.display.getRegistry()
        registry.events = object : WlRegistry.Events {
            override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                when (`interface`) {
                    "wl_compositor" -> compositorName = name
                    "wl_shm" -> shmName = name
                    "xdg_wm_base" -> wmBaseName = name
                    "zwp_linux_dmabuf_v1" -> {
                        dmabufName = name
                        dmabufVersion = versionArg
                    }
                    "zwp_pointer_gestures_v1" -> {
                        gesturesName = name
                        gesturesVersion = versionArg
                    }
                    "zwp_keyboard_shortcuts_inhibit_manager_v1" -> inhibitName = name
                    "wl_seat" -> if (seatName == 0) {
                        seatName = name
                        seatVersion = versionArg
                    }
                }
            }
        }
        connection.roundtrip()
        check(compositorName != 0 && shmName != 0 && wmBaseName != 0) {
            "The outer compositor lacks wl_compositor, wl_shm or xdg_wm_base"
        }

        compositor = registry.bind(compositorName, WlCompositor, 4)
        shm = registry.bind(shmName, WlShm, 1)
        wmBase = registry.bind(wmBaseName, XdgWmBase, 1)
        // Version 8 adds high-resolution scroll steps (axis_value120).
        if (gesturesName != 0) hostGestures = registry.bind(gesturesName, ZwpPointerGesturesV1, minOf(gesturesVersion, 3))
        seat = if (seatName != 0) registry.bind(seatName, WlSeat, minOf(seatVersion, 8)) else null
        seat?.let(::listenToInput)
        hostDmabuf = if (dmabufName != 0 && dmabufVersion >= 3) {
            val dmabuf = registry.bind(dmabufName, ZwpLinuxDmabufV1, minOf(dmabufVersion, 4))
            HostDmabuf.connect(connection, dmabuf, renderer).also { if (it == null) dmabuf.destroy() }
        } else {
            null
        }

        // The outer compositor checks we are alive; answer or it marks us "not responding".
        wmBase.events = object : XdgWmBase.Events {
            override fun ping(proxy: XdgWmBase, serial: Int) = proxy.pong(serial)
        }

        surface = compositor.createSurface()
        xdgSurface = wmBase.getXdgSurface(surface)
        toplevel = xdgSurface.getToplevel()
        toplevel.setTitle(title)
        toplevel.setAppId(appId)
        // Every key comes to us while we have the keyboard — Super too — if the outer session
        // lets windows keep its shortcuts (it offers a way back, e.g. Super+Escape).
        val outerSeat = seat
        if (inhibitName != 0 && outerSeat != null) {
            registry.bind(inhibitName, dev.caulis.protocol.client.keyboardshortcutsinhibitunstablev1.ZwpKeyboardShortcutsInhibitManagerV1, 1)
                .inhibitShortcuts(surface, outerSeat)
        }

        toplevel.events = object : XdgToplevel.Events {
            override fun configure(proxy: XdgToplevel, width: Int, height: Int, states: ByteArray) {
                // 0 means "choose yourself".
                if (width > 0 && height > 0 && (width != this@NestedWindow.width || height != this@NestedWindow.height)) {
                    this@NestedWindow.width = width
                    this@NestedWindow.height = height
                    onResize(width, height)
                }
            }

            override fun close(proxy: XdgToplevel) = onClose()
        }
        xdgSurface.events = object : XdgSurface.Events {
            override fun configure(proxy: XdgSurface, serial: Int) {
                proxy.ackConfigure(serial)
                if (!configured) {
                    configured = true
                    onFrame() // the first frame
                }
            }
        }
        // The first commit without a buffer asks the outer compositor for a size.
        surface.commit()
        connection.attach(loop)
    }


    // Asks for the outer keyboard and pointer when the seat has them.
    private fun listenToInput(seat: WlSeat) {
        var keyboard: WlKeyboard? = null
        var pointer: WlPointer? = null
        seat.events = object : WlSeat.Events {
            override fun capabilities(proxy: WlSeat, capabilities: Int) {
                if (capabilities and WlSeat.Capability.KEYBOARD != 0 && keyboard == null) {
                    keyboard = proxy.getKeyboard().also { it.events = keyboardEvents }
                }
                if (capabilities and WlSeat.Capability.POINTER != 0 && pointer == null) {
                    val created = proxy.getPointer().also { it.events = pointerEvents }
                    pointer = created
                    // Touchpad gestures come through the pointer.
                    hostGestures?.let { gestures ->
                        gestures.getSwipeGesture(created).events = swipeEvents
                        gestures.getPinchGesture(created).events = pinchEvents
                        if (gestures.version >= 3) gestures.getHoldGesture(created).events = holdEvents
                    }
                }
            }
        }
    }


    /**
     * Shows a new frame: [draw] draws it into the given target (the window's size); the
     * number is how many frames old the target's picture is (1 = previous frame, 0 = new:
     * draw everything). Afterwards [onFrame] is called again when the outer compositor
     * wants the next frame.
     */
    fun present(draw: (target: GpuTarget, age: Int) -> Unit) {
        val buffer = freeBuffer()
        frameNumber++
        buffer.render(draw)
        surface.attach(buffer.wlBuffer, 0, 0)
        surface.damageBuffer(0, 0, width, height)
        buffer.busy = true
        presentedPending = true
        if (!frameRequested) requestFrameCallback()
        surface.commit()
    }

    // A picture was committed and not yet reported as shown.
    private var presentedPending = false

    private fun requestFrameCallback() {
        frameRequested = true
        surface.frame().events = object : WlCallback.Events {
            override fun done(proxy: WlCallback, callbackData: Int) {
                frameRequested = false
                if (presentedPending) {
                    presentedPending = false
                    onPresented(System.nanoTime())
                }
                onFrame()
            }
        }
    }

    private var frameNumber = 0L

    /**
     * Asks for an [onFrame] call at the outer compositor's next refresh, e.g. because
     * something changed while no frame was being drawn. Cheap to call often.
     */
    fun requestFrame() {
        if (!configured || frameRequested) return
        requestFrameCallback()
        surface.commit()
    }

    // A buffer the outer compositor is not reading right now, of the current size.
    private fun freeBuffer(): Buffer {
        buffers.removeAll { buffer ->
            val outdated = buffer.width != width || buffer.height != height
            if (outdated && !buffer.busy) buffer.close()
            outdated && !buffer.busy
        }
        buffers.firstOrNull { !it.busy && it.width == width && it.height == height }?.let { return it }
        val host = hostDmabuf
        val buffer = if (host != null) GpuFrameBuffer(host, width, height) else ShmFrameBuffer(width, height)
        return buffer.also { buffers += it }
    }

    /** One picture the outer compositor can show. */
    private abstract inner class Buffer(val width: Int, val height: Int) : AutoCloseable {
        abstract val wlBuffer: WlBuffer

        /** True while the outer compositor may read it. */
        var busy = false

        /** Draws the frame into this buffer. */
        abstract fun render(draw: (GpuTarget, Int) -> Unit)

        // The frame this buffer was last drawn in (0 = never).
        protected var drawnInFrame = 0L

        // How many frames old this buffer's picture is (0 = never drawn).
        protected fun takeAge(): Int {
            val age = if (drawnInFrame == 0L) 0 else (frameNumber - drawnInFrame).toInt()
            drawnInFrame = frameNumber
            return age
        }

        // The outer compositor is done reading; the buffer may be reused.
        protected fun listenForRelease() {
            wlBuffer.events = object : WlBuffer.Events {
                override fun release(proxy: WlBuffer) {
                    busy = false
                }
            }
        }
    }

    /** GPU memory the outer compositor reads directly: nothing is copied. */
    private inner class GpuFrameBuffer(host: HostDmabuf, width: Int, height: Int) : Buffer(width, height) {
        private val gpuBuffer: GpuBuffer
        override val wlBuffer: WlBuffer

        init {
            val (gpu, wl) = host.createBuffer(width, height)
            gpuBuffer = gpu
            wlBuffer = wl
            listenForRelease()
        }

        override fun render(draw: (GpuTarget, Int) -> Unit) = draw(gpuBuffer, takeAge())

        override fun close() {
            wlBuffer.destroy()
            gpuBuffer.close()
        }
    }

    /** Shared memory: each frame is drawn on the GPU, then copied here (the fallback). */
    private inner class ShmFrameBuffer(width: Int, height: Int) : Buffer(width, height) {
        private val stride = width * 4
        private val memory = SharedMemory.create("caulis-nested", stride.toLong() * height)
        private val pool: WlShmPool = shm.createPool(memory.fd, (stride.toLong() * height).toInt())
        override val wlBuffer: WlBuffer = pool.createBuffer(0, width, height, stride, WlShm.Format.XRGB8888)

        init {
            listenForRelease()
        }

        override fun render(draw: (GpuTarget, Int) -> Unit) {
            // All shared-memory frames are drawn in one target, which therefore always
            // holds the previous frame (unless it was just created).
            val fresh = shmTarget?.let { it.width != width || it.height != height } ?: true
            val target = drawingTarget()
            draw(target, if (fresh) 0 else 1)
            memory.write { pixels -> target.readPixels(pixels, stride) }
        }

        override fun close() {
            wlBuffer.destroy()
            pool.destroy()
            memory.close()
        }
    }

    // Where shared-memory frames are drawn before copying; one, of the current size.
    private var shmTarget: GpuTarget? = null

    private fun drawingTarget(): GpuTarget {
        shmTarget?.takeIf { it.width == width && it.height == height }?.let { return it }
        shmTarget?.close()
        return renderer.createTarget(width, height).also { shmTarget = it }
    }

    override fun close() {
        buffers.forEach { it.close() }
        shmTarget?.close()
        toplevel.destroy()
        xdgSurface.destroy()
        surface.destroy()
        connection.flush()
        connection.close()
    }

    companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 800
    }
}
