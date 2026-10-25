package dev.caulis.render.gles

import dev.caulis.ffi.c.egl.Egl
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/**
 * Functions from EGL and OpenGL ES extensions. Drivers do not export them as normal
 * symbols; eglGetProcAddress hands out their addresses.
 */
internal class EglExtensions {

    private val linker = Linker.nativeLinker()

    private fun find(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val address = Arena.ofConfined().use { arena -> Egl.eglGetProcAddress(arena.allocateFrom(name)) }
        check(address != MemorySegment.NULL) { "The GPU driver lacks $name" }
        return linker.downcallHandle(address, descriptor)
    }

    // void glEGLImageTargetTexture2DOES(GLenum target, GLeglImageOES image)
    private val imageTargetTexture = find("glEGLImageTargetTexture2DOES", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS))

    // EGLBoolean eglQueryDmaBufFormatsEXT(EGLDisplay, EGLint max, EGLint *formats, EGLint *count)
    private val queryFormats = find(
        "eglQueryDmaBufFormatsEXT",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
    )

    // EGLBoolean eglQueryDmaBufModifiersEXT(EGLDisplay, EGLint format, EGLint max,
    //     EGLuint64KHR *modifiers, EGLBoolean *externalOnly, EGLint *count)
    private val queryModifiers = find(
        "eglQueryDmaBufModifiersEXT",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
    )

    /** Makes the bound GL_TEXTURE_2D show [image]. */
    fun imageTargetTexture2D(target: Int, image: MemorySegment) {
        imageTargetTexture.invokeExact(target, image)
    }

    /** Pixel formats (DRM fourcc) the GPU can import. */
    fun dmabufFormats(display: MemorySegment): List<Int> = Arena.ofConfined().use { arena ->
        val count = arena.allocate(JAVA_INT)
        check(queryFormats.invokeExact(display, 0, MemorySegment.NULL, count) as Int == Egl.EGL_TRUE())
        val n = count.get(JAVA_INT, 0)
        if (n == 0) return emptyList()
        val formats = arena.allocate(JAVA_INT, n.toLong())
        check(queryFormats.invokeExact(display, n, formats, count) as Int == Egl.EGL_TRUE())
        (0 until count.get(JAVA_INT, 0)).map { formats.getAtIndex(JAVA_INT, it.toLong()) }
    }

    /** Memory layouts the GPU can import for [format], without the "external only" ones. */
    fun dmabufModifiers(display: MemorySegment, format: Int): List<Long> =
        dmabufModifiersAll(display, format).filter { !it.second }.map { it.first }

    /** Every memory layout the GPU knows for [format], each with whether it is "external only". */
    fun dmabufModifiersAll(display: MemorySegment, format: Int): List<Pair<Long, Boolean>> = Arena.ofConfined().use { arena ->
        val count = arena.allocate(JAVA_INT)
        check(queryModifiers.invokeExact(display, format, 0, MemorySegment.NULL, MemorySegment.NULL, count) as Int == Egl.EGL_TRUE())
        val n = count.get(JAVA_INT, 0)
        if (n == 0) return emptyList()
        val modifiers = arena.allocate(java.lang.foreign.ValueLayout.JAVA_LONG, n.toLong())
        val externalOnly = arena.allocate(JAVA_INT, n.toLong())
        check(queryModifiers.invokeExact(display, format, n, modifiers, externalOnly, count) as Int == Egl.EGL_TRUE())
        (0 until count.get(JAVA_INT, 0)).map {
            modifiers.getAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, it.toLong()) to (externalOnly.getAtIndex(JAVA_INT, it.toLong()) != 0)
        }
    }
}
