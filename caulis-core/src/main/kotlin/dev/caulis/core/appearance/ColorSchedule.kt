package dev.caulis.core.appearance

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * When the desktop turns dark and light by itself: at fixed times of day ([Times]), or at
 * sunset and sunrise of a place ([Sun]).
 *
 * ```
 * ColorSchedule.Times(dark = LocalTime.of(20, 0), light = LocalTime.of(7, 0))
 * ColorSchedule.Sun(latitude = 55.75, longitude = 37.62)   // Moscow
 * ```
 */
sealed interface ColorSchedule {

    /** The scheme at [time], and when it changes next. */
    fun at(time: ZonedDateTime): Pair<ColorScheme, ZonedDateTime>

    /** Dark from [dark] until [light] (the local time of day; over midnight as usual). */
    data class Times(val dark: LocalTime, val light: LocalTime) : ColorSchedule {
        init {
            require(dark != light) { "dark and light cannot start at the same time ($dark)" }
        }

        override fun at(time: ZonedDateTime): Pair<ColorScheme, ZonedDateTime> {
            val now = time.toLocalTime()
            val isDark = if (dark < light) now >= dark && now < light else now >= dark || now < light
            val nextAt = if (isDark) light else dark
            var next = time.with(nextAt).withNano(0)
            if (!next.isAfter(time)) next = time.plusDays(1).with(nextAt).withNano(0)
            return (if (isDark) ColorScheme.DARK else ColorScheme.LIGHT) to next
        }
    }

    /**
     * Dark from sunset until sunrise at [latitude] (north positive) and [longitude] (east
     * positive). Where the sun does not set or rise that day (far north or south), light or
     * dark all day.
     */
    data class Sun(val latitude: Double, val longitude: Double) : ColorSchedule {
        init {
            require(latitude in -90.0..90.0) { "latitude is -90 to 90, not $latitude" }
            require(longitude in -180.0..180.0) { "longitude is -180 to 180, not $longitude" }
        }

        override fun at(time: ZonedDateTime): Pair<ColorScheme, ZonedDateTime> {
            // Sunrises and sunsets around now, in order; the last one before now says the scheme.
            val day = time.toLocalDate()
            val changes = (-1L..2L).flatMap { d ->
                when (val sun = SunTimes.on(day.plusDays(d), latitude, longitude)) {
                    is SunTimes.RiseAndSet -> listOf(sun.rise to ColorScheme.LIGHT, sun.set to ColorScheme.DARK)
                    else -> emptyList()
                }
            }.sortedBy { it.first }
            val instant = time.toInstant()
            val before = changes.lastOrNull { !it.first.isAfter(instant) }
            val after = changes.firstOrNull { it.first.isAfter(instant) }
            val scheme = before?.second ?: when (SunTimes.on(day, latitude, longitude)) {
                SunTimes.AlwaysUp -> ColorScheme.LIGHT
                else -> ColorScheme.DARK
            }
            // No change soon (polar day or night): asked again tomorrow.
            val next = after?.first?.atZone(time.zone) ?: time.toLocalDate().plusDays(1).atStartOfDay(time.zone)
            return scheme to next
        }
    }
}

/** When the sun rises and sets on a day at a place (the NOAA's sunrise equation; within a minute or two). */
sealed interface SunTimes {
    data class RiseAndSet(val rise: Instant, val set: Instant) : SunTimes
    /** It does not set that day (polar day). */
    data object AlwaysUp : SunTimes
    /** It does not rise that day (polar night). */
    data object AlwaysDown : SunTimes

    companion object {
        fun on(date: LocalDate, latitude: Double, longitude: Double): SunTimes {
            // Julian days since 2000-01-01 12:00, at the place's noon.
            val julianDate = date.toEpochDay() + 2440587.5
            val n = ceil(julianDate - 2451545.0 + 0.0008)
            val meanNoon = n - longitude / 360.0
            val anomaly = Math.toRadians((357.5291 + 0.98560028 * meanNoon).mod(360.0))
            val center = 1.9148 * sin(anomaly) + 0.02 * sin(2 * anomaly) + 0.0003 * sin(3 * anomaly)
            val eclipticLongitude = Math.toRadians((Math.toDegrees(anomaly) + center + 180 + 102.9372).mod(360.0))
            val transit = 2451545.0 + meanNoon + 0.0053 * sin(anomaly) - 0.0069 * sin(2 * eclipticLongitude)
            val declination = asin(sin(eclipticLongitude) * sin(Math.toRadians(23.4397)))
            val phi = Math.toRadians(latitude)
            val cosHourAngle = (sin(Math.toRadians(-0.833)) - sin(phi) * sin(declination)) / (cos(phi) * cos(declination))
            if (cosHourAngle < -1) return AlwaysUp
            if (cosHourAngle > 1) return AlwaysDown
            val hourAngle = Math.toDegrees(acos(cosHourAngle))
            fun instant(julian: Double) = Instant.ofEpochMilli(Math.round((julian - 2440587.5) * 86_400_000))
            return RiseAndSet(instant(transit - hourAngle / 360), instant(transit + hourAngle / 360))
        }
    }
}
