package dev.tessera.ffi

import dev.tessera.ffi.c.libc.LibC

/** errno values Tessera reacts to. */
object Errno {
    val EAGAIN: Int = LibC.EAGAIN()
    val EINTR: Int = LibC.EINTR()
    val EPIPE: Int = LibC.EPIPE()
    val ECONNRESET: Int = LibC.ECONNRESET()
    val EWOULDBLOCK: Int = LibC.EWOULDBLOCK()
}
