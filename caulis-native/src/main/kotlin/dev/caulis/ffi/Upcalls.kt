package dev.caulis.ffi

import dev.caulis.log.InternalCaulisApi

/**
 * Guards code that C calls back into (upcalls: libseat, libinput, libdrm callbacks).
 *
 * An exception must never travel back into C: the JVM would stop the whole process.
 * So every callback body runs inside [guard]; an error is logged and C gets `fallback`.
 */
@InternalCaulisApi
object Upcalls {
    private val logger = dev.caulis.log.Logger("native")

    inline fun <T> guard(name: String, fallback: T, body: () -> T): T = try {
        body()
    } catch (e: Throwable) {
        report(name, e)
        fallback
    }

    inline fun guard(name: String, body: () -> Unit) = guard(name, Unit, body)

    fun report(name: String, error: Throwable) =
        logger.error("Error in callback $name (ignored)", error)
}
