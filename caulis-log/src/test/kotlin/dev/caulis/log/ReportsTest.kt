package dev.caulis.log

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What is taken out of reports, the log file's limit, and crash reports. */
class ReportsTest {

    @AfterTest
    fun reset() {
        Log.file = null
        Log.toConsole = true
        Log.fileLevel = 0
        Log.fileLimitBytes = 16L * 1024 * 1024
        Log.fileKeep = 2
        CrashReports.folder = null
        CrashReports.state = { null }
        CrashReports.privateWords = { emptyList() }
    }

    private val redactor = Redactor(words = listOf("My bank - Firefox"), home = "/home/alice", user = "alice", host = "alices-laptop")

    @Test
    fun `secrets, names, addresses and titles go`() {
        val cleaned = redactor.clean(
            """
            run --password hunter2 now, token=abc123 API_KEY: "zzz" and https://bob:pw@example.org/x
            Authorization: Bearer abcdefghijkl0123
            /home/alice/.config/newm/config.kts and /home/carol/x of alice on alices-laptop, kernel 7.3-alice
            Window("My bank - Firefox", firefox) and Window("Other title", foot)
            Wi-Fi "Cafe Net" ssid = "Home 5G" device "Pixel of Bob"
            mail bob@example.org mac aa:bb:cc:dd:ee:ff ip 192.168.1.20
            key 0123456789abcdef0123456789abcdef0123456789abcdef here
            """.trimIndent(),
        )
        for (gone in listOf("hunter2", "abc123", "zzz", "bob:pw", "abcdefghijkl0123", "alice", "carol", "My bank", "Other title", "Cafe Net", "Home 5G", "Pixel of Bob", "bob@", "aa:bb", "192.168", "0123456789abcdef0123")) {
            assertFalse(gone in cleaned, "\"$gone\" is still in:\n$cleaned")
        }
        assertTrue("~/.config/newm/config.kts" in cleaned, cleaned)
    }

    @Test
    fun `quoted text goes in private areas only`() {
        val windows = redactor.clean("12:00:00.000     1.234 I windows  opened \"secret plans\"")
        assertTrue("secret plans" !in windows, windows)
        val config = redactor.clean("12:00:00.000     1.234 W config   colors.source \"nonsense\" is not a colour")
        assertTrue("nonsense" in config, config)
    }

    @Test
    fun `what is needed to act stays`() {
        val line = "at dev.caulis.core.Desktop.frame(Desktop.kt:12) DP-1 3840x2160 config.kts:12 JDK 27.0.1.3.8 Mesa 25.1.2"
        assertEquals(line, redactor.clean(line))
    }

    @Test
    fun `a log file over its limit moves aside and the oldest goes`() {
        val dir = Files.createTempDirectory("log")
        Log.toConsole = false
        Log.fileLimitBytes = 1000
        Log.fileKeep = 2
        Log.file = dir.resolve("x.log")
        val log = Logger("test")
        repeat(100) { log.fatal("line $it " + "x".repeat(100)) }
        assertTrue(Files.size(dir.resolve("x.log")) <= 1200)
        assertTrue(Files.exists(dir.resolve("x.log.1")))
        assertTrue(Files.exists(dir.resolve("x.log.2")))
        assertFalse(Files.exists(dir.resolve("x.log.3")))
        assertTrue("line 99" in Files.readString(dir.resolve("x.log")) + Files.readString(dir.resolve("x.log.1")))
    }

    @Test
    fun `the file can have more than the console`() {
        val dir = Files.createTempDirectory("log")
        val shown = ArrayList<String>()
        Log.toConsole = false
        Log.listener = { shown += it }
        Log.fileLevel = 2
        Log.file = dir.resolve("x.log")
        Logger("test").info("in the file only")
        Log.listener = null
        assertTrue("in the file only" in Files.readString(dir.resolve("x.log")))
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a crash report says what, on what, and what came before`() {
        val dir = Files.createTempDirectory("crash")
        Log.toConsole = false
        CrashReports.folder = dir
        CrashReports.fact("Caulis", "9.9")
        CrashReports.state = { "2 windows" }
        CrashReports.privateWords = { listOf("Secret Title") }
        Logger("test").info("before the crash, Secret Title")
        val file = CrashReports.write("It broke", IllegalStateException("boom"), Thread.currentThread())!!
        assertEquals(setOf("rw-------"), setOf(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file))))
        val text = Files.readString(file)
        for (needed in listOf("It broke", "boom", "ReportsTest", "Caulis: 9.9", "Kernel: ", "JDK: ", "2 windows", "before the crash")) assertTrue(needed in text, "$needed:\n$text")
        assertFalse("Secret Title" in text)
    }

    @Test
    fun `old reports go`() {
        val dir = Files.createTempDirectory("crash")
        Log.toConsole = false
        CrashReports.folder = dir
        CrashReports.keep = 2
        try {
            repeat(4) { CrashReports.write("n$it") }
            assertEquals(2, CrashReports.list().size)
        } finally {
            CrashReports.keep = 10
        }
    }

    @Test
    fun `a problem has its stack in the black box`() {
        Log.toConsole = false
        Logger("test").error("Stack please", IllegalStateException("x"))
        assertTrue(Log.recent().last().contains("\n    at "), Log.recent().last())
    }
}
