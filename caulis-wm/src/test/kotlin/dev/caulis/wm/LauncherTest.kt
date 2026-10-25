package dev.caulis.wm

import dev.caulis.log.Log
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.io.path.readLines
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Starting programs: the Wayland way first, through X11 when that fails. */
class LauncherTest {

    private val dir: Path = Files.createTempDirectory("caulis-launch")
    private val thread = Executors.newSingleThreadExecutor()
    private var x11Programs = emptySet<String>()
    private var scale = 1.0
    private var sharp = false
    private val launcher = Launcher(
        environment = { env ->
            env["WAYLAND_DISPLAY"] = "wayland-test"
            env["DISPLAY"] = ":99"
        },
        x11Available = { true },
        post = { task -> thread.execute(task) },
        settings = { Launcher.Settings(waylandFirst = true, x11Programs = x11Programs, scale = scale, sharpWholeScales = sharp) },
    )

    @AfterTest
    fun cleanUp() {
        thread.shutdownNow()
        dir.toFile().deleteRecursively()
    }

    // A program that notes each start (with how it was started), then does [then].
    private fun program(name: String, then: String): String {
        val script = dir.resolve(name)
        Files.writeString(script, """
            #!/bin/sh
            echo "start WAYLAND_DISPLAY=${'$'}{WAYLAND_DISPLAY:-} GDK_BACKEND=${'$'}{GDK_BACKEND:-} QT_QPA_PLATFORM=${'$'}{QT_QPA_PLATFORM:-} DISPLAY=${'$'}{DISPLAY:-} J2D_UISCALE=${'$'}{J2D_UISCALE:-} JDK_JAVA_OPTIONS=${'$'}{JDK_JAVA_OPTIONS:-}|" >> "$dir/$name.log"
            echo ${'$'}${'$'} > "$dir/$name.pid"
            $then
        """.trimIndent())
        script.toFile().setExecutable(true)
        return script.toString()
    }

    private fun starts(name: String): List<String> = dir.resolve("$name.log").takeIf(Files::exists)?.readLines().orEmpty()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun launch(command: String, x11: Boolean = false) = thread.submit { launcher.launch(command, x11) }.get()

    @Test
    fun `programs are told to prefer Wayland`() {
        launch(program("fine", "exit 0"))
        waitFor { starts("fine").isNotEmpty() }
        Thread.sleep(300)
        val start = starts("fine").single()
        assertTrue("WAYLAND_DISPLAY=wayland-test" in start, start)
        assertTrue("GDK_BACKEND=${System.getenv("GDK_BACKEND") ?: "wayland,x11"}" in start, start)
        assertTrue("QT_QPA_PLATFORM=${System.getenv("QT_QPA_PLATFORM") ?: "wayland;xcb"}" in start, start)
    }

    @Test
    fun `a program that fails on Wayland is started again through X11, and then right away`() {
        val command = program("waylandless", """[ -n "${'$'}WAYLAND_DISPLAY" ] && exit 3; exit 0""")
        launch(command)
        waitFor { starts("waylandless").size == 2 }
        val (first, second) = starts("waylandless")
        assertTrue("WAYLAND_DISPLAY=wayland-test" in first, first)
        assertTrue("WAYLAND_DISPLAY= " in second && "GDK_BACKEND=x11" in second && "QT_QPA_PLATFORM=xcb" in second, second)
        assertTrue("DISPLAY=:99" in second, second)
        // Started again, it goes through X11 at once.
        launch(command)
        waitFor { starts("waylandless").size == 3 }
        assertTrue("GDK_BACKEND=x11" in starts("waylandless")[2])
    }

    @Test
    fun `a program that showed a window is not started again when it fails later`() {
        launch(program("windowed", "sleep 1; exit 3"))
        waitFor { Files.exists(dir.resolve("windowed.pid")) }
        val pid = Files.readString(dir.resolve("windowed.pid")).trim().toInt()
        thread.submit { launcher.windowOpened(pid) }.get()
        Thread.sleep(2000)
        assertEquals(1, starts("windowed").size)
    }

    @Test
    fun `X11 programs and missing programs`() {
        x11Programs = setOf("oldie")
        launch(program("oldie", "exit 0"))
        launch(program("forced", "exit 0"), x11 = true)
        waitFor { starts("oldie").isNotEmpty() && starts("forced").isNotEmpty() }
        assertTrue("GDK_BACKEND=x11" in starts("oldie").single())
        assertTrue("WAYLAND_DISPLAY= " in starts("forced").single())

        val lines = CopyOnWriteArrayList<String>()
        Log.listener = { lines += it }
        try {
            launch("surely-no-such-program-here")
            waitFor { lines.any { "was not found" in it } }
        } finally {
            Log.listener = null
        }
    }

    @Test
    fun `Java is told the nearest whole scale (it has no other on X11)`() {
        scale = 1.5
        launch(program("app", "exit 0"))
        waitFor { starts("app").isNotEmpty() }
        assertTrue("J2D_UISCALE=2 " in starts("app").single(), starts("app").single())
        // With an X server of its own it draws at the next whole scale up, to be shown smaller (sharp).
        scale = 1.25
        sharp = true
        launch(program("sharp", "exit 0"))
        waitFor { starts("sharp").isNotEmpty() }
        assertTrue("J2D_UISCALE=2 " in starts("sharp").single(), starts("sharp").single())
    }

    @Test
    fun `the program a command starts`() {
        assertEquals("steam", Launcher.programName("env FOO=1 /usr/bin/steam -silent"))
        assertEquals("foot", Launcher.programName("  foot --server"))
        assertEquals("firefox", Launcher.programName("MOZ_X=1 exec firefox"))
    }
}
