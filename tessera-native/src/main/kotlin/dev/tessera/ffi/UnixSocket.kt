package dev.tessera.ffi

import dev.tessera.ffi.c.libc.LibC
import dev.tessera.ffi.c.libc.cmsghdr
import dev.tessera.ffi.c.libc.iovec
import dev.tessera.ffi.c.libc.msghdr
import dev.tessera.ffi.c.libc.sockaddr_un
import dev.tessera.ffi.c.libc.ucred
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path

/**
 * A connected local (unix) socket. Wayland programs talk to the compositor through one.
 *
 * Besides bytes, a unix socket can carry open file descriptors from one process to
 * another (shared memory, keyboard maps, GPU buffers...). [send] and [receive] support
 * that. All calls are non-blocking: they return -1 when they would have to wait; use
 * [Epoll] to learn when to try again.
 */
class UnixSocket private constructor(val fd: Fd) : AutoCloseable {

    // Reusable memory for the message headers, so sending and receiving create no garbage.
    private val arena = Arena.ofShared()
    private val message = msghdr.allocate(arena)
    private val ioVector = iovec.allocate(arena)
    private val control = arena.allocate(controlSpace(MAX_FDS))

    /**
     * Sends [data] and, optionally, file descriptors. The descriptors stay owned by the
     * caller (the other process gets its own copies).
     *
     * Returns the number of bytes sent, or -1 if the socket buffer is full right now.
     */
    fun send(data: MemorySegment, fds: List<Fd> = emptyList()): Long {
        require(fds.size <= MAX_FDS) { "At most $MAX_FDS file descriptors per message, got ${fds.size}" }
        prepare(data, controlLength = if (fds.isEmpty()) 0 else controlSpace(fds.size))
        if (fds.isNotEmpty()) {
            cmsghdr.cmsg_len(control, CMSG_HEADER + 4L * fds.size)
            cmsghdr.cmsg_level(control, LibC.SOL_SOCKET())
            cmsghdr.cmsg_type(control, LibC.SCM_RIGHTS())
            fds.forEachIndexed { i, fd -> control.set(JAVA_INT, CMSG_HEADER + 4L * i, fd.value) }
        }
        return Libc.sendMsg(fd.value, message, LibC.MSG_NOSIGNAL() or LibC.MSG_DONTWAIT())
    }

    /**
     * Reads into [buffer]. Descriptors that came with the data are added to [receivedFds];
     * the caller now owns them and must close them.
     *
     * Returns the number of bytes read, 0 if the other side closed the connection,
     * or -1 if nothing is waiting right now.
     */
    fun receive(buffer: MemorySegment, receivedFds: MutableList<Fd>): Long {
        prepare(buffer, controlLength = control.byteSize())
        // MSG_CMSG_CLOEXEC: received descriptors are not inherited by programs we start.
        val count = Libc.recvMsg(fd.value, message, LibC.MSG_CMSG_CLOEXEC() or LibC.MSG_DONTWAIT())
        if (count <= 0) return count

        val added = collectFds(receivedFds)
        if (msghdr.msg_flags(message) and LibC.MSG_CTRUNC() != 0) {
            // Some descriptors did not fit and were lost. Close the ones we got so they
            // do not leak, and treat the connection as broken.
            repeat(added) { receivedFds.removeLast().close() }
            throw NativeException("recvmsg (too many file descriptors)", 0)
        }
        return count
    }

    /** Which process is on the other end (taken from the kernel, cannot be faked). */
    fun peerCredentials(): Credentials = Arena.ofConfined().use { temp ->
        val credentials = ucred.allocate(temp)
        val length = temp.allocateFrom(JAVA_INT, ucred.sizeof().toInt())
        Libc.getSockOpt(fd.value, LibC.SOL_SOCKET(), LibC.SO_PEERCRED(), credentials, length)
        Credentials(ucred.pid(credentials), ucred.uid(credentials), ucred.gid(credentials))
    }

    // Fills the message header: one data buffer plus room for descriptors.
    private fun prepare(data: MemorySegment, controlLength: Long) {
        iovec.iov_base(ioVector, data)
        iovec.iov_len(ioVector, data.byteSize())
        msghdr.msg_name(message, MemorySegment.NULL)
        msghdr.msg_namelen(message, 0)
        msghdr.msg_iov(message, ioVector)
        msghdr.msg_iovlen(message, 1)
        msghdr.msg_control(message, if (controlLength == 0L) MemorySegment.NULL else control)
        msghdr.msg_controllen(message, controlLength)
        msghdr.msg_flags(message, 0)
    }

    // Walks the received control messages and takes ownership of every descriptor.
    private fun collectFds(into: MutableList<Fd>): Int {
        val used = msghdr.msg_controllen(message)
        var offset = 0L
        var added = 0
        while (offset + CMSG_HEADER <= used) {
            val header = control.asSlice(offset)
            val length = cmsghdr.cmsg_len(header)
            if (length < CMSG_HEADER || offset + length > used) break
            if (cmsghdr.cmsg_level(header) == LibC.SOL_SOCKET() && cmsghdr.cmsg_type(header) == LibC.SCM_RIGHTS()) {
                val count = ((length - CMSG_HEADER) / 4).toInt()
                for (i in 0 until count) {
                    into += Fd.adopt(header.get(JAVA_INT, CMSG_HEADER + 4L * i))
                    added++
                }
            }
            offset += align(length)
        }
        return added
    }

    override fun close() {
        fd.close()
        arena.close()
    }

    /** Process, user and group of the other end of a connection. */
    data class Credentials(val pid: Int, val uid: Int, val gid: Int)

    companion object {
        /** Most descriptors one message can carry (the same limit libwayland uses). */
        const val MAX_FDS = 28

        /** Two sockets connected to each other, e.g. for a client started by the compositor. */
        fun pair(): Pair<UnixSocket, UnixSocket> = Arena.ofConfined().use { temp ->
            val ends = temp.allocate(JAVA_INT, 2)
            val type = LibC.SOCK_STREAM() or LibC.SOCK_CLOEXEC() or LibC.SOCK_NONBLOCK()
            Libc.socketPair(LibC.AF_UNIX(), type, 0, ends)
            UnixSocket(Fd.adopt(ends.getAtIndex(JAVA_INT, 0))) to UnixSocket(Fd.adopt(ends.getAtIndex(JAVA_INT, 1)))
        }

        /** Connects to a listening socket at [path]. */
        fun connect(path: Path): UnixSocket {
            val socket = Fd.adopt(newSocket())
            try {
                Arena.ofConfined().use { temp -> Libc.connect(socket.value, address(path, temp)) }
            } catch (e: NativeException) {
                socket.close()
                throw e
            }
            return UnixSocket(socket)
        }

        internal fun adopt(fd: Fd) = UnixSocket(fd)

        internal fun newSocket(): Int =
            Libc.socket(LibC.AF_UNIX(), LibC.SOCK_STREAM() or LibC.SOCK_CLOEXEC() or LibC.SOCK_NONBLOCK(), 0)

        // struct sockaddr_un for a filesystem path.
        internal fun address(path: Path, arena: Arena): MemorySegment {
            val bytes = path.toString().toByteArray()
            val address = sockaddr_un.allocate(arena)
            val room = sockaddr_un.sun_path(address).byteSize()
            // One byte stays free for the terminating zero C expects.
            require(bytes.size < room) { "Socket path is too long (max ${room - 1} bytes): $path" }
            sockaddr_un.sun_family(address, LibC.AF_UNIX().toShort())
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, sockaddr_un.sun_path(address), 0, bytes.size.toLong())
            return address
        }

        // Control message sizes, as the C macros CMSG_SPACE/CMSG_LEN compute them.
        private val CMSG_HEADER = align(cmsghdr.sizeof())

        private fun align(size: Long) = (size + 7) and 7L.inv()

        private fun controlSpace(fdCount: Int) = CMSG_HEADER + align(4L * fdCount)
    }
}

/**
 * A socket at a filesystem path that programs can connect to (the Wayland display socket).
 * Closing it removes the path again.
 */
class UnixListener private constructor(val fd: Fd, val path: Path) : AutoCloseable {

    /** Takes one waiting connection, or returns null if nobody is waiting. */
    fun accept(): UnixSocket? {
        val client = Libc.accept(fd.value, LibC.SOCK_CLOEXEC() or LibC.SOCK_NONBLOCK())
        return if (client < 0) null else UnixSocket.adopt(Fd.adopt(client))
    }

    override fun close() {
        fd.close()
        Files.deleteIfExists(path)
    }

    companion object {
        fun listen(path: Path, backlog: Int = 128): UnixListener {
            val socket = Fd.adopt(UnixSocket.newSocket())
            try {
                Arena.ofConfined().use { temp -> Libc.bind(socket.value, UnixSocket.address(path, temp)) }
                Libc.listen(socket.value, backlog)
            } catch (e: NativeException) {
                socket.close()
                throw e
            }
            return UnixListener(socket, path)
        }
    }
}
