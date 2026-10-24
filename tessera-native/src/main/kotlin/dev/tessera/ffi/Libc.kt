package dev.tessera.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

/**
 * Direct calls to C library functions.
 *
 * Every function that can fail returns -1 in C and puts the reason into `errno`.
 * The wrappers here read `errno` right after the call (Java would otherwise lose it)
 * and throw [NativeException] instead of returning -1, so callers cannot forget to
 * check for errors.
 *
 * This object is internal: the rest of Tessera uses the safe classes built on it
 * ([Fd], [Epoll], [UnixSocket], [SharedMemory]).
 */
internal object Libc {

    private val linker = Linker.nativeLinker()
    private val lookup = linker.defaultLookup()

    // Memory where the FFM API stores errno after each call. One per thread,
    // because two threads may call C at the same time.
    private val errnoLayout = Linker.Option.captureStateLayout()
    private val errnoField: VarHandle =
        errnoLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"))
    private val errnoState = ThreadLocal.withInitial { Arena.global().allocate(errnoLayout) }

    /** A handle for a C function that reports errors through errno. */
    private fun function(
        name: String,
        result: MemoryLayout,
        vararg args: MemoryLayout,
        firstVariadicArg: Int = -1,
    ): MethodHandle {
        val options = buildList {
            add(Linker.Option.captureCallState("errno"))
            if (firstVariadicArg >= 0) add(Linker.Option.firstVariadicArg(firstVariadicArg))
        }
        return linker.downcallHandle(
            lookup.find(name).orElseThrow { UnsatisfiedLinkError("C function $name not found") },
            FunctionDescriptor.of(result, *args),
            *options.toTypedArray(),
        )
    }

    private fun lastErrno(state: MemorySegment): Int = errnoField.get(state, 0L) as Int

    // Throws NativeException if a C call returned -1.
    private fun check(function: String, result: Int, state: MemorySegment): Int {
        if (result == -1) throw NativeException(function, lastErrno(state))
        return result
    }

    private fun check(function: String, result: Long, state: MemorySegment): Long {
        if (result == -1L) throw NativeException(function, lastErrno(state))
        return result
    }

    // --- error text -------------------------------------------------------------------

    private val strerror = linker.downcallHandle(
        lookup.find("strerror").orElseThrow(),
        FunctionDescriptor.of(ADDRESS, JAVA_INT),
    )

    /** Readable description of an errno value, e.g. "Resource temporarily unavailable". */
    fun describeError(errno: Int): String {
        val text = strerror.invokeExact(errno) as MemorySegment
        return text.reinterpret(Long.MAX_VALUE).getString(0)
    }

    // --- file descriptors -------------------------------------------------------------

    private val closeHandle = function("close", JAVA_INT, JAVA_INT)

    fun close(fd: Int) {
        val state = errnoState.get()
        check("close", closeHandle.invokeExact(state, fd) as Int, state)
    }

    private val readHandle = function("read", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)

    fun read(fd: Int, buffer: MemorySegment): Long {
        val state = errnoState.get()
        return check("read", readHandle.invokeExact(state, fd, buffer, buffer.byteSize()) as Long, state)
    }

    private val writeHandle = function("write", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)

    fun write(fd: Int, data: MemorySegment): Long {
        val state = errnoState.get()
        return check("write", writeHandle.invokeExact(state, fd, data, data.byteSize()) as Long, state)
    }

    // fcntl(fd, command, argument) — a variadic C function: the third argument is "...".
    private val fcntlHandle = function("fcntl", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, firstVariadicArg = 2)

    fun fcntl(fd: Int, command: Int, argument: Int = 0): Int {
        val state = errnoState.get()
        return check("fcntl", fcntlHandle.invokeExact(state, fd, command, argument) as Int, state)
    }

    // --- epoll ------------------------------------------------------------------------

    private val epollCreateHandle = function("epoll_create1", JAVA_INT, JAVA_INT)

    fun epollCreate(flags: Int): Int {
        val state = errnoState.get()
        return check("epoll_create1", epollCreateHandle.invokeExact(state, flags) as Int, state)
    }

    private val epollCtlHandle = function("epoll_ctl", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)

    fun epollCtl(epoll: Int, operation: Int, fd: Int, event: MemorySegment) {
        val state = errnoState.get()
        check("epoll_ctl", epollCtlHandle.invokeExact(state, epoll, operation, fd, event) as Int, state)
    }

    private val epollWaitHandle = function("epoll_wait", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)

    /** Returns the number of ready events, or 0 on timeout. Interruptions are retried. */
    fun epollWait(epoll: Int, events: MemorySegment, maxEvents: Int, timeoutMillis: Int): Int {
        val state = errnoState.get()
        while (true) {
            val result = epollWaitHandle.invokeExact(state, epoll, events, maxEvents, timeoutMillis) as Int
            if (result == -1 && lastErrno(state) == Errno.EINTR) continue
            return check("epoll_wait", result, state)
        }
    }

    // --- sockets ----------------------------------------------------------------------

    private val socketHandle = function("socket", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)

    fun socket(domain: Int, type: Int, protocol: Int): Int {
        val state = errnoState.get()
        return check("socket", socketHandle.invokeExact(state, domain, type, protocol) as Int, state)
    }

    private val socketPairHandle = function("socketpair", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)

    fun socketPair(domain: Int, type: Int, protocol: Int, twoInts: MemorySegment) {
        val state = errnoState.get()
        check("socketpair", socketPairHandle.invokeExact(state, domain, type, protocol, twoInts) as Int, state)
    }

    private val bindHandle = function("bind", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)

    fun bind(fd: Int, address: MemorySegment) {
        val state = errnoState.get()
        check("bind", bindHandle.invokeExact(state, fd, address, address.byteSize().toInt()) as Int, state)
    }

    private val listenHandle = function("listen", JAVA_INT, JAVA_INT, JAVA_INT)

    fun listen(fd: Int, backlog: Int) {
        val state = errnoState.get()
        check("listen", listenHandle.invokeExact(state, fd, backlog) as Int, state)
    }

    private val acceptHandle = function("accept4", JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)

    /** Accepts a waiting connection, or returns -1 if none is waiting right now. */
    fun accept(fd: Int, flags: Int): Int {
        val state = errnoState.get()
        val result = acceptHandle.invokeExact(state, fd, MemorySegment.NULL, MemorySegment.NULL, flags) as Int
        if (result == -1 && lastErrno(state) == Errno.EAGAIN) return -1
        return check("accept4", result, state)
    }

    private val connectHandle = function("connect", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)

    fun connect(fd: Int, address: MemorySegment) {
        val state = errnoState.get()
        check("connect", connectHandle.invokeExact(state, fd, address, address.byteSize().toInt()) as Int, state)
    }

    private val sendMsgHandle = function("sendmsg", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT)

    /** Sends a message; returns bytes sent, or -1 if the socket buffer is full right now. */
    fun sendMsg(fd: Int, message: MemorySegment, flags: Int): Long {
        val state = errnoState.get()
        val result = sendMsgHandle.invokeExact(state, fd, message, flags) as Long
        if (result == -1L && lastErrno(state) == Errno.EAGAIN) return -1
        return check("sendmsg", result, state)
    }

    private val recvMsgHandle = function("recvmsg", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT)

    /** Receives a message; returns bytes read (0 = peer closed), or -1 if nothing is waiting. */
    fun recvMsg(fd: Int, message: MemorySegment, flags: Int): Long {
        val state = errnoState.get()
        val result = recvMsgHandle.invokeExact(state, fd, message, flags) as Long
        if (result == -1L && lastErrno(state) == Errno.EAGAIN) return -1
        return check("recvmsg", result, state)
    }

    private val getSockOptHandle = function("getsockopt", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)

    fun getSockOpt(fd: Int, level: Int, option: Int, value: MemorySegment, length: MemorySegment) {
        val state = errnoState.get()
        check("getsockopt", getSockOptHandle.invokeExact(state, fd, level, option, value, length) as Int, state)
    }

    // --- shared memory ----------------------------------------------------------------

    private val memfdCreateHandle = function("memfd_create", JAVA_INT, ADDRESS, JAVA_INT)

    fun memfdCreate(name: MemorySegment, flags: Int): Int {
        val state = errnoState.get()
        return check("memfd_create", memfdCreateHandle.invokeExact(state, name, flags) as Int, state)
    }

    private val ftruncateHandle = function("ftruncate", JAVA_INT, JAVA_INT, JAVA_LONG)

    fun ftruncate(fd: Int, size: Long) {
        val state = errnoState.get()
        check("ftruncate", ftruncateHandle.invokeExact(state, fd, size) as Int, state)
    }
}
