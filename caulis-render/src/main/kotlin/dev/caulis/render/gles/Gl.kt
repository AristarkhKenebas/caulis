package dev.caulis.render.gles

import dev.caulis.ffi.c.gles.Gles
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_INT

/** Small helpers around OpenGL ES calls. */
internal object Gl {

    /** Creates one OpenGL object (texture, framebuffer, buffer...) with a glGenXxx function. */
    fun generate(gen: (Int, java.lang.foreign.MemorySegment) -> Unit): Int = Arena.ofConfined().use { arena ->
        val id = arena.allocate(JAVA_INT)
        gen(1, id)
        id.get(JAVA_INT, 0)
    }

    /** Deletes one OpenGL object with a glDeleteXxx function. */
    fun delete(id: Int, delete: (Int, java.lang.foreign.MemorySegment) -> Unit) = Arena.ofConfined().use { arena ->
        delete(1, arena.allocateFrom(JAVA_INT, id))
    }

    private val log = dev.caulis.log.Logger("render")

    /**
     * Throws if OpenGL reported an error since the last check. Call [drain] before the
     * operation, so an error left by something else is not blamed on it.
     */
    fun check(what: String) {
        val error = Gles.glGetError()
        if (error != Gles.GL_NO_ERROR()) error("OpenGL error 0x${Integer.toHexString(error)} in $what")
    }

    /**
     * Reads and forgets OpenGL's errors so far (it keeps them until asked), logging them as
     * [whose]: a later checked call (a program's texture) must not fail for them. Returns
     * true if there were any.
     */
    fun drain(whose: String): Boolean {
        var any = false
        repeat(MAX_ERRORS) {
            val error = Gles.glGetError()
            if (error == Gles.GL_NO_ERROR()) return any
            any = true
            log.warn("OpenGL error 0x${Integer.toHexString(error)} in $whose (ignored)")
        }
        return any
    }

    // OpenGL keeps one error per kind: this many reads empty the list.
    private const val MAX_ERRORS = 16
}
