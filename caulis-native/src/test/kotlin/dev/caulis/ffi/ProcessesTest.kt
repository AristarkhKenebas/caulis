package dev.caulis.ffi

import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Starting programs natively (posix_spawn), as Java's ProcessBuilder would. */
class ProcessesTest {

    private val temp = Files.createTempDirectory("caulis-processes")

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
        Processes.method = Processes.Method.NATIVE
    }

    private fun run(builder: ProcessBuilder): Pair<String, Int> {
        val p = Processes.startNatively(builder)
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        return out to p.exitValue()
    }

    @Test
    fun `output and exit status come back`() {
        val (out, status) = run(ProcessBuilder("sh", "-c", "printf hello; exit 7"))
        assertEquals("hello", out)
        assertEquals(7, status)
    }

    @Test
    fun `input goes in and errors join the output when asked`() {
        val p = Processes.startNatively(ProcessBuilder("sh", "-c", "cat; echo oops >&2").redirectErrorStream(true))
        p.outputStream.use { it.write("in\n".toByteArray()) }
        assertEquals("in\noops\n", p.inputStream.bufferedReader().readText())
        assertEquals(0, p.waitFor())
    }

    @Test
    fun `separate error stream`() {
        val p = Processes.startNatively(ProcessBuilder("sh", "-c", "echo out; echo err >&2"))
        assertEquals("out\n", p.inputStream.bufferedReader().readText())
        assertEquals("err\n", p.errorStream.bufferedReader().readText())
        assertEquals(0, p.waitFor())
    }

    @Test
    fun `files as input and output, the directory and the environment`() {
        val input = temp.resolve("in.txt").also { Files.writeString(it, "from a file\n") }
        val log = temp.resolve("out.log").also { Files.writeString(it, "before\n") }
        val p = Processes.startNatively(
            ProcessBuilder("sh", "-c", "cat; pwd; echo \$CAULIS_TEST")
                .redirectInput(input.toFile())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .directory(temp.toFile())
                .also { it.environment()["CAULIS_TEST"] = "yes" },
        )
        assertEquals(0, p.waitFor())
        assertEquals("before\nfrom a file\n${temp.toRealPath()}\nyes\n", Files.readString(log))
        assertEquals(-1, p.inputStream.read())
    }

    @Test
    fun `a program that is not there is told apart from other failures`() {
        val missing = assertFailsWith<ProgramStartException> { Processes.startNatively(ProcessBuilder("caulis-no-such-program-xyz")) }
        assertTrue(missing.notFound)
        assertTrue(Processes.isNotFound(missing))
        val notRunnable = temp.resolve("tool").also { Files.writeString(it, "#!/bin/sh\n") }
        val denied = assertFailsWith<ProgramStartException> { Processes.startNatively(ProcessBuilder(notRunnable.toString())) }
        assertFalse(Processes.isNotFound(denied))
        val noDirectory = assertFailsWith<IOException> { Processes.startNatively(ProcessBuilder("true").directory(temp.resolve("gone").toFile())) }
        assertFalse(Processes.isNotFound(noDirectory))
        // ProcessBuilder's own words for it.
        assertTrue(Processes.isNotFound(IOException("Cannot run program \"x\": error=2, No such file or directory")))
        assertFalse(Processes.isNotFound(IOException("Cannot run program \"/bin/sh\": Failed to exec spawn helper: pid: 1, exit code: 18")))
    }

    @Test
    fun `programs are found on the PATH given to them`() {
        val bin = Files.createDirectories(temp.resolve("bin"))
        val tool = bin.resolve("caulis-fake-tool")
        Files.writeString(tool, "#!/bin/sh\necho fake\n")
        tool.toFile().setExecutable(true)
        val builder = ProcessBuilder("caulis-fake-tool").also { it.environment()["PATH"] = "$bin:/usr/bin:/bin" }
        assertEquals("fake\n" to 0, run(builder))
    }

    @Test
    fun `none of our other open files goes along`() {
        Files.writeString(temp.resolve("secret"), "x")
        java.io.FileInputStream(temp.resolve("secret").toFile()).use {
            val (out, _) = run(ProcessBuilder("sh", "-c", "for f in /proc/self/fd/*; do readlink \$f; done"))
            assertFalse("secret" in out, out)
        }
    }

    @Test
    fun `destroy ends it and onExit hears it`() {
        val p = Processes.startNatively(ProcessBuilder("sleep", "30").redirectOutput(ProcessBuilder.Redirect.DISCARD))
        assertTrue(p.isAlive)
        val ended = p.onExit()
        p.destroy()
        assertEquals(128 + 15, p.waitFor())
        assertTrue(ended.get(5, TimeUnit.SECONDS) === p)
        assertFalse(p.isAlive)
        p.destroyForcibly() // (ended: nothing happens)
    }

    @Test
    fun `available says what is waiting`() {
        val p = Processes.startNatively(ProcessBuilder("printf", "12345"))
        p.waitFor()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (p.inputStream.available() < 5 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(5, p.inputStream.available())
        assertContentEquals("12345".toByteArray(), p.inputStream.readAllBytes())
    }

    @Test
    fun `virtual threads waiting for output do not hold their carriers`() {
        val count = Runtime.getRuntime().availableProcessors() * 2
        val processes = List(count) { Processes.startNatively(ProcessBuilder("sh", "-c", "sleep 1; echo hi")) }
        val outputs = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val readers = processes.map { p -> Thread.ofVirtual().start { outputs += p.inputStream.bufferedReader().readText() } }
        Thread.sleep(200)
        // Every carrier would be stuck in C now if they waited there: another virtual thread still runs.
        val other = java.util.concurrent.CompletableFuture<Long>()
        val asked = System.nanoTime()
        Thread.ofVirtual().start { other.complete(System.nanoTime()) }
        assertTrue((other.get(5, TimeUnit.SECONDS) - asked) / 1_000_000 < 500, "a virtual thread ran while the others waited")
        readers.forEach { it.join(10_000) }
        assertEquals(List(count) { "hi\n" }, outputs.toList())
    }

    @Test
    fun `Java's way works too and the helper's failure is recognised`() {
        Processes.method = Processes.Method.JAVA
        val p = Processes.start(ProcessBuilder("printf", "java"))
        assertEquals("java", p.inputStream.bufferedReader().readText())
        assertTrue(Processes.isSpawnHelperFailure(IOException("Cannot run program \"/bin/sh\": Failed to exec spawn helper: pid: 2589794, exit code: 18")))
        assertFalse(Processes.isSpawnHelperFailure(IOException("Cannot run program \"x\": error=2, No such file or directory")))
    }

    @Test
    fun `a Java updated under us is noticed once`() {
        val lib = Files.createDirectories(temp.resolve("lib"))
        Files.writeString(lib.resolve("modules"), "old")
        Files.writeString(temp.resolve("release"), "JAVA_VERSION=\"27\"")
        val watch = JavaWatch(temp)
        var heard = 0
        watch.listeners += Runnable { heard++ }
        assertFalse(watch.check())
        // An update replaces the file (another inode, another time).
        val replaced = lib.resolve("modules.new")
        Files.writeString(replaced, "new!")
        Files.setLastModifiedTime(replaced, FileTime.fromMillis(System.currentTimeMillis() + 60_000))
        Files.move(replaced, lib.resolve("modules"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertTrue(watch.check())
        assertTrue(watch.check())
        assertEquals(1, heard)
    }

    @Test
    fun `not installed only when it is not there, each reason said once`() {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        dev.caulis.log.Log.listener = { lines += it }
        try {
            val logger = dev.caulis.log.Logger("keys")
            val helper = IOException("Cannot run program \"/bin/sh\": Failed to exec spawn helper: pid: 1, exit code: 18\nPossible reasons: ...")
            repeat(3) { Processes.tellStartFailed(logger, "caulis-wpctl-a", helper, "the key needs it") }
            Processes.tellStartFailed(logger, "caulis-wpctl-b", ProgramStartException("caulis-wpctl-b", "No such file or directory", notFound = true), "the key needs it")
            val warnings = lines.filter { " W " in it }
            assertEquals(2, warnings.size, lines.joinToString("\n"))
            assertTrue("caulis-wpctl-a could not be started: Java's helper" in warnings[0], warnings[0])
            assertFalse("not installed" in warnings[0])
            assertTrue("caulis-wpctl-b is not installed: the key needs it" in warnings[1], warnings[1])
        } finally {
            dev.caulis.log.Log.listener = null
        }
    }

    @Test
    fun `the reason is said once per program and reason`() {
        val e = ProgramStartException("caulis-x", "Permission denied", notFound = false)
        assertEquals("Permission denied", Processes.reason(e))
        val missing = ProgramStartException("caulis-y", "No such file or directory", notFound = true)
        assertTrue(Processes.isNotFound(IOException("wrapped", missing)))
    }
}
