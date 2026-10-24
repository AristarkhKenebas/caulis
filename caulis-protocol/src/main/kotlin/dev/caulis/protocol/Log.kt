package dev.caulis.protocol

/** A small wrapper over Java's built-in logger, so log calls stay short. */
class Log(name: String) {
    private val logger = System.getLogger(name)

    fun info(message: String) = logger.log(System.Logger.Level.INFO, message)
    fun warn(message: String) = logger.log(System.Logger.Level.WARNING, message)
    fun error(message: String, error: Throwable? = null) =
        logger.log(System.Logger.Level.ERROR, message, error)
}
