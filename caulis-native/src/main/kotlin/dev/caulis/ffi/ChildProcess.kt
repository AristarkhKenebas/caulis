package dev.caulis.ffi

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * A program started with some of our open descriptors handed over at given numbers — what
 * Java's ProcessBuilder cannot do, and Xwayland needs (its Wayland and X11 sockets, the
 * window manager's connection). [ended] becomes readable when the program stops; then
 * [reap] gives its exit status (and frees it: no zombie is left).
 */
class ChildProcess private constructor(val pid: Int, val ended: Fd) : AutoCloseable {

    private var status: Int? = null

    /** The exit code if the program ended (128 + signal if it was killed), else null. Does not wait. */
    fun reap(): Int? {
        status?.let { return it }
        Arena.ofConfined().use { arena ->
            val raw = arena.allocate(JAVA_INT)
            if (Libc.waitpid(pid, raw, WNOHANG) != pid) return null
            val value = raw.get(JAVA_INT, 0)
            // WIFEXITED / WEXITSTATUS / WTERMSIG, as glibc defines them.
            val code = if (value and 0x7f == 0) (value shr 8) and 0xff else 128 + (value and 0x7f)
            status = code
            return code
        }
    }

    /** Asks the program to stop (SIGTERM). */
    fun terminate() {
        if (status == null) runCatching { Libc.kill(pid, SIGTERM) }
    }

    override fun close() {
        ended.close()
    }

    companion object {
        private const val WNOHANG = 1
        private const val SIGTERM = 15

        /**
         * Starts [program] (found in PATH) with [arguments] and [environment] (the whole
         * environment). [handOver] maps descriptor numbers in the new program to our
         * descriptors (e.g. 3 to a socket); no other descriptor of ours is inherited,
         * standard input, output and error are ours.
         */
        fun start(program: String, arguments: List<String>, environment: Map<String, String>, handOver: Map<Int, Fd> = emptyMap()): ChildProcess =
            Arena.ofConfined().use { arena ->
                fun strings(values: List<String>): MemorySegment {
                    val array = arena.allocate(ADDRESS, values.size + 1L)
                    values.forEachIndexed { i, value -> array.setAtIndex(ADDRESS, i.toLong(), arena.allocateFrom(value)) }
                    array.setAtIndex(ADDRESS, values.size.toLong(), MemorySegment.NULL)
                    return array
                }
                val actions = arena.allocate(Libc.SPAWN_ACTIONS_SIZE, 16)
                Libc.spawnActionsInit(actions)
                var nothing: Fd? = null
                try {
                    // First every source onto a number above all used ones, then onto its target:
                    // a source that happens to have another one's target number is not overwritten.
                    val above = (handOver.keys + handOver.values.map { it.value }).maxOrNull()?.plus(1) ?: 3
                    val entries = handOver.entries.toList()
                    entries.forEachIndexed { i, (_, fd) -> Libc.spawnActionsDup2(actions, fd.value, above + i) }
                    entries.forEachIndexed { i, (target, _) -> Libc.spawnActionsDup2(actions, above + i, target) }
                    // Nothing else of ours goes along (the JVM keeps files open that are not close-on-exec):
                    // above the highest target all are closed; numbers between targets get /dev/null.
                    val highest = handOver.keys.maxOrNull() ?: 2
                    val gaps = (3..highest).filter { it !in handOver }
                    nothing = if (gaps.isEmpty()) null else Device.open(java.nio.file.Path.of("/dev/null"))
                    nothing?.let { empty -> for (gap in gaps) Libc.spawnActionsDup2(actions, empty.value, gap) }
                    Libc.spawnActionsCloseFrom(actions, highest + 1)
                    val pidOut = arena.allocate(JAVA_INT)
                    val pid = Libc.spawn(
                        arena.allocateFrom(program), actions,
                        strings(listOf(program) + arguments),
                        strings(environment.map { (k, v) -> "$k=$v" }),
                        pidOut,
                    )
                    ChildProcess(pid, Fd.adopt(Libc.pidfdOpen(pid)))
                } finally {
                    Libc.spawnActionsDestroy(actions)
                    nothing?.close()
                }
            }
    }
}
