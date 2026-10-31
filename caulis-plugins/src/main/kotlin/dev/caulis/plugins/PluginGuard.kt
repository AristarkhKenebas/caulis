package dev.caulis.plugins

/**
 * Runs one plugin's code: what it throws is caught and makes the plugin fail, and a call on the
 * compositor's thread is timed by the watchdog. Every hook an API gives a plugin goes through
 * [call]. Once the plugin is disabled its code is not run any more.
 */
class PluginGuard internal constructor(
    /** The plugin's id. */
    val pluginId: String,
    @PublishedApi internal val watchdog: PluginWatchdog,
    private val onFailure: (PluginGuard, String, Throwable) -> Unit,
) {
    /** False once it was disabled. */
    @Volatile
    var active: Boolean = true
        internal set

    /** Runs [body]: [fallback] when it throws or the plugin is off. [what] names it ("key Super+G") in reports. */
    inline fun <T> call(what: String, fallback: T, body: () -> T): T {
        if (!active) return fallback
        val timed = watchdog.enter(this, what)
        return try {
            body()
        } catch (e: Throwable) {
            failed(what, e)
            fallback
        } finally {
            if (timed) watchdog.leave()
        }
    }

    /** The same for Java, and for what returns nothing. */
    fun run(what: String, body: Runnable) = call(what, Unit) { body.run() }

    /** What was thrown while running it. */
    @PublishedApi
    internal fun failed(what: String, e: Throwable) {
        // (Also running out of memory: what it needed goes with it. If that was not it, the compositor's next allocation says so.)
        onFailure(this, what, e)
    }
}
