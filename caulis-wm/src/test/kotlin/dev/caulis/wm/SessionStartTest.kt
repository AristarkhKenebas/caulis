package dev.caulis.wm

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The start in a running compositor (headless): dark until warm, then autostart, once per session. */
class SessionStartTest {

    private val harness = CaulisTest()

    @Test
    fun `the desktop fades in, then the programs start one by one, and a reload starts none again`() = with(harness) {
        val folder = Files.createTempDirectory("caulis-autostart")
        val out = folder.resolve("started.txt")
        val script = folder.resolve("start.sh").also {
            Files.writeString(it, "#!/bin/sh\necho script \"\$1\" >> '$out'\n")
            it.toFile().setExecutable(true)
        }
        val events = CopyOnWriteArrayList<String>()
        fun Caulis.startup() {
            reveal {
                enabled = true
                fadeMillis = 100
                maxWaitMillis = 3000
            }
            whenWarm("test work") { done -> events += "warm work"; after(200) { events += "warm done"; done() } }
            onReveal { events += "revealed" }
            autostart {
                staggerMillis = 50
                shell("echo one >> '$out'")
                app("/bin/sh", "-c", "echo two \$X >> '$out'") { env("X", "x"); after("echo") }
                script(script.toString(), "three")
            }
        }
        try {
            withCaulis({
                startup()
                startLater(listOf("/bin/sh", "-c", "echo restored >> '$out'"))
            }, headless = true) { caulis, client ->
                client.waitFor { Files.exists(out) && Files.readAllLines(out).size == 4 }
                assertEquals(listOf("one", "two x", "script three", "restored"), Files.readAllLines(out), "in order, after the reveal")
                assertEquals(listOf("warm work", "warm done", "revealed"), events, "shown only once the work was done")
                assertTrue(caulis.onLoop { it.config.revealed })
                val status = caulis.onLoop { it.config.autostartStatus() }
                assertEquals(4, status.size, "$status")

                // A reload (the same entries, and one more): only the new one starts.
                val next = caulis.reloaded {
                    startup()
                    autostart { shell("echo four >> '$out'") }
                }
                caulis.onLoop { it.reconfigure(next) }
                client.waitFor { Files.readAllLines(out).size == 5 }
                Thread.sleep(300)
                assertEquals(listOf("one", "two x", "script three", "restored", "four"), Files.readAllLines(out))
                assertEquals(listOf("warm work", "warm done", "revealed"), events, "no second reveal")
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
