package dev.caulis.core

import dev.caulis.protocol.client.Connection
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Settings changed while running — the scale, a monitor's scale and place, the wallpaper, the
 * workspace: every frame really shown keeps the wallpaper, with no black or empty flashes.
 */
class LiveChangesTest {

    @Test
    fun `no frame loses the wallpaper or flashes while settings change`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        // (The picture below is made before any Desktop exists: as Desktop would have it.)
        System.setProperty("java.awt.headless", "true")
        val picture = Files.createTempFile("caulis-wall", ".png")
        try {
            // A wallpaper: a gradient, nowhere the empty background's colour or black.
            val image = java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 320) for (y in 0 until 200) image.setRGB(x, y, (0x40 + x * 0xa0 / 320 shl 16) or (0x60 shl 8) or (0x30 + y * 0x80 / 200))
            javax.imageio.ImageIO.write(image, "png", picture.toFile())

            ScreenTestServer(monitorCount = 2).use { server ->
                TestClient(Connection.connect(server.socketPath)).use { _ ->
                    // The worst frame of each step: how many pixels show no wallpaper.
                    val worst = ConcurrentHashMap<String, Int>()
                    val stale = ConcurrentHashMap<String, Int>()
                    var step = "the wallpaper comes"
                    server.onServer { d -> d.background = Background.image(picture, Background.Fit.FILL) }
                    val deadline = System.nanoTime() + 5_000_000_000
                    while (server.screenshot(0)[200 * ScreenTestServer.WIDTH + 200].let { it == EMPTY || it == BLACK }) {
                        check(System.nanoTime() < deadline) { "the wallpaper did not come" }
                        Thread.sleep(50)
                    }
                    Thread.sleep(300)
                    val last = ConcurrentHashMap<String, IntArray>()
                    server.onFrameShown = { monitor, pixels ->
                        val holes = pixels.count { it == EMPTY || it == BLACK }
                        worst.merge("$step ($monitor)", holes, ::maxOf)
                        last[monitor] = pixels
                    }
                    fun change(name: String, action: (Desktop) -> Unit) {
                        step = name
                        server.onServer(action)
                        Thread.sleep(1200) // the change and its animation
                        // Settled: what is shown is what drawing everything anew gives (no leftovers
                        // of the picture before, from drawing only what changed).
                        for (n in 0..1) {
                            val shown = last.getValue("TEST-${n + 1}")
                            val whole = server.screenshot(n)
                            stale.merge(name, shown.indices.count { shown[it] != whole[it] }, ::maxOf)
                        }
                    }
                    change("the scale 1 → 1.5") { d -> d.scale = 1.5 }
                    change("the second monitor's own scale") { d -> d.setScale(d.monitors[1], 1.0) }
                    change("the second monitor moved") { d -> d.moveMonitor(d.monitors[1], 500, 120) }
                    change("another wallpaper (crossfade)") { d -> d.background = Background.color(dev.caulis.render.Color(0.2f, 0.5f, 0.3f)) }
                    change("another workspace with its own wallpaper (slide)") { d ->
                        d.backgroundFor = { _, workspace -> if (workspace == 3) Background.image(picture, Background.Fit.FIT, behind = dev.caulis.render.Color(0.6f, 0.2f, 0.2f)) else null }
                        d.switchTo(3)
                    }
                    change("the scale back to 1") { d -> d.scale = 1.0 }
                    server.onFrameShown = null

                    val report = worst.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" }
                    println("Pixels without wallpaper, worst frame of each step:\n$report")
                    // (A few hundred: the cursor's own black outline.)
                    assertTrue(worst.values.all { it < 2000 }, "a frame without the wallpaper:\n$report")
                    assertTrue(worst.keys.size >= 12, "frames were seen:\n$report")
                    assertTrue(stale.values.all { it == 0 }, "pixels left over from before, after each step:\n$stale")
                }
            }
        } finally {
            Files.deleteIfExists(picture)
        }
    }

    private companion object {
        // Desktop.BACKGROUND (what shows where nothing is), and black.
        val EMPTY = 0xFF000000.toInt() or (41 shl 16) or (46 shl 8) or 56
        const val BLACK = 0xFF000000.toInt()
    }
}
