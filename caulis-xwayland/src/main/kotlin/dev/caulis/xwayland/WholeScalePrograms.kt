package dev.caulis.xwayland

import java.nio.file.Files
import java.nio.file.Path

/**
 * Programs that draw only at whole scales, and at which: Java's AWT on X11 (Swing, AWT
 * programs, most Java desktop programs) has no fractional scale at all — even
 * `-Dsun.java2d.uiScale=1.5` becomes 1 there.
 */
object WholeScalePrograms {

    /**
     * The whole scale process [pid] draws at if it only knows whole ones, or null if it is
     * not such a program (or is gone). Read from the process itself: a Java program is one
     * that loaded AWT for X11, and it chooses its scale as OpenJDK does — J2D_UISCALE, else
     * GDK_SCALE, each cut to a whole number, else 1.
     */
    @JvmStatic
    fun scaleOf(pid: Int): Int? {
        if (!loaded(pid, "libawt_xawt.so")) return null
        val environment = environmentOf(pid)
        return wholeScale(environment["J2D_UISCALE"]) ?: wholeScale(environment["GDK_SCALE"]) ?: 1
    }

    // As OpenJDK reads them: a number, cut to a whole one; below 1 means "not set".
    private fun wholeScale(value: String?): Int? {
        val number = value?.trim()?.let { Regex("^[+-]?[0-9]*\\.?[0-9]+([eE][+-]?[0-9]+)?").find(it)?.value?.toDoubleOrNull() } ?: return null
        return if (number < 1) null else number.toInt()
    }

    private fun loaded(pid: Int, library: String): Boolean = runCatching {
        Files.newBufferedReader(Path.of("/proc/$pid/maps")).use { maps -> maps.lineSequence().any { it.endsWith("/$library") } }
    }.getOrDefault(false)

    private fun environmentOf(pid: Int): Map<String, String> = runCatching {
        Files.readAllBytes(Path.of("/proc/$pid/environ")).decodeToString().split('\u0000')
            .mapNotNull { entry -> entry.indexOf('=').takeIf { it > 0 }?.let { entry.substring(0, it) to entry.substring(it + 1) } }
            .toMap()
    }.getOrDefault(emptyMap())
}
