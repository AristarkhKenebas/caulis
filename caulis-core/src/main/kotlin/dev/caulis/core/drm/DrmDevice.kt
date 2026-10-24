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

    /** The raw descriptor (the renderer draws on the same device). */
    val fd: Int get() = device.fd.value

    private val arena = Arena.ofShared()
    private val flipHandlers = HashMap<Int, () -> Unit>()
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

        // "A new picture is on screen" events, per CRTC (one CRTC drives one monitor).
        eventContext = _drmEventContext.allocate(arena)
        _drmEventContext.version(eventContext, 3)
        _drmEventContext.page_flip_handler2(eventContext, _drmEventContext.page_flip_handler2.allocate({ _, _, _, _, crtc, _ ->
            Upcalls.guard("page_flip_handler2") { flipHandlers[crtc]?.invoke() }
        }, arena))
        loop.add(device.fd) { Drm.drmHandleEvent(fd, eventContext) }
    }

    internal fun onFlip(crtc: Int, handler: () -> Unit) {
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

    /** Registers [buffer] as something the display can show; returns the framebuffer id. */
    internal fun addFramebuffer(buffer: GpuBuffer): Int = Arena.ofConfined().use { temp ->
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

    internal fun removeFramebuffer(id: Int) {
        Drm.drmModeRmFB(fd, id)
    }

    /**
     * Sends one atomic request built by [build]. Returns 0 on success or a negative
     * error number (e.g. the mode does not work with this monitor).
     */
    internal fun commit(flags: Int, build: (AtomicRequest) -> Unit): Int {
        val request = Drm.drmModeAtomicAlloc()
        check(request != MemorySegment.NULL) { "drmModeAtomicAlloc failed" }
        try {
            build(AtomicRequest(request))
            return Drm.drmModeAtomicCommit(fd, request, flags, MemorySegment.NULL)
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
    }

    override fun close() {
        loop.remove(device.fd)
        flipHandlers.clear()
        session.closeDevice(device)
        arena.close()
    }
}
