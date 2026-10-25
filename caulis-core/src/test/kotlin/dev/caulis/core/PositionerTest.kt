package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgPositioner
import kotlin.test.Test
import kotlin.test.assertEquals

/** Menus kept on the screen: turned over, slid along the edge, or made smaller, as the program allows. */
class PositionerTest {

    // A 200x300 menu at a click point (x 500, y 900; the point is one unit big, so a menu turned
    // over ends at its far side) of a window whose screen ends at y 1000.
    private fun menu(adjustment: Int) = Positioner.Rules(
        width = 200, height = 300, anchorRect = Rect(500, 900, 1, 1),
        anchor = XdgPositioner.Anchor.TOP_LEFT, gravity = XdgPositioner.Gravity.BOTTOM_RIGHT, adjustment = adjustment,
    )

    private val screen = Rect(0, 0, 1920, 1000)

    @Test
    fun `near the bottom a menu that may turn over opens upwards`() {
        assertEquals(Rect(500, 601, 200, 300), menu(FLIP_Y or SLIDE_Y).place(screen))
    }

    @Test
    fun `one that may only slide is moved up to the edge`() {
        assertEquals(Rect(500, 700, 200, 300), menu(SLIDE_Y).place(screen))
    }

    @Test
    fun `one that may only shrink is made shorter`() {
        assertEquals(Rect(500, 900, 200, 100), menu(RESIZE_Y).place(screen))
    }

    @Test
    fun `without permission it stays where it was asked to be`() {
        assertEquals(Rect(500, 900, 200, 300), menu(0).place(screen))
    }

    @Test
    fun `at the right edge it turns to the left`() {
        val rules = menu(FLIP_X or FLIP_Y).apply { anchorRect = Rect(1850, 100, 1, 1) }
        assertEquals(Rect(1651, 100, 200, 300), rules.place(screen))
    }

    private companion object {
        const val SLIDE_Y = 2
        const val FLIP_X = 4
        const val FLIP_Y = 8
        const val RESIZE_Y = 32
    }
}
