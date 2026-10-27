package dev.caulis.wm

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Autostart's order and timing on a clock of the test's own (no waiting, no real programs):
 * the stagger, delays, after, ifNotRunning, restarts and their limit, and reloads.
 */
class AutostartTest {

    // A program that ends when the test says so.
    private class FakeProcess(private val id: Long) : Process() {
        val exit = CompletableFuture<Process>()
        var status = -1

        fun end(status: Int) {
            this.status = status
            exit.complete(this)
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = exit.get().let { status }
        override fun exitValue(): Int = if (exit.isDone) status else throw IllegalThreadStateException()
        override fun destroy() = end(143)
        override fun pid() = id
        override fun isAlive() = !exit.isDone
        override fun onExit(): CompletableFuture<Process> = exit
    }

    // The test's clock and timers; posted work runs when the test lets it ([run]).
    private class World {
        var now = 0L
        private val timers = ArrayList<Pair<Long, () -> Unit>>()
        private val posted = ConcurrentLinkedQueue<() -> Unit>()
        val started = ArrayList<Pair<Long, List<String>>>()
        val processes = ArrayList<FakeProcess>()
        val runningNames = HashSet<String>()
        var envs = ArrayList<Map<String, String>>()

        val runner = AutostartRunner(
            start = { command, _, env ->
                started += now to command
                envs += env
                FakeProcess(processes.size + 1L).also { processes += it }
            },
            later = { millis, action ->
                val timer = (now + millis) to action
                timers += timer
                AutoCloseable { timers.remove(timer) }
            },
            post = { posted += it },
            runningAlready = { it in runningNames },
            clock = { now },
        )

        /** Lets [millis] pass: the timers due run, in order. */
        fun advance(millis: Long) {
            val end = now + millis
            while (true) {
                run()
                val next = timers.minByOrNull { it.first }?.takeIf { it.first <= end } ?: break
                timers.remove(next)
                now = next.first
                next.second()
            }
            now = end
            run()
        }

        /** Runs what was posted. */
        fun run() {
            while (true) (posted.poll() ?: return)()
        }

        fun names() = started.map { (_, command) -> (if (command[0] == "/bin/sh") command.last() else command[0]).substringAfterLast('/') }
    }

    @Test
    fun `entries start in order, the stagger apart`() {
        val w = World()
        val autostart = Autostart().apply {
            stagger = 400.milliseconds
            app("waybar")
            app("mako")
            shell("echo hi")
        }
        w.runner.begin(autostart)
        assertEquals(listOf(0L to listOf("waybar")), w.started, "the first at once")
        w.advance(399)
        assertEquals(1, w.started.size)
        w.advance(1)
        w.advance(400)
        assertEquals(listOf(0L, 400L, 800L), w.started.map { it.first })
        assertEquals(listOf("/bin/sh", "-c", "echo hi"), w.started[2].second, "shell through sh -c")
    }

    @Test
    fun `a delay, and after waits for the other to start`() {
        val w = World()
        val autostart = Autostart().apply {
            staggerMillis = 100
            app("nm-applet", "--indicator") {
                delay = 2.seconds
                after("waybar")
                env("X", "y")
            }
            app("waybar") { delay = 1.seconds }
        }
        w.runner.begin(autostart)
        assertTrue(w.started.isEmpty())
        w.advance(1000)
        assertEquals(listOf(1000L to listOf("waybar")), w.started, "waybar after its delay")
        w.advance(1000)
        assertEquals(2000L to listOf("nm-applet", "--indicator"), w.started[1], "nm-applet after its delay (waybar had started)")
        assertEquals(mapOf("X" to "y"), w.envs[1])
    }

    @Test
    fun `after with a long wait starts once the other has, or after the timeout`() {
        val w = World()
        val autostart = Autostart().apply {
            staggerMillis = 0
            afterTimeoutMillis = 5000
            app("second") { after("first") }
            app("first") { delayMillis = 3000 }
            app("lonely") { after("never-started"); after("slow") }
            app("slow") { delayMillis = 60_000 }
        }
        w.runner.begin(autostart)
        assertTrue(w.started.isEmpty(), "second waits for first, lonely for slow")
        w.advance(3000)
        assertEquals(listOf("first", "second"), w.names())
        w.advance(2000)
        assertEquals(listOf("first", "second", "lonely"), w.names(), "lonely gave up waiting after 5 s")
    }

    @Test
    fun `ifNotRunning leaves out what runs already`() {
        val w = World()
        w.runningNames += "waybar"
        val autostart = Autostart().apply {
            staggerMillis = 0
            app("waybar") { ifNotRunning = true }
            app("mako") { ifNotRunning = true }
            app("dunst") { after("waybar") }
        }
        w.runner.begin(autostart)
        assertEquals(listOf("mako", "dunst"), w.names(), "waybar not started; dunst does not wait for it")
        assertTrue(w.runner.status().first().contains("runs already"), w.runner.status().first())
    }

    @Test
    fun `restarted after a crash, within the limit`() {
        val w = World()
        val autostart = Autostart().apply {
            staggerMillis = 0
            app("bar") { restart = OnCrash(maxTimes = 2, within = 1.minutes) }
            app("clean") { restart = OnCrash() }
            app("never")
        }
        w.runner.begin(autostart)
        assertEquals(3, w.started.size)
        w.processes[1].end(0) // clean: no crash
        w.processes[2].end(1) // never restarted
        w.processes[0].end(139) // a segfault
        w.advance(10)
        assertEquals(listOf("bar", "clean", "never", "bar"), w.names())
        w.processes[3].end(134)
        w.advance(10)
        assertEquals(5, w.started.size, "the second restart")
        w.processes[4].end(1)
        w.advance(10)
        assertEquals(5, w.started.size, "a third crash within a minute: given up")
        assertTrue(w.runner.status().first().contains("given up"), w.runner.status().first())
    }

    @Test
    fun `restarts only count within their time`() {
        val w = World()
        w.runner.begin(Autostart().apply { staggerMillis = 0; app("bar") { restart = Always(maxTimes = 1, within = 1.minutes) } })
        w.processes[0].end(0)
        w.advance(10)
        assertEquals(2, w.started.size, "always: also after a clean end")
        w.advance(120_000)
        w.processes[1].end(0)
        w.advance(10)
        assertEquals(3, w.started.size, "the restart before is more than a minute ago")
        w.processes[2].end(0)
        w.advance(10)
        assertEquals(3, w.started.size, "then the limit")
    }

    @Test
    fun `stopped from outside is no crash`() {
        val w = World()
        w.runner.begin(Autostart().apply { staggerMillis = 0; app("bar") { restart = Restart.onCrash(3, 60_000) } })
        w.processes[0].end(143)
        w.advance(10)
        assertEquals(1, w.started.size)
    }

    @Test
    fun `a reload starts only new entries and stops watching removed ones`() {
        val w = World()
        val first = Autostart().apply {
            staggerMillis = 0
            app("waybar")
            app("mako") { restart = OnCrash() }
        }
        w.runner.begin(first)
        w.runner.begin(first) // (once only)
        assertEquals(listOf("waybar", "mako"), w.names())
        val second = Autostart().apply {
            staggerMillis = 0
            app("waybar")
            app("swayidle", "-w")
        }
        w.runner.reconfigure(second)
        assertEquals(listOf("waybar", "mako", "swayidle"), w.names(), "only the new one started")
        w.processes[1].end(1)
        w.advance(10)
        assertEquals(3, w.started.size, "mako is no longer watched (not restarted)")
        w.runner.reconfigure(second)
        assertEquals(3, w.started.size, "the same configuration again: nothing starts")
    }

    @Test
    fun `a reload that changes an entry, or takes it away and puts it back, starts no second copy`() {
        val w = World()
        w.runner.begin(Autostart().apply { staggerMillis = 0; app("waybar"); app("mako") })
        assertEquals(listOf("waybar", "mako"), w.names())
        // waybar's environment, folder, name and after change: the same program, not started again.
        w.runner.reconfigure(Autostart().apply {
            staggerMillis = 0
            app("waybar") { env("A", "b"); dir = "/tmp"; name = "bar"; after("mako") }
            app("mako")
        })
        assertEquals(listOf("waybar", "mako"), w.names(), "nothing started twice")
        // mako taken away, then put back while it still runs: the running one is taken over.
        w.runner.reconfigure(Autostart().apply { staggerMillis = 0; app("waybar") })
        w.runner.reconfigure(Autostart().apply { staggerMillis = 0; app("waybar"); app("mako") { restart = OnCrash() } })
        assertEquals(listOf("waybar", "mako"), w.names(), "mako still ran: not started again")
        assertTrue(w.runner.status().any { it.startsWith("mako: running") }, "${w.runner.status()}")
        // ...and is watched again (its new restart rule).
        w.processes[1].end(1)
        w.advance(10)
        assertEquals(listOf("waybar", "mako", "mako"), w.names(), "restarted after its crash")
        // Taken away and put back after it ended: started anew.
        w.runner.reconfigure(Autostart().apply { staggerMillis = 0; app("waybar") })
        w.processes[2].end(0)
        w.advance(10)
        w.runner.reconfigure(Autostart().apply { staggerMillis = 0; app("waybar"); app("mako") })
        assertEquals(listOf("waybar", "mako", "mako", "mako"), w.names())
        // The same command twice is two programs.
        w.runner.reconfigure(Autostart().apply { staggerMillis = 0; app("waybar"); app("mako"); app("mako") })
        assertEquals(5, w.started.size)
    }

    @Test
    fun `startLater waits for the start, then goes through the queue`() {
        val w = World()
        w.runner.startLater(listOf("firefox"), null, gapMillis = 300)
        w.runner.startLater(listOf("foot"), null)
        assertTrue(w.started.isEmpty(), "before the start: waiting")
        assertTrue(w.runner.status().all { "waiting for the start" in it })
        w.runner.begin(Autostart().apply { staggerMillis = 100; app("waybar") })
        assertEquals(listOf("waybar"), w.names())
        w.advance(100)
        assertEquals(listOf("waybar", "firefox"), w.names())
        w.advance(299)
        assertEquals(2, w.started.size, "firefox's own gap")
        w.advance(1)
        assertEquals(listOf("waybar", "firefox", "foot"), w.names())
    }

    @Test
    fun `a script that is not there or cannot run is reported, not started`() {
        val w = World()
        val folder = Files.createTempDirectory("caulis-autostart")
        try {
            val plain = folder.resolve("plain.sh").also { Files.writeString(it, "#!/bin/sh\n") }
            val runnable = folder.resolve("run.sh").also { Files.writeString(it, "#!/bin/sh\n"); it.toFile().setExecutable(true) }
            w.runner.begin(Autostart().apply {
                staggerMillis = 0
                script(folder.resolve("missing.sh").toString())
                script(plain.toString())
                script(runnable.toString(), "--now")
            })
            assertEquals(listOf(listOf(runnable.toString(), "--now")), w.started.map { it.second })
            assertTrue(w.runner.status().take(2).all { "could not be started" in it })
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `settings are checked and names are the programs'`() {
        val a = Autostart()
        assertFailsWith<IllegalArgumentException> { a.staggerMillis = -1 }
        assertFailsWith<IllegalArgumentException> { a.app("") }
        assertEquals("nm-applet", a.app("/usr/bin/nm-applet").name)
        assertEquals("notify-send", a.shell("notify-send hi").name)
        assertEquals("start.sh", a.script("~/.config/foo/start.sh").name)
        assertEquals(listOf("nm-applet", "notify-send", "start.sh"), a.entries.map { it.name })
        assertTrue(a.remove("notify-send"))
        assertEquals(2, a.entries.size)
    }
}
