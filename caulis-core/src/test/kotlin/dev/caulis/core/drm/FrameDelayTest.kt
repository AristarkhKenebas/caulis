package dev.caulis.core.drm

import kotlin.test.Test
import kotlin.test.assertEquals

/** When a picture is begun: with maxRenderMillis, that long before the next refresh. */
class FrameDelayTest {
    private val refresh = 16_000_000L
    private val flip = 1_000_000_000L

    @Test
    fun `off, until the next refresh is due`() {
        assertEquals(16, frameDelayMillis(flip, refresh, flip, 0))
        assertEquals(6, frameDelayMillis(flip, refresh, flip + 10_000_000, 0))
        assertEquals(0, frameDelayMillis(flip, refresh, flip + 50_000_000, 0))
    }

    @Test
    fun `on, so long before the next refresh ahead`() {
        assertEquals(10, frameDelayMillis(flip, refresh, flip, 6))
        assertEquals(0, frameDelayMillis(flip, refresh, flip + 10_000_000, 6), "just in time")
        assertEquals(0, frameDelayMillis(flip, refresh, flip + 12_000_000, 6), "too late for this one: at once")
        assertEquals(6, frameDelayMillis(flip, refresh, flip + 20_000_000, 6), "at rest for long: the refresh after")
    }

    @Test
    fun `never more than a refresh`() {
        assertEquals(0, frameDelayMillis(flip, refresh, flip + 1_000_000, 500))
    }
}
