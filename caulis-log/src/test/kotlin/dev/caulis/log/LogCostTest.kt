package dev.caulis.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A level that is off costs nothing: not one byte, however much the line would say. */
class LogCostTest {

    @Test
    fun `a trace of a level that is off allocates nothing`() {
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val log = Logger("test")
        Log.level = Log.PROBLEMS
        var made = 0
        val id = Thread.currentThread().threadId()
        // (The first runs load and settle what the check itself needs.)
        repeat(1000) { n -> log.trace { made++; "Frame $n of many" } }
        val before = threads.getThreadAllocatedBytes(id)
        repeat(1_000_000) { n -> log.trace { made++; "Frame $n of many" } }
        val allocated = threads.getThreadAllocatedBytes(id) - before
        assertEquals(0, made, "the text was made")
        assertTrue(allocated < 64 * 1024, "$allocated bytes for a million lines that are off")
    }
}
