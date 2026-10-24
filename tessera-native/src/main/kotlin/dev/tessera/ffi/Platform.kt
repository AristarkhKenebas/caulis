package dev.tessera.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/** Facts about the system Tessera runs on. */
object Platform {

    // A ready-to-call handle to the C function `int getpid(void)`.
    // Looking it up is slow, so it is done once, on first use.
    private val getpid: MethodHandle by lazy {
        val linker = Linker.nativeLinker()
        val address = linker.defaultLookup().find("getpid").orElseThrow()
        linker.downcallHandle(address, FunctionDescriptor.of(ValueLayout.JAVA_INT))
    }

    /** Process id of this program, read through a direct call to the C library. */
    fun processId(): Int = getpid.invokeExact() as Int
}
