package dev.caulis.core.appearance

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dark and light by the clock, and by the sun. */
class ColorScheduleTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = LocalDate.of(2026, 6, 21)) = ZonedDateTime.of(date, LocalTime.of(hour, minute), berlin)

    @Test
    fun `fixed times - dark over midnight, the next change named`() {
        val schedule = ColorSchedule.Times(dark = LocalTime.of(20, 0), light = LocalTime.of(7, 0))
        assertEquals(ColorScheme.LIGHT to at(20), schedule.at(at(12)))
        assertEquals(ColorScheme.DARK to at(7, date = LocalDate.of(2026, 6, 22)), schedule.at(at(21)))
        assertEquals(ColorScheme.DARK to at(7), schedule.at(at(3)))
        assertEquals(ColorScheme.DARK, schedule.at(at(20)).first, "from the minute it says")
        // Dark during the day (a night shift) works the same way round.
        val day = ColorSchedule.Times(dark = LocalTime.of(9, 0), light = LocalTime.of(17, 0))
        assertEquals(ColorScheme.DARK to at(17), day.at(at(12)))
        assertEquals(ColorScheme.LIGHT to at(9, date = LocalDate.of(2026, 6, 22)), day.at(at(18)))
    }

    @Test
    fun `sunrise and sunset in Berlin on the longest day, within two minutes`() {
        val sun = SunTimes.on(LocalDate.of(2026, 6, 21), 52.52, 13.405) as SunTimes.RiseAndSet
        // (Almanac: 04:43 and 21:33 local, CEST = UTC+2.)
        fun near(expected: String, actual: Instant) = assertTrue(abs(Duration.between(Instant.parse(expected), actual).toMinutes()) <= 2, "$actual, not about $expected")
        near("2026-06-21T02:43:00Z", sun.rise)
        near("2026-06-21T19:33:00Z", sun.set)
        val schedule = ColorSchedule.Sun(52.52, 13.405)
        assertEquals(ColorScheme.LIGHT, schedule.at(at(12)).first)
        assertEquals(ColorScheme.DARK, schedule.at(at(23)).first)
        assertEquals(ColorScheme.DARK, schedule.at(at(3)).first)
        val (_, next) = schedule.at(at(12))
        assertTrue(abs(Duration.between(at(21, 33), next).toMinutes()) <= 2, "the next change is sunset: $next")
    }

    @Test
    fun `far north - light all of the polar day, dark all of the polar night`() {
        val tromso = ColorSchedule.Sun(69.65, 18.96)
        val zone = ZoneId.of("Europe/Oslo")
        assertEquals(SunTimes.AlwaysUp, SunTimes.on(LocalDate.of(2026, 6, 21), 69.65, 18.96))
        assertEquals(ColorScheme.LIGHT, tromso.at(ZonedDateTime.of(2026, 6, 21, 1, 0, 0, 0, zone)).first)
        assertEquals(SunTimes.AlwaysDown, SunTimes.on(LocalDate.of(2026, 12, 21), 69.65, 18.96))
        val (scheme, next) = tromso.at(ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, zone))
        assertEquals(ColorScheme.DARK, scheme)
        assertTrue(next.isAfter(ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, zone)), "asked again later: $next")
    }
}
