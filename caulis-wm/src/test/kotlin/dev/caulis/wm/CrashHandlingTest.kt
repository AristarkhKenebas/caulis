package dev.caulis.wm

import dev.caulis.log.CrashReports
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An exception nothing caught leaves a report, once, and is told to [Caulis.onCrashReport]. */
class CrashHandlingTest {

    @Test
    fun `a thread that dies leaves a report`() {
        val folder = Files.createTempDirectory("crashes")
        val told = java.util.concurrent.CopyOnWriteArrayList<java.nio.file.Path>()
        val config = Caulis().apply {
            crashReportFolder = folder
            onCrashReport = { told.add(it) }
        }
        CrashHandling.install(config)
        repeat(2) {
            Thread({ failHere() }, "a-helper").apply { start(); join() }
        }
        val reports = CrashReports.list(folder)
        assertEquals(1, reports.size, "the same failure twice is one report")
        assertEquals(reports, told.toList())
        val text = Files.readString(reports.single())
        for (needed in listOf("a-helper", "the thing broke", "failHere", "Caulis: ", "JDK: ", "Kernel: ")) assertTrue(needed in text, "$needed in:\n$text")
    }

    @Test
    fun `reports can be turned off`() {
        CrashHandling.install(Caulis().apply { crashReports = false })
        assertEquals(null, CrashReports.folder)
    }

    private fun failHere(): Nothing = error("the thing broke")
}
