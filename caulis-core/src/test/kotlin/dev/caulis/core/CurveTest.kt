package dev.caulis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bézier and spring curves, with the numbers Hyprland and CSS use. */
class CurveTest {

    @Test
    fun `a bezier curve as CSS draws it`() {
        val straight = Curve.bezier(0.0, 0.0, 1.0, 1.0)
        for (t in listOf(0.1, 0.5, 0.9)) assertEquals(t, straight.at(t), 1e-4)
        val ease = Curve.bezier(0.25, 0.1, 0.25, 1.0) // CSS "ease"
        assertEquals(0.8024, ease.at(0.5), 1e-3)
        assertEquals(0.0, ease.at(0.0))
        assertEquals(1.0, ease.at(1.0))
        val lively = Curve.bezier(0.05, 0.9, 0.1, 1.05) // Hyprland's "myBezier"
        assertTrue((0..100).map { lively.at(it / 100.0) }.max() > 1.0, "it overshoots")
    }

    @Test
    fun `a spring takes its own time and ends at rest`() {
        val soft = Curve.spring(stiffness = 100.0, damping = 20.0)
        val bouncy = Curve.spring(stiffness = 400.0, damping = 8.0)
        assertTrue(soft.naturalMillis!! in 100..2000, "${soft.naturalMillis}")
        assertTrue((0..200).map { bouncy.at(it / 200.0) }.max() > 1.1, "a weakly held spring bounces past the end")
        assertTrue((0..200).map { soft.at(it / 200.0) }.max() <= 1.001, "a well held one does not")
        assertEquals(0.0, soft.at(0.0))
        assertEquals(1.0, soft.at(1.0))
        assertEquals(1.0, bouncy.at(0.999), 0.01)
    }
}
