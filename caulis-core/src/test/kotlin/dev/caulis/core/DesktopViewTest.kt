package dev.caulis.core

import dev.caulis.render.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopViewTest {

    private val screen = Rect(0, 0, 100, 100)

    @Test
    fun `the first frame draws everything, then only changes`() {
        val view = DesktopView()
        assertTrue(view.hasChanges)
        assertEquals(screen, view.takeRepaint(age = 0, bounds = screen))
        assertFalse(view.hasChanges)
        assertNull(view.takeRepaint(age = 1, bounds = screen), "nothing changed, nothing to draw")

        view.damage(Rect(10, 10, 5, 5))
        assertEquals(Rect(10, 10, 5, 5), view.takeRepaint(age = 1, bounds = screen))
    }

    @Test
    fun `an older buffer also gets what changed in the frames it missed`() {
        val view = DesktopView()
        view.takeRepaint(0, screen) // frame 1: everything
        view.damage(Rect(0, 0, 10, 10))
        view.takeRepaint(1, screen) // frame 2
        view.damage(Rect(50, 50, 10, 10))
        // Frame 3 into a buffer last drawn in frame 1 (age 2): it misses frame 2's change too.
        assertEquals(Rect(0, 0, 60, 60), view.takeRepaint(2, screen))
    }

    @Test
    fun `unknown or very old buffers are drawn completely`() {
        val view = DesktopView()
        view.takeRepaint(0, screen)
        view.damage(Rect(1, 1, 1, 1))
        assertEquals(screen, view.takeRepaint(age = 9, bounds = screen))
        view.damage(Rect(1, 1, 1, 1))
        assertEquals(screen, view.takeRepaint(age = 0, bounds = screen))
    }

    @Test
    fun `changes outside the screen are cut off`() {
        val view = DesktopView()
        view.takeRepaint(0, screen)
        view.damage(Rect(90, 90, 50, 50))
        assertEquals(Rect(90, 90, 10, 10), view.takeRepaint(1, screen))
    }
}
