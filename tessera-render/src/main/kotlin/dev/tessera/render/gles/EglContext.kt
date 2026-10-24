package dev.tessera.render.gles

import dev.tessera.ffi.Device
import dev.tessera.ffi.Fd
import dev.tessera.ffi.c.egl.Egl
import dev.tessera.ffi.c.gbm.Gbm
import dev.tessera.ffi.c.gles.Gles
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Files
import java.nio.file.Path

/**
 * An OpenGL ES 3 context on the GPU, without any window: everything is drawn into
 * textures ([GlesTarget]). The same way of reaching the GPU (EGL on GBM) is later used
 * to drive real monitors.
 *
 * OpenGL contexts belong to one thread: create and use this on the event loop thread.
 */
internal class EglContext private constructor(
    private val renderNode: Fd,
    private val gbmDevice: MemorySegment,
    private val display: MemorySegment,
    private val context: MemorySegment,
) : AutoCloseable {

    /** e.g. "AMD Radeon Graphics (radeonsi, renoir, ...)". */
    val renderer: String = glString(Gles.GL_RENDERER())

    /** OpenGL ES extensions, e.g. "GL_EXT_texture_format_BGRA8888". */
    val extensions: Set<String> = glString(Gles.GL_EXTENSIONS()).split(' ').filter { it.isNotEmpty() }.toSet()

    init {
        // Client buffers come as BGRA bytes, and frames are read back as BGRA.
        for (needed in listOf("GL_EXT_texture_format_BGRA8888", "GL_EXT_read_format_bgra")) {
            check(needed in extensions) { "The GPU driver lacks $needed" }
        }
    }

    override fun close() {
        Egl.eglMakeCurrent(display, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL)
        Egl.eglDestroyContext(display, context)
        Egl.eglTerminate(display)
        Egl.eglReleaseThread()
        Gbm.gbm_device_destroy(gbmDevice)
        renderNode.close()
    }

    companion object {
        /** Opens the first GPU render node (e.g. /dev/dri/renderD128) and creates a context on it. */
        fun create(renderNodePath: Path = findRenderNode()): EglContext {
            val renderNode = openDevice(renderNodePath)
            val gbm = Gbm.gbm_create_device(renderNode.value)
            if (gbm == MemorySegment.NULL) {
                renderNode.close()
                error("gbm_create_device failed for $renderNodePath")
            }

            val display = Arena.ofConfined().use { arena ->
                // EGLAttrib list (64-bit values): just "end".
                val attributes = arena.allocateFrom(JAVA_LONG, Egl.EGL_NONE().toLong())
                Egl.eglGetPlatformDisplay(Egl.EGL_PLATFORM_GBM_KHR(), gbm, attributes)
            }
            check(display != MemorySegment.NULL) { "eglGetPlatformDisplay failed: ${eglError()}" }
            check(Egl.eglInitialize(display, MemorySegment.NULL, MemorySegment.NULL) == Egl.EGL_TRUE()) {
                "eglInitialize failed: ${eglError()}"
            }

            // A context without a config and without a surface: we only draw into textures.
            val eglExtensions = Egl.eglQueryString(display, Egl.EGL_EXTENSIONS()).reinterpret(Long.MAX_VALUE).getString(0)
            for (needed in listOf("EGL_KHR_surfaceless_context", "EGL_KHR_no_config_context")) {
                check(needed in eglExtensions.split(' ')) { "The EGL driver lacks $needed" }
            }
            check(Egl.eglBindAPI(Egl.EGL_OPENGL_ES_API()) == Egl.EGL_TRUE()) { "eglBindAPI failed: ${eglError()}" }

            val context = Arena.ofConfined().use { arena ->
                val attributes = arena.allocateFrom(
                    JAVA_INT,
                    Egl.EGL_CONTEXT_MAJOR_VERSION(), 3,
                    Egl.EGL_CONTEXT_MINOR_VERSION(), 0,
                    Egl.EGL_NONE(),
                )
                Egl.eglCreateContext(display, MemorySegment.NULL, MemorySegment.NULL, attributes)
            }
            check(context != MemorySegment.NULL) { "eglCreateContext failed: ${eglError()}" }
            check(Egl.eglMakeCurrent(display, MemorySegment.NULL, MemorySegment.NULL, context) == Egl.EGL_TRUE()) {
                "eglMakeCurrent failed: ${eglError()}"
            }
            return EglContext(renderNode, gbm, display, context)
        }

        /** The first render node in /dev/dri (render nodes need no special rights, unlike card nodes). */
        fun findRenderNode(): Path = Files.list(Path.of("/dev/dri")).use { files ->
            files.filter { it.fileName.toString().startsWith("renderD") }.sorted().findFirst()
                .orElseThrow { IllegalStateException("No GPU render node in /dev/dri") }
        }

        private fun openDevice(path: Path): Fd = Device.open(path)

        private fun eglError(): String = "0x" + Integer.toHexString(Egl.eglGetError())

        private fun glString(name: Int): String = Gles.glGetString(name).reinterpret(Long.MAX_VALUE).getString(0)
    }
}
