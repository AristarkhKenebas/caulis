package dev.tessera.ffi

import java.lang.foreign.MemorySegment

/** Strings that C libraries hand over. */
object CStrings {
    /**
     * Reads a zero-terminated string that a C library allocated (with malloc) and gave to us,
     * then frees that memory. Returns null for a null pointer.
     */
    fun takeOwned(pointer: MemorySegment): String? {
        if (pointer == MemorySegment.NULL) return null
        val text = pointer.reinterpret(Long.MAX_VALUE).getString(0)
        Libc.free(pointer)
        return text
    }

    /** Reads a zero-terminated string owned by the C library (not freed). */
    fun borrowed(pointer: MemorySegment): String? =
        if (pointer == MemorySegment.NULL) null else pointer.reinterpret(Long.MAX_VALUE).getString(0)
}
