package dev.caulis.ffi

/**
 * A call to the C library failed.
 *
 * [errno] is the error code the call left behind (for example 11 = EAGAIN,
 * "try again later"); [message] contains its readable description.
 */
class NativeException(
    val function: String,
    val errno: Int,
) : RuntimeException("$function failed: ${Libc.describeError(errno)} (errno $errno)")
