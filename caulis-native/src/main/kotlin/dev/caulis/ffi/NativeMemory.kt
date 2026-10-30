package dev.caulis.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * The C library's memory: what Skia, the GPU driver, fonts and the like take with malloc.
 *
 * glibc keeps memory that was freed for later use — in pools of its own (one per few threads)
 * — and gives back only what is at a pool's very end. After the start's work (the first
 * frames, menus drawn ahead, pictures read) tens of megabytes stayed counted to the
 * compositor although nothing used them any more. [trim] asks glibc to give them back.
 */
object NativeMemory {

    private val log = dev.caulis.log.Logger("native")

    // malloc_trim(size_t pad): glibc's own; null where there is none (musl).
    private val mallocTrim: MethodHandle? = runCatching {
        val linker = Linker.nativeLinker()
        linker.defaultLookup().find("malloc_trim").map { linker.downcallHandle(it, FunctionDescriptor.of(JAVA_INT, JAVA_LONG)) }.orElse(null)
    }.getOrNull()

    // mallopt(int param, int value): glibc's settings of malloc.
    private val mallopt: MethodHandle? = runCatching {
        val linker = Linker.nativeLinker()
        linker.defaultLookup().find("mallopt").map { linker.downcallHandle(it, FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)) }.orElse(null)
    }.getOrNull()

    // glibc's M_ARENA_MAX and M_MMAP_THRESHOLD.
    private const val M_ARENA_MAX = -8
    private const val M_MMAP_THRESHOLD = -3

    /**
     * Blocks of [kb] kB or more get a mapping of their own and are given back to the system
     * the moment they are freed. glibc starts at 128 kB but raises the limit by itself each
     * time such a block is freed (up to 32 MB): from then on the pictures Skia draws into and
     * the GPU driver's buffers (a few hundred kB to some MB, made and freed each frame) stayed
     * in its pools, 30-60 MB more within minutes until the next [trim]. Set, the limit stays.
     * Call it early. Returns false where it cannot (not glibc).
     */
    @JvmStatic
    fun mapBlocksAbove(kb: Int): Boolean {
        require(kb in 16..65_536) { "16 to 65536 kB, not $kb" }
        val handle = mallopt ?: return false
        return try {
            (handle.invokeExact(M_MMAP_THRESHOLD, kb * 1024) as Int) == 1
        } catch (e: Throwable) {
            log.warn("mallopt failed: $e")
            false
        }.also { ok -> if (ok) log.info { "Blocks of $kb kB or more mapped of their own (given back when freed)" } }
    }

    /**
     * At most [count] of glibc's pools for the threads that malloc from now on (glibc's own
     * choice: up to 8 per CPU core, each keeping what was freed in it — a compositor's few busy
     * threads do not need them). Only this program: started programs keep glibc's default.
     * Call it early (threads that malloc'd before keep their pools). Returns false where it
     * cannot (not glibc).
     */
    @JvmStatic
    fun limitPools(count: Int): Boolean {
        require(count >= 1) { "at least one pool, not $count" }
        val handle = mallopt ?: return false
        return try {
            (handle.invokeExact(M_ARENA_MAX, count) as Int) == 1
        } catch (e: Throwable) {
            log.warn("mallopt failed: $e")
            false
        }.also { ok -> if (ok) log.info { "At most $count malloc pools" } }
    }

    /** True where [trim] can do something (glibc; not musl). */
    @JvmStatic
    val canTrim: Boolean get() = mallocTrim != null

    /**
     * Gives freed memory back to the system (glibc's malloc_trim). Takes a few milliseconds
     * and holds other threads' malloc meanwhile: call it now and then (after the start, after
     * a big menu closed), from any thread — not for every frame. Returns how much smaller the
     * program got in kB (the resident memory before and after), or -1 if it cannot trim here.
     */
    @JvmStatic
    fun trim(): Long {
        val handle = mallocTrim ?: return -1
        val before = residentKb()
        val started = System.nanoTime()
        try {
            handle.invokeExact(0L) as Int
        } catch (e: Throwable) {
            log.warn("malloc_trim failed: $e")
            return -1
        }
        val freed = if (before < 0) 0 else (before - residentKb()).coerceAtLeast(0)
        log.info { "Freed memory given back to the system: ${freed / 1024} MB, in ${(System.nanoTime() - started) / 1_000_000} ms" }
        return freed
    }

    /** The program's resident memory now in kB (VmRSS), or -1 if unknown. */
    @JvmStatic
    fun residentKb(): Long = runCatching {
        java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))
            .firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()
    }.getOrNull() ?: -1
}
