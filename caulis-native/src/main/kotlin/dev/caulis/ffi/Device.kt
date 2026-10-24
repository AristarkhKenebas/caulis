package dev.caulis.ffi

import dev.caulis.ffi.c.libc.LibC
import java.lang.foreign.Arena
import java.nio.file.Path

/** Opening device files such as /dev/dri/renderD128. */
object Device {
    /** Opens [path] for reading and writing; the descriptor is not inherited by child processes. */
    fun open(path: Path): Fd = Arena.ofConfined().use { temp ->
        Fd.adopt(Libc.open(temp.allocateFrom(path.toString()), LibC.O_RDWR() or LibC.O_CLOEXEC(), 0))
    }
}
