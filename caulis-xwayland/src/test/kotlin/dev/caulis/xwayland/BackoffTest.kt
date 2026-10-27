package dev.caulis.xwayland

import kotlin.test.Test
import kotlin.test.assertEquals

/** The wait between tries to start a failing Xwayland grows, up to its limit, never negative. */
class BackoffTest {

    @Test
    fun `doubles after each failure up to the limit`() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 10_000L), (1..5).map { backoff(1000, 10_000, it) })
    }

    @Test
    fun `huge settings do not overflow into a negative wait`() {
        assertEquals(Long.MAX_VALUE, backoff(Long.MAX_VALUE / 3, Long.MAX_VALUE, 40))
        assertEquals(5000L, backoff(Long.MAX_VALUE, 5000, 3))
        assertEquals(0L, backoff(1000, 0, 3))
    }
}
