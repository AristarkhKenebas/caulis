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

    @Test
    fun `settle starts from rest, is soon nearly there, ends exactly`() {
        val c = Curve.SETTLE
        assertEquals(0.0, c.at(0.0))
        assertEquals(1.0, c.at(1.0))
        assertTrue(c.at(0.01) < 0.01, "from rest")
        assertTrue(c.at(0.4) > 0.9, "nine tenths at two fifths: ${c.at(0.4)}")
        assertTrue((0..100).map { c.at(it / 100.0) }.zipWithNext().all { (a, b) -> b >= a }, "only onward")
        assertEquals(c, Curve.named("settle"))
    }

    @Test
    fun `what a speed adds rises as fast as the time, then dies out`() {
        for (c in listOf(Curve.SETTLE, Curve.LINEAR, Curve.spring(800.0, 56.0), Curve.spring(400.0, 8.0), Curve.spring(400.0, 80.0))) {
            assertEquals(0.0, c.carried(0.0))
            assertEquals(0.0, c.carried(1.0))
            assertEquals(1.0, c.carried(1e-5) / 1e-5, 0.01, "its slope at the start")
            assertTrue(Math.abs(c.carried(0.999)) < 0.01, "nothing left at the end: ${c.carried(0.999)}")
        }
    }

    @Test
    fun `a spring by its ratio, and a slowdown of everything`() {
        val still = Curve.springRatio(1.0, 800.0)
        assertTrue(still.naturalMillis!! in 250..450, "${still.naturalMillis}")
        assertTrue((0..200).map { still.at(it / 200.0) }.max() <= 1.0005, "no bounce")
        assertTrue((0..200).map { Curve.springRatio(0.4, 800.0).at(it / 200.0) }.max() > 1.1, "a bounce")
        val tree = AnimationTree(listOf(AnimationNode("a", millis = 100)))
        assertEquals(500L, AnimationSettings(tree, true, slowdown = 5.0).resolve("a").millis)
        assertEquals(1000L, AnimationSettings(tree, true, slowdown = 5.0).resolve("a", 200).millis)
        assertEquals(500L, AnimationSettings(tree, true, slowdown = 5.0).with("a", AnimationEntry.ON).resolve("a").millis, "kept by with")
    }
}
