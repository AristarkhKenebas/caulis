package dev.caulis.ffi

import dev.caulis.log.InternalCaulisApi
import dev.caulis.ffi.c.libc.LibC

/** errno values Caulis reacts to. */
@InternalCaulisApi
object Errno {
    val EAGAIN: Int = LibC.EAGAIN()
    val EINTR: Int = LibC.EINTR()
    val EPIPE: Int = LibC.EPIPE()
    val ECONNRESET: Int = LibC.ECONNRESET()
    val EWOULDBLOCK: Int = LibC.EWOULDBLOCK()

    /** The process (EMFILE) or the whole system (ENFILE) has no free file descriptors. */
    val EMFILE: Int = LibC.EMFILE()
    val ENFILE: Int = LibC.ENFILE()

    /** A connecting program gave up before we accepted it. */
    val ECONNABORTED: Int = LibC.ECONNABORTED()
}
