package dev.caulis.core.drm

import dev.caulis.core.session.Session
import dev.caulis.event.EventLoop
import dev.caulis.ffi.Upcalls
import dev.caulis.ffi.c.drm.Drm
import dev.caulis.ffi.c.drm._drmEventContext
import dev.caulis.ffi.c.drm._drmModeObjectProperties
import dev.caulis.ffi.c.drm._drmModeProperty
import dev.caulis.ffi.c.drm._drmModePropertyBlob
import dev.caulis.render.Dmabuf
import dev.caulis.render.GpuBuffer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Path

/**
 * One GPU's display side (a DRM "card" device, e.g. /dev/dri/card0), opened through the
 * [session]. It talks to the kernel with atomic mode setting: every change to the screens
 * is one all-or-nothing request that can be tested first, so a monitor never ends up
 * half-configured.
 */
class DrmDevice(private val loop: EventLoop, private val session: Session, val path: Path) : AutoCloseable {

    private val device: Session.Device = session.openDevice(path)
    private val log = dev.caulis.log.Logger("drm")

    /** The raw descriptor (the renderer draws on the same device). */
    val fd: Int get() = device.fd.value

    private val arena = Arena.ofShared()

    /** Flip times are the monotonic clock's (else they are not exact enough to report as the hardware's). */
    var monotonicTimestamps = false
        private set

    // The kernel takes buffer layouts (modifiers) with framebuffers.
    private var modifiersSupported = false

    /** A capability of the display (drmGetCap), or -1 if it does not say. */
    internal fun capability(capability: Int): Long = cap(capability)

    private fun cap(capability: Int): Long = Arena.ofConfined().use { temp ->
        val value = temp.allocate(JAVA_LONG)
        if (Drm.drmGetCap(fd, capability.toLong(), value) != 0) -1L else value.get(JAVA_LONG, 0)
    }
    private val flipHandlers = HashMap<Int, (nanos: Long, sequence: Long, serial: Long) -> Unit>()
    private val eventContext: MemorySegment

    init {
        try {
            // Atomic mode setting needs "universal planes" (all layers of a screen as objects).
            check(Drm.drmSetClientCap(fd, Drm.DRM_CLIENT_CAP_UNIVERSAL_PLANES().toLong(), 1L) == 0) {
                "$path does not support universal planes"
            }
            check(Drm.drmSetClientCap(fd, Drm.DRM_CLIENT_CAP_ATOMIC().toLong(), 1L) == 0) {
                "$path does not support atomic mode setting (the GPU driver is too old)"
            }
        } catch (e: Exception) {
            session.closeDevice(device)
            throw e
        }

        monotonicTimestamps = cap(Drm.DRM_CAP_TIMESTAMP_MONOTONIC()) == 1L
        modifiersSupported = cap(Drm.DRM_CAP_ADDFB2_MODIFIERS()) == 1L

        // "A new picture is on screen" events, per CRTC (one CRTC drives one monitor).
        eventContext = _drmEventContext.allocate(arena)
        _drmEventContext.version(eventContext, 3)
        _drmEventContext.page_flip_handler2(eventContext, _drmEventContext.page_flip_handler2.allocate({ _, sequence, seconds, micros, crtc, userData ->
            Upcalls.guard("page_flip_handler2") {
                // The kernel's timestamp of the refresh that showed the picture (monotonic clock).
                val nanos = Integer.toUnsignedLong(seconds) * 1_000_000_000 + Integer.toUnsignedLong(micros) * 1_000
                // userData: the serial of the commit this flip belongs to (see commit).
                flipHandlers[crtc]?.invoke(nanos, Integer.toUnsignedLong(sequence), userData.address())
            }
        }, arena))
        loop.add(device.fd) { ready ->
            // A device that hung up (unplugged GPU) reports it for ever: stop listening, or the
            // loop would spin at full speed.
            if (ready.hangUp || ready.error) {
                log.error("The GPU $path stopped answering")
                loop.remove(device.fd)
                return@add
            }
            if (Drm.drmHandleEvent(fd, eventContext) < 0) log.warn("Reading the GPU's events failed")
        }
    }

    internal fun onFlip(crtc: Int, handler: (nanos: Long, sequence: Long, serial: Long) -> Unit) {
        flipHandlers[crtc] = handler
    }

    internal fun removeFlipHandler(crtc: Int) {
        flipHandlers.remove(crtc)
    }

    /** Names and values of a DRM object's properties (e.g. "CRTC_ID" of a connector). */
    internal fun properties(objectId: Int, objectType: Int): Map<String, Property> {
        val props = Drm.drmModeObjectGetProperties(fd, objectId, objectType)
        if (props == MemorySegment.NULL) return emptyMap()
        try {
            val count = _drmModeObjectProperties.count_props(props)
            val ids = _drmModeObjectProperties.props(props).reinterpret(count * 4L)
            val values = _drmModeObjectProperties.prop_values(props).reinterpret(count * 8L)
            val result = HashMap<String, Property>()
            for (i in 0 until count) {
                val id = ids.getAtIndex(JAVA_INT, i.toLong())
                val property = Drm.drmModeGetProperty(fd, id)
                if (property == MemorySegment.NULL) continue
                val name = _drmModeProperty.name(property).getString(0)
                Drm.drmModeFreeProperty(property)
                result[name] = Property(id, values.getAtIndex(JAVA_LONG, i.toLong()))
            }
            return result
        } finally {
            Drm.drmModeFreeObjectProperties(props)
        }
    }

    internal class Property(val id: Int, val value: Long)

    /** The contents of a property blob (e.g. a monitor's EDID), or null. */
    internal fun blob(id: Long): ByteArray? {
        if (id == 0L) return null
        val blob = Drm.drmModeGetPropertyBlob(fd, id.toInt())
        if (blob == MemorySegment.NULL) return null
        try {
            val length = _drmModePropertyBlob.length(blob)
            return _drmModePropertyBlob.data(blob).reinterpret(length.toLong()).toArray(JAVA_BYTE)
        } finally {
            Drm.drmModeFreePropertyBlob(blob)
        }
    }

    /** Stores [data] in the kernel (e.g. a display mode) and returns its blob id. */
    internal fun createBlob(data: MemorySegment): Int = Arena.ofConfined().use { temp ->
        val id = temp.allocate(JAVA_INT)
        val result = Drm.drmModeCreatePropertyBlob(fd, data, data.byteSize(), id)
        check(result == 0) { "drmModeCreatePropertyBlob failed ($result)" }
        id.get(JAVA_INT, 0)
    }

    internal fun destroyBlob(id: Int) {
        Drm.drmModeDestroyPropertyBlob(fd, id)
    }

    /**
     * True when the renderer draws on this same descriptor (it could not get one of its own):
     * then buffer handles here are shared with the GPU driver and must not be closed.
     */
    internal var sharedWithRenderer = false

    /**
     * Registers [buffer] (the renderer's) as something the display can show; returns the
     * framebuffer id. Through a dma-buf: the renderer's handles belong to its own descriptor.
     */
    internal fun addFramebuffer(buffer: GpuBuffer): Int {
        if (sharedWithRenderer) return addFramebufferShared(buffer)
        return buffer.export().use { dmabuf -> importFramebuffer(dmabuf) } ?: error("the display cannot show the renderer's buffer")
    }

    // The renderer's handles are this descriptor's own (it draws here too).
    private fun addFramebufferShared(buffer: GpuBuffer): Int = Arena.ofConfined().use { temp ->
        val handles = temp.allocate(JAVA_INT, 4)
        val pitches = temp.allocate(JAVA_INT, 4)
        val offsets = temp.allocate(JAVA_INT, 4)
        val modifiers = temp.allocate(JAVA_LONG, 4)
        val explicitModifier = buffer.modifier != Dmabuf.MODIFIER_INVALID
        for (plane in 0 until buffer.planeCount) {
            handles.setAtIndex(JAVA_INT, plane.toLong(), buffer.handle(plane))
            pitches.setAtIndex(JAVA_INT, plane.toLong(), buffer.stride(plane))
            offsets.setAtIndex(JAVA_INT, plane.toLong(), buffer.offset(plane))
            if (explicitModifier) modifiers.setAtIndex(JAVA_LONG, plane.toLong(), buffer.modifier)
        }
        val id = temp.allocate(JAVA_INT)
        val flags = if (explicitModifier) Drm.DRM_MODE_FB_MODIFIERS() else 0
        val result = Drm.drmModeAddFB2WithModifiers(
            fd, buffer.width, buffer.height, buffer.format, handles, pitches, offsets, modifiers, id, flags,
        )
        check(result == 0) { "drmModeAddFB2WithModifiers failed ($result)" }
        id.get(JAVA_INT, 0)
    }

    /**
     * Registers a program's own GPU buffer as something the display can show (direct
     * scanout). Returns the framebuffer id, or null if this display cannot show it.
     */
    internal fun importFramebuffer(buffer: Dmabuf): Int? = Arena.ofConfined().use { temp ->
        val handles = temp.allocate(JAVA_INT, 4)
        val pitches = temp.allocate(JAVA_INT, 4)
        val offsets = temp.allocate(JAVA_INT, 4)
        val modifiers = temp.allocate(JAVA_LONG, 4)
        val explicitModifier = buffer.modifier != Dmabuf.MODIFIER_INVALID
        // A layout other than plain rows cannot be told to a kernel without modifiers (wlroots does the same).
        if (explicitModifier && !modifiersSupported && buffer.modifier != MODIFIER_LINEAR) return@use null
        val withModifier = explicitModifier && modifiersSupported
        val opened = LinkedHashSet<Int>()
        try {
            val handle = temp.allocate(JAVA_INT)
            for ((index, plane) in buffer.planes.withIndex()) {
                if (Drm.drmPrimeFDToHandle(fd, plane.fd.value, handle) != 0) return@use null
                val value = handle.get(JAVA_INT, 0)
                opened += value
                handles.setAtIndex(JAVA_INT, index.toLong(), value)
                pitches.setAtIndex(JAVA_INT, index.toLong(), plane.stride)
                offsets.setAtIndex(JAVA_INT, index.toLong(), plane.offset)
                if (withModifier) modifiers.setAtIndex(JAVA_LONG, index.toLong(), buffer.modifier)
            }
            val id = temp.allocate(JAVA_INT)
            val flags = if (withModifier) Drm.DRM_MODE_FB_MODIFIERS() else 0
            val result = Drm.drmModeAddFB2WithModifiers(
                fd, buffer.width, buffer.height, buffer.format, handles, pitches, offsets, modifiers, id, flags,
            )
            if (result == 0) id.get(JAVA_INT, 0) else null
        } finally {
            // The framebuffer keeps its own reference to the memory. (With the renderer on this
            // descriptor, the handle may be the GPU driver's own: then it stays.)
            if (!sharedWithRenderer) for (value in opened) Drm.drmCloseBufferHandle(fd, value)
        }
    }

    /**
     * Lets go of framebuffer [id]. Closed, not removed: a picture still on screen stays there
     * until the next one (drmModeRmFB would switch the screen off). Old libdrm has no
     * drmModeCloseFB: then it is removed.
     */
    internal fun removeFramebuffer(id: Int) {
        val close = closeFb
        if (close != null) {
            val result = close.invokeExact(fd, id) as Int
            if (result != -22) return // -EINVAL: the kernel is too old for it
        }
        Drm.drmModeRmFB(fd, id)
    }

    // drmModeCloseFB (libdrm 2.4.117 and later), if there is one.
    private val closeFb: java.lang.invoke.MethodHandle? by lazy {
        try {
            val lookup = java.lang.foreign.SymbolLookup.libraryLookup("libdrm.so.2", arena)
            lookup.find("drmModeCloseFB").map { symbol ->
                java.lang.foreign.Linker.nativeLinker().downcallHandle(symbol, java.lang.foreign.FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
            }.orElse(null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Sends one atomic request built by [build]. Returns 0 on success or a negative
     * error number (e.g. the mode does not work with this monitor).
     */
    internal fun commit(flags: Int, serial: Long = 0, build: (AtomicRequest) -> Unit): Int {
        val request = Drm.drmModeAtomicAlloc()
        check(request != MemorySegment.NULL) { "drmModeAtomicAlloc failed" }
        try {
            build(AtomicRequest(request))
            // The serial comes back with the flip event: flips are matched to their commit.
            return Drm.drmModeAtomicCommit(fd, request, flags, MemorySegment.ofAddress(serial))
        } finally {
            Drm.drmModeAtomicFree(request)
        }
    }

    internal class AtomicRequest(private val request: MemorySegment) {
        /** Sets [property] (looked up by name in [properties]) of object [objectId]. */
        fun set(objectId: Int, properties: Map<String, Property>, property: String, value: Long) {
            val id = properties[property]?.id ?: error("DRM object $objectId has no property $property")
            Drm.drmModeAtomicAddProperty(request, objectId, id, value)
        }

        /** Sets [property] if the object has it (drivers differ in what they offer); false if not. */
        fun setIfPresent(objectId: Int, properties: Map<String, Property>, property: String, value: Long): Boolean {
            val id = properties[property]?.id ?: return false
            Drm.drmModeAtomicAddProperty(request, objectId, id, value)
            return true
        }
    }

    private companion object {
        const val MODIFIER_LINEAR = 0L
    }

    override fun close() {
        loop.remove(device.fd)
        flipHandlers.clear()
        session.closeDevice(device)
        arena.close()
    }
}
