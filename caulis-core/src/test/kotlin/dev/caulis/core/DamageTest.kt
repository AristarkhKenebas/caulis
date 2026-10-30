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
    fun `a moving glow redraws its ring, not the window inside`() {
        val view = DesktopView(Monitor("TEST", null, 1920, 1080, 1.0))
        view.takeRepaintParts(0, bounds) // the first frame: everything
        val box = Rect(100, 100, 1000, 800)
        val glow = DrawItem.Shadow("glow", box, 12f, 16f, dev.caulis.render.Paint.rainbow(), box, 12f, 1f, 3f)
        glow.areas.forEach(view::damage)
        val parts = view.takeRepaintParts(1, bounds)!!
        assertTrue(parts.all { it.x < 100 + 12 || it.y < 100 + 12 || it.right > 1100 - 12 || it.bottom > 900 - 12 }, "$parts")
        val area = parts.sumOf { it.width.toLong() * it.height }
        assertTrue(area < 1000L * 800 / 5, "a thin ring: $area of ${1000 * 800}")
        assertTrue(parts.any { it.x <= 100 - 16 } && parts.any { it.bottom >= 900 + 16 }, "all of its reach: $parts")
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
    fun `a still background too redraws only what windows leave of it`() {
        val background = DrawItem.Custom("background", bounds, "look", background = true) {}
        val window = DrawItem.Color("window", Rect(0, 0, 1200, 1080), 0f, 0f, 0f, covers = listOf(Rect(0, 0, 1200, 1080)))
        assertEquals(listOf(Rect(1200, 0, 720, 1080)), uncoveredAreas(background, listOf(background, window), 0))
    }

    @Test
    fun `what does not hide the background is not cut out of it`() {
        val background = DrawItem.Custom("background", bounds, "look", background = true) {}
        // A see-through or moving picture covers nothing (it says so itself), nor a shadow or a tint.
        val faded = DrawItem.Color("faded", Rect(0, 0, 500, 500), 0f, 0f, 0f, alpha = 0.5f)
        val tint = DrawItem.Tint("tint", bounds, 0f, 0f, 0f, 0f, 0.5f)
        assertEquals(listOf(bounds), uncovered(listOf(bounds), listOf(background, faded, tint), 0, 8))
        // A window sliding with its workspace: not for sure on this monitor.
        val sliding = DrawItem.Color("sliding", bounds, 0f, 0f, 0f, covers = listOf(bounds)).also { it.only = Monitor("OTHER", null, 1920, 1080, 1.0) }
        assertEquals(listOf(bounds), uncovered(listOf(bounds), listOf(background, sliding), 0, 8))
    }

    @Test
    fun `round corners stay drawn behind a window`() {
        // (As Desktop.coversOf gives them: everything but the 12 x 12 corners.)
        val covers = listOf(Rect(100, 112, 800, 576), Rect(112, 100, 776, 600))
        val window = DrawItem.Color("window", Rect(100, 100, 800, 600), 0f, 0f, 0f, covers = covers)
        val left = uncovered(listOf(Rect(100, 100, 800, 600)), listOf(window, window), 0, 16)!!
        assertEquals(setOf(Rect(100, 100, 12, 12), Rect(888, 100, 12, 12), Rect(100, 688, 12, 12), Rect(888, 688, 12, 12)), left.toSet())
    }

    @Test
    fun `what a blur reads stays drawn though a window above the blur covers it`() {
        val background = DrawItem.Custom("background", bounds, "look", background = true) {}
        val blur = DrawItem.Blur("blur", Rect(0, 0, 1920, 40), 0f, 10, 2)
        val reach = blur.reachRect()
        val window = DrawItem.Color("window", bounds, 0f, 0f, 0f, covers = listOf(bounds))
        // Window under the blur: it is what the blur reads, the background is hidden whole.
        assertEquals(emptyList(), uncovered(listOf(bounds), listOf(background, window, blur), 0, 8))
        // Window over the blur: the blur is drawn first and reads the background there.
        assertEquals(listOfNotNull(reach.intersect(bounds)), uncovered(listOf(bounds), listOf(background, blur, window), 0, 8))
    }

    @Test
    fun `too many pieces of background - left whole`() {
        val tiny = List(30) { DrawItem.Color("w$it", Rect(it * 60, 500, 10, 10), 0f, 0f, 0f, covers = listOf(Rect(it * 60, 500, 10, 10))) }
        val background = DrawItem.Custom("background", bounds, "look", background = true) {}
        assertEquals(listOf(bounds), uncoveredAreas(background, listOf<DrawItem>(background) + tiny, 0))
        assertEquals(null, uncovered(listOf(bounds), listOf<DrawItem>(background) + tiny, 0, 8))
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
