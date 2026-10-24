package dev.caulis.ffi

/**
 * Guards code that C calls back into (upcalls: libseat, libinput, libdrm callbacks).
 *
 * An exception must never travel back into C: the JVM would stop the whole process.
 * So every callback body runs inside [guard]; an error is logged and C gets [fallback].
 */
object Upcalls {
    private val logger = System.getLogger("caulis.native")

    inline fun <T> guard(name: String, fallback: T, body: () -> T): T = try {
        body()
    } catch (e: Throwable) {
        report(name, e)
        fallback
    }

    inline fun guard(name: String, body: () -> Unit) = guard(name, Unit, body)

    fun report(name: String, error: Throwable) =
        logger.log(System.Logger.Level.ERROR, "Error in callback $name (ignored)", error)
}
