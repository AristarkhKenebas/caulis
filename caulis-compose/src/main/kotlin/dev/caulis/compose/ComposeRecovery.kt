package dev.caulis.compose

/**
 * What a [ComposeWidget] does when its content fails — an exception thrown in a composable,
 * in a `LaunchedEffect`, in a click's handler or while it is drawn. Compose stops such a scene
 * for good (nothing is composed or drawn anew in it), so the widget makes it anew: the error
 * is logged (area "compose"), [onError] is told, and after [delayMillis] the scene starts over
 * (state kept with `remember` in it too). Until then it shows what it showed last and takes no
 * input. Failing again soon after doubles the wait each time, up to [maxDelayMillis]: content
 * that fails at once does not take every frame.
 *
 * ```
 * ComposeRecovery(delayMillis = 2000, notice = null, onError = { e -> myLog(e) })
 * ```
 */
class ComposeRecovery @JvmOverloads constructor(
    /** How long after the first failure the scene starts over (milliseconds). */
    val delayMillis: Long = 500,
    /** The longest wait, however often it failed (milliseconds). */
    val maxDelayMillis: Long = 30_000,
    /** Failures further apart than this (milliseconds) count as a first one again. */
    val forgetAfterMillis: Long = 60_000,
    /**
     * A notice shown the first time the widget fails (so a mistake is seen without reading the
     * log): its text, "{error}" for what went wrong; null: none.
     */
    val notice: String? = "A part of the desktop written in Compose failed and starts over: {error}",
    /** Told each failure, after it is logged (on the compositor's thread): your own report. */
    val onError: (error: Throwable) -> Unit = {},
) {
    init {
        require(delayMillis >= 0 && maxDelayMillis >= 0 && forgetAfterMillis >= 0) { "times must not be negative" }
    }

    /** The wait before the scene starts over after its [failures]th failure in a row (1 for the first). */
    fun delayAfter(failures: Int): Long {
        var wait = delayMillis
        repeat((failures - 1).coerceIn(0, 40)) { wait = if (wait > maxDelayMillis / 2) maxDelayMillis else wait * 2 }
        return wait.coerceAtMost(maxDelayMillis)
    }

    companion object {
        /** As it is by default. */
        @JvmField
        val DEFAULT = ComposeRecovery()
    }
}
