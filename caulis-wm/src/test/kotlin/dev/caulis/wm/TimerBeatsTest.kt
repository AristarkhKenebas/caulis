package dev.caulis.wm

import kotlin.test.Test
import kotlin.test.assertEquals

class TimerBeatsTest {
    @Test
    fun `short timers as they are`() {
        assertEquals(200, timerDelayMillis(200, 12345, first = false))
        assertEquals(200, timerDelayMillis(200, 12345, first = true))
    }

    @Test
    fun `timers of a second or more meet on the beat`() {
        assertEquals(655, timerDelayMillis(1000, 12345, first = false))
        assertEquals(1000, timerDelayMillis(1000, 12000, first = false), "on the beat: the next one")
        // Started at different moments, they turn at the same one.
        assertEquals(13000L, 12345 + timerDelayMillis(1000, 12345, first = false))
        assertEquals(13000L, 12999 + timerDelayMillis(1000, 12999, first = false))
    }

    @Test
    fun `the first turn after a whole period at least`() {
        assertEquals(1655, timerDelayMillis(1000, 12345, first = true))
        assertEquals(1000, timerDelayMillis(1000, 12000, first = true))
    }
}
