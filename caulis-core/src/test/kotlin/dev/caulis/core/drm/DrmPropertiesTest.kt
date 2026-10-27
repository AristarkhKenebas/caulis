package dev.caulis.core.drm

import kotlin.test.Test
import kotlin.test.assertEquals

/** A property the driver refuses along with a mode set is left out alone; the others still apply. */
class DrmPropertiesTest {

    @Test
    fun `one refused property does not take the others with it`() {
        val refused = ArrayList<String>()
        val taken = takenAlong(listOf("max bpc", "VRR_ENABLED", "Colorspace"), { "VRR_ENABLED" !in it }) { p, _ -> refused += p }
        assertEquals(listOf("max bpc", "Colorspace"), taken)
        assertEquals(listOf("VRR_ENABLED"), refused)
    }

    @Test
    fun `all taken together, none tested alone`() {
        var tests = 0
        assertEquals(listOf("a", "b"), takenAlong(listOf("a", "b"), { tests++; true }) { _, _ -> error("none refused") })
        assertEquals(1, tests)
    }

    @Test
    fun `a mode that fails without them too does not blame them`() {
        assertEquals(listOf("a"), takenAlong(listOf("a"), { false }) { _, _ -> error("none refused") })
    }

    @Test
    fun `taken alone but not together, all are left out`() {
        val refused = ArrayList<Pair<String, Boolean>>()
        assertEquals(emptyList(), takenAlong(listOf("a", "b"), { it.size < 2 }) { p, alone -> refused += p to alone })
        assertEquals(listOf("a" to false, "b" to false), refused)
    }
}
