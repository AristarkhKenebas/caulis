package dev.caulis.core

import dev.caulis.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a frame redraws: a few parts apart from each other, not one box around everything. */
class DamageTest {

    private val bounds = Rect(0, 0, 1920, 1080)

    @Test
    fun `a turning border redraws its frame, not the window inside`() {
        val view = DesktopView(Monitor("TEST", null, 1920, 1080, 1.0))
        view.takeRepaintParts(0, bounds) // the first frame: everything
        val border = DrawItem.Border("border", Rect(100, 100, 1000, 800), 12f, 3f, dev.caulis.render.Paint.rainbow())
        border.areas.forEach(view::damage)
        val parts = view.takeRepaintParts(1, bounds)!!
        assertEquals(4, parts.size, "$parts")
        val area = parts.sumOf { it.width.toLong() * it.height }
        assertTrue(area < 1000L * 800 / 10, "a thin frame: $area of ${1000 * 800}")
    }

    @Test
    fun `overlapping changes become one, far ones stay apart`() {
        val view = DesktopView(Monitor("TEST", null, 1920, 1080, 1.0))
        view.takeRepaintParts(0, bounds)
        view.damage(Rect(0, 0, 10, 10))
        view.damage(Rect(5, 5, 10, 10))
        view.damage(Rect(1800, 1000, 20, 20))
        assertEquals(listOf(Rect(0, 0, 15, 15), Rect(1800, 1000, 20, 20)), view.takeRepaintParts(1, bounds)!!.sortedBy { it.x })
    }

    @Test
    fun `a moving wallpaper redraws only what windows leave of it`() {
        val wallpaper = DrawItem.Custom("background", bounds, "look", framesPerSecond = 30, wallpaper = WallpaperShader("vec4 wallpaper(vec2 at) { return vec4(at, 0.0, 1.0); }")) {}
        val window = DrawItem.Color("window", Rect(0, 0, 1200, 1080), 0f, 0f, 0f, covers = listOf(Rect(0, 0, 1200, 1080)))
        val list = listOf(wallpaper, window)
        assertEquals(listOf(Rect(1200, 0, 720, 1080)), uncoveredAreas(wallpaper, list, 0))
        // Something else (not a wallpaper shader) is redrawn whole as before.
        val plain = DrawItem.Custom("background", bounds, "look") {}
        assertEquals(listOf(bounds), uncoveredAreas(plain, listOf(plain, window), 0))
        // All of it covered: nothing.
        val full = DrawItem.Color("full", bounds, 0f, 0f, 0f, covers = listOf(bounds))
        assertEquals(emptyList(), uncoveredAreas(wallpaper, listOf(wallpaper, full), 0))
    }

    @Test
    fun `a blur's reach is redrawn whole first, the other parts stay apart`() {
        // A blurred bar at the top; the wallpaper changed in a strip on the left and one on the right.
        val reach = Rect(0, 0, 1920, 60)
        val left = Rect(0, 0, 20, 1080)
        val right = Rect(1900, 0, 20, 1080)
        val parts = withBlurParts(listOf(reach), listOf(left, right), bounds)
        assertEquals(listOf(reach, left, right), parts)
        // Untouched blurs stay out; a blur whose reach overlaps a taken one comes in too, as one part with it.
        val far = Rect(800, 500, 100, 100)
        val chained = Rect(1800, 40, 100, 100)
        assertEquals(listOf(Rect(0, 0, 1920, 140), left, right), withBlurParts(listOf(reach, far, chained), listOf(left, right), bounds))
    }
}
