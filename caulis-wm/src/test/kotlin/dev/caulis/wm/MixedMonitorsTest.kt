package dev.caulis.wm

import dev.caulis.core.Transform
import dev.caulis.protocol.client.xdgoutputunstablev1.ZxdgOutputManagerV1
import dev.caulis.protocol.client.xdgoutputunstablev1.ZxdgOutputV1
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A wide monitor (2560x1440 at 1.25) and one standing on its side (1920x1080 turned 90°)
 * beside it: what programs are told, and where and how big their windows are on each.
 */
class MixedMonitorsTest {

    private val harness = CaulisTest()

    private fun Caulis.twoMonitors() {
        headlessMonitors = 2
        monitor("HEADLESS-1") { mode(2560, 1440); scale = 1.25 }
        monitor("HEADLESS-2") { mode(1920, 1080); scale = 1.0; transform = Transform.ROTATE_90 }
    }

    @Test
    fun `programs are told the right sizes and places`() = with(harness) {
        withCaulis({ twoMonitors() }, headless = true) { caulis, client ->
            caulis.onLoop { r ->
                val (wide, tall) = r.desktop.monitors
                assertEquals(dev.caulis.geometry.Rect(0, 0, 2048, 1152), wide.area, "2560x1440 at 1.25")
                assertEquals(dev.caulis.geometry.Rect(2048, 0, 1080, 1920), tall.area, "standing: 1080 wide, 1920 high, right beside")
            }
            // (Version 2: there the name is always told.)
            val xdg = client.bind("zxdg_output_manager_v1", ZxdgOutputManagerV1, 2)
            val logical = HashMap<String, List<Int>>()
            client.outputs.forEach { output ->
                val seen = intArrayOf(0, 0, 0, 0)
                xdg.getXdgOutput(output).events = object : ZxdgOutputV1.Events {
                    override fun logicalPosition(proxy: ZxdgOutputV1, x: Int, y: Int) { seen[0] = x; seen[1] = y }
                    override fun logicalSize(proxy: ZxdgOutputV1, width: Int, height: Int) { seen[2] = width; seen[3] = height }
                    override fun name(proxy: ZxdgOutputV1, name: String) { logical[name] = seen.toList() }
                    override fun done(proxy: ZxdgOutputV1) {}
                }
            }
            client.waitFor { logical.size == 2 }
            // (The name comes after the size and place: they are complete by then.)
            assertEquals(listOf(0, 0, 2048, 1152), logical["HEADLESS-1"])
            assertEquals(listOf(2048, 0, 1080, 1920), logical["HEADLESS-2"])
        }
    }

    @Test
    fun `windows fit each monitor, maximized and fullscreen`() = with(harness) {
        withCaulis({ twoMonitors() }, headless = true) { caulis, client ->
            val window = client.window().also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow != null } }
            caulis.onLoop { r ->
                val w = r.desktop.focusedWindow!!
                r.desktop.moveToMonitor(w, r.desktop.monitors[1])
                r.desktop.setMaximized(w, true)
            }
            client.waitFor { window.width == 1080 && window.height == 1920 }
            window.draw()
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow!!.let { it.x == 2048 && it.y == 0 } } }

            caulis.onLoop { r ->
                val w = r.desktop.focusedWindow!!
                r.desktop.setMaximized(w, false)
                r.desktop.moveToMonitor(w, r.desktop.monitors[0])
                r.desktop.setFullscreen(w, true)
            }
            client.waitFor { window.width == 2048 && window.height == 1152 }
        }
    }

    @Test
    fun `a screenshot of the monitor on its side is upright`() = with(harness) {
        val grim = System.getenv("PATH").orEmpty().split(':').map { Path.of(it, "grim") }.firstOrNull { Files.isExecutable(it) }
        assumeTrue(grim != null, "no grim")
        withCaulis({
            twoMonitors()
            background = dev.caulis.core.Background.color(dev.caulis.render.Color.hex("#ff0000"))
            effects = dev.caulis.core.Effects.NONE
            titleBars = dev.caulis.core.TitleBars.NONE
            // A black strip at the top of the monitor on its side.
            rule { appId("strip"); monitor = "HEADLESS-2"; floating = true; size(1080, 300); position(0, 0) }
        }, headless = true) { caulis, client ->
            client.window { it.setAppId("strip") }.draw(1080, 300)
            client.waitFor { caulis.onLoop { r -> r.desktop.allWindows.any { it.appId == "strip" && it.mapped } } }
            Thread.sleep(300)
            val socket = caulis.socketDirectory!!.resolve(caulis.onLoop { it.socket })
            val file = Files.createTempFile("tall", ".png")
            try {
                val process = ProcessBuilder(grim.toString(), "-o", "HEADLESS-2", file.toString()).apply {
                    environment()["WAYLAND_DISPLAY"] = socket.toString()
                    environment()["XDG_RUNTIME_DIR"] = socket.parent.toString()
                    redirectErrorStream(true)
                }.start()
                assertEquals(true, process.waitFor(10, TimeUnit.SECONDS))
                val out = process.inputStream.readAllBytes().decodeToString()
                assertEquals(0, process.exitValue(), out)
                val image = javax.imageio.ImageIO.read(file.toFile())
                assertEquals(1080 to 1920, image.width to image.height, "upright: as wide as the monitor stands")
                assertEquals(0x000000, image.getRGB(540, 100) and 0xFFFFFF, "the strip is at the top")
                assertEquals(0xFF0000, image.getRGB(540, 1500) and 0xFFFFFF, "the background below it")
                assertEquals(0xFF0000, image.getRGB(1000, 1000) and 0xFFFFFF, "not at a side")
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }
}
