package dev.caulis.compose

import dev.caulis.core.NoticeLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Content that fails: what is reported, restarted, and when it is given up.
 */
internal class ComposeFailures(private val composeWidget: ComposeWidget) {

    // Monitors whose scene failed: when it may start over (System.nanoTime()).
    internal val restartAt = HashMap<String, Long>()

    internal var failures = 0

    private var lastFailure = 0L

    private var noticeShown = false

    // Runs [action] on [part]'s scene (on [name]); if it throws, the scene failed: null.
    internal inline fun <T> guarded(name: String, part: ComposeWidget.Part, action: () -> T): T? {
        if (part.failed) return null
        return try {
            action()
        } catch (e: Throwable) {
            // (An Error too — TODO() is one — else it would fail on every frame past us.)
            e.rethrowIfFatalHere()
            failed(name, e)
            null
        }
    }

    // The content on [name] threw [error]: said, then its scene starts over after a while.
    internal fun failed(name: String, error: Throwable) {
        if (composeWidget.closed) return
        val part = composeWidget.parts[name]
        if (part != null) {
            if (part.failed) return
            part.failed = true
        } else if (name in restartAt) {
            return
        }
        val now = System.nanoTime()
        if (failures > 0 && now - lastFailure > composeWidget.recovery.forgetAfterMillis * 1_000_000) failures = 0
        failures++
        lastFailure = now
        val wait = composeWidget.recovery.delayAfter(failures)
        restartAt[name] = now + wait * 1_000_000
        // (The whole story the first time; after that a line each — it may fail again and again.)
        if (failures == 1) {
            composeWidget.log.error("Your Compose content failed on $name: it starts over in $wait ms", error)
        } else {
            composeWidget.log.error("Your Compose content failed on $name again ($failures times in a row: $error): it starts over in $wait ms")
        }
        try {
            composeWidget.recovery.onError(error)
        } catch (e: Throwable) {
            e.rethrowIfFatalHere()
            composeWidget.log.error("Your onError of a Compose widget failed", e)
        }
        val notice = composeWidget.recovery.notice
        if (notice != null && !noticeShown) {
            noticeShown = true
            composeWidget.desktop.notify(notice.replace("{error}", error.message ?: error.javaClass.simpleName), NoticeLevel.ERROR)
        }
        // Not now: it may have failed in the middle of its own frame.
        composeWidget.restarts.launch {
            delay(wait)
            restartAt.remove(name)
            if (composeWidget.closed) return@launch
            composeWidget.log.info { "The Compose content on $name starts over" }
            if (composeWidget.parts[name]?.failed == true) composeWidget.drop(name)
            if (composeWidget.hoverOn == name) composeWidget.hoverOn = null
            composeWidget.desktop.requestRedraw()
        }
    }
}
