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
}
