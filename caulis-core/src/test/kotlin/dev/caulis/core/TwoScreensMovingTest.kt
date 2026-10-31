package dev.caulis.core

import dev.caulis.geometry.Rect
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Something moving with two screens: one scene serves both at one moment, and the last look is never stale. */
class TwoScreensMovingTest {

    @Test
    fun `fewer scenes than refreshes, and both screens end right`() {
        ScreenTestServer(monitorCount = 2).use { server ->
            val shown = ConcurrentHashMap<String, IntArray>()
            server.onFrameShown = { name, pixels -> shown[name] = pixels }
            val until = System.nanoTime() + 500_000_000
            var refreshes = 0
            server.onServer { d ->
                d.sceneBuilder.takeBuilds()
                d.backgroundChangeMillis = 0
                d.background = Background.gradient(dev.caulis.render.Color.hex("#102030"), dev.caulis.render.Color.hex("#f0e0d0"), degrees = 30.0)
                d.addDrawing(DrawingLevel.ON_TOP, object : Drawing {
                    override fun animating(now: Long): Boolean {
                        refreshes++
                        return now < until
                    }

                    override fun draw(monitor: Monitor, canvas: OverlayCanvas) {
                        val left = ((System.nanoTime() - until + 500_000_000) / 2_000_000).toInt().coerceIn(0, 300)
                        canvas.fill(Rect(monitor.area.x + left, 100, 50, 50), 1f, 0f, 0f)
                    }
                })
            }
            Thread.sleep(800)
            val (built, asked) = server.onServer { d -> d.sceneBuilder.takeBuilds() to refreshes }
            println("$built scenes for $asked refreshes")
            assertTrue(built < asked * 3 / 4, "$built scenes for $asked refreshes")
            // (Time-dependent only while moving: at rest the same picture again.)
            assertContentEquals(server.screenshot(0), shown["TEST-1"], "the first screen's last frame")
            assertContentEquals(server.screenshot(1), shown["TEST-2"], "the second screen's last frame")
        }
    }
}
