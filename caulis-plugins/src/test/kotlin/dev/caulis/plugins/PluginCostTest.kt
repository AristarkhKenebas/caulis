package dev.caulis.plugins

import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/** What the system costs with no plugin: measured, with limits far above what is seen, so a jump shows. */
class PluginCostTest {
    @Test
    fun `an empty manager keeps little, and allocates nothing while nothing happens`() {
        val folder = Files.createTempDirectory("plugin-cost")
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val runtime = Runtime.getRuntime()
        val host = object : PluginHost<Any> {
            override val apiName = "test"
            override val apis = mapOf("test" to ApiVersion(0, 1))
            override fun apiFor(handle: PluginHandle): Any = handle
            override fun post(task: Runnable) = task.run()
            override fun notice(problem: Boolean, title: String, text: String) {}
            override fun dataDirFor(id: String): Path = folder
        }
        fun used(): Long {
            repeat(3) { System.gc() }
            return runtime.totalMemory() - runtime.freeMemory()
        }
        // (Its classes loaded once: they are the same few with or without plugins.)
        PluginManager(host, PluginOptions(directories = listOf(folder), settleMillis = 1)).also { it.start(); Thread.sleep(200) }.close()
        val before = used()
        val manager = PluginManager(host, PluginOptions(directories = listOf(folder), settleMillis = 1))
        manager.start()
        Thread.sleep(200)
        val kept = used() - before
        val all = threads.allThreadIds
        val allocatedBefore = threads.getThreadAllocatedBytes(all).sum()
        Thread.sleep(1000)
        val allocated = threads.getThreadAllocatedBytes(all).sum() - allocatedBefore
        val names = threads.getThreadInfo(threads.allThreadIds).filterNotNull().map { it.threadName }.filter { it.startsWith("caulis-plugin") }
        manager.close()
        println("An empty plugin manager: keeps $kept bytes of heap; threads $names; other threads allocated $allocated bytes in a second")
        assertTrue(kept < 512 * 1024, "$kept bytes")
        assertTrue("caulis-plugin-watchdog" !in names, "$names")
        folder.toFile().deleteRecursively()
    }
}
