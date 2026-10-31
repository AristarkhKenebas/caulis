package dev.caulis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** An answer once worked out is kept, but never given for another question. */
class AnimationResolveTest {
    private val tree = AnimationTree(listOf(AnimationNode("windows"), AnimationNode("windowsIn", parent = "windows")))

    @Test
    fun `the same question gets the same answer, another its own`() {
        val settings = AnimationSettings(tree, enabled = true, millis = 100)
        assertSame(settings.resolve("windowsIn", 300L, Curve.LINEAR), settings.resolve("windowsIn", 300L, Curve.LINEAR))
        assertEquals(300L, settings.resolve("windowsIn", 300L).millis)
        assertEquals(500L, settings.resolve("windowsIn", 500L).millis)
        assertEquals(100L, settings.resolve("windowsIn").millis)
        assertEquals(100L, settings.resolve("windows").millis)
        assertEquals(300L, settings.resolve("windowsIn", 300L).millis)
    }

    @Test
    fun `settings made with a new entry do not keep the old answers`() {
        val settings = AnimationSettings(tree, enabled = true, millis = 100)
        assertEquals(100L, settings.resolve("windowsIn").millis)
        assertEquals(700L, settings.with("windows", AnimationEntry(millis = 700L)).resolve("windowsIn").millis)
    }
}
