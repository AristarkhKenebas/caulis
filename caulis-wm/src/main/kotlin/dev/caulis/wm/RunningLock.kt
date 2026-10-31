package dev.caulis.wm

import dev.caulis.core.NoticeLevel
import dev.caulis.event.EventLoop

/**
 * Locking: starting the locker, watching that it locks, the fallback, sleeping and waking.
 */
internal class RunningLock(private val running: Running) {

    // True while the screen is locked: by a locker program, or by the compositor's own (lockerShows).
    internal fun lockedNow(): Boolean =
        running.desktop.locked || running.config.lockerShows?.let { shows -> running.safely("lockerShows", false) { shows(running.config) } } == true

    // The computer is about to sleep: your onSleep, the screen locked (when asked for), then [ready].
    internal fun goingToSleep(ready: () -> Unit) {
        running.config.sleepActions.toList().forEach { action -> running.safely("onSleep") { action(running.config) } }
        if (!running.config.lockBeforeSleep || lockedNow()) return ready()
        running.safely("the lock before sleep") { running.config.lock() }
        // A lock screen of the compositor's own that cannot say when it shows: taken as up at once.
        if (running.config.locker != null && running.config.lockerShows == null) return ready()
        // Sleep once the lock screen shows (or after a while: logind does not wait for ever anyway).
        val since = System.nanoTime()
        fun check() {
            when {
                lockedNow() -> ready()
                System.nanoTime() - since > running.config.sleepLockWaitMillis * 1_000_000 -> {
                    lockFailed("it did not lock in ${running.config.sleepLockWaitMillis} ms before sleep")
                    // (Our own lock screen: a moment to be on screen before the computer sleeps.)
                    if (fallbackLock != null) running.loop.after(running.config.sleepOwnLockShowMillis) { ready() } else ready()
                }
                else -> running.loop.after(50) { check() }
            }
        }
        check()
    }

    // The computer woke up: the monitors are set up anew (sleep may have reset them), the idle
    // timers start over, and your onWake runs.
    internal fun wokeUp() {
        running.safely("waking the monitors") { running.wakeMonitors() }
        running.userActive()
        running.safely("the colour schedule") { running.rlooks.updateColorScheme() } // (the clock went on while asleep)
        running.config.wakeActions.toList().forEach { action -> running.safely("onWake") { action(running.config) } }
    }

    // Programs started as the screen locker (by Caulis.lock: lockCommand, or what your locker
    // started): only their unlock ends the compositor's own lock screen. (Ended ones stay in
    // the set: a pid is reused only after many others.)
    internal val lockerPids = HashSet<Long>()

    internal var startingLocker = false

    /** Runs [body], which starts the screen locker: the programs it starts may end our own lock screen. */
    fun <T> startingLocker(body: () -> T): T {
        val outer = startingLocker
        startingLocker = true
        try {
            return body()
        } finally {
            startingLocker = outer
        }
    }

    // Says so when a lock asked for has not shown in time (see Caulis.lock).
    private var lockWatch: dev.caulis.event.EventLoop.Timer? = null

    /** A lock was asked of [locker]: if the screen is not locked [Caulis.lockShowMillis] later, a notice says so. */
    fun watchLock(locker: String) {
        lockWatch?.cancel()
        val millis = running.config.lockShowMillis
        lockWatch = running.loop.after(millis) {
            lockWatch = null
            if (!lockedNow()) lockFailed("$locker showed no lock screen in $millis ms")
        }
    }

    // A lock asked for did not show ([why]): said loudly, and — as lockFailure says — the
    // compositor's own plain lock screen goes up instead.
    private fun lockFailed(why: String) {
        // (Said once: the lock before sleep and the watch on the lock may both find it.)
        lockWatch?.cancel()
        lockWatch = null
        running.log.error("The screen did not lock: $why")
        running.desktop.notify("The screen is NOT locked: $why. Is the locker installed? (lockCommand)", NoticeLevel.ERROR)
        if (running.config.lockFailure == LockFailure.OWN_LOCK) lockOwnScreen()
    }

    // The compositor's own plain lock screen (the locker did not show), or null.
    private var fallbackLock: FallbackLock? = null

    // The locker program in charge now (to know its unlock ends our own lock screen too).
    internal var lockerProgram: dev.caulis.core.SessionLock? = null

    /** Locks with the compositor's own plain lock screen (see [Caulis.lockFailure]). */
    fun lockOwnScreen() {
        if (fallbackLock != null) return
        val screen = FallbackLock(color = { running.config.lockColor }, retry = {
            running.log.info { "Starting the locker again (Enter on the compositor's own lock screen)" }
            running.safely("the locker") { running.config.lock() }
        })
        fallbackLock = screen
        running.desktop.lockInternally(screen, hideDesktop = true)
        running.log.warn(
            "Locked with the compositor's own plain lock screen: it ends when the locker the compositor starts unlocks — " +
                "press Enter to start ${Launcher.programName(running.config.lockCommand)} again (from a text console: fix lockCommand in the configuration, save it, then press Enter)",
        )
    }

    // A locker program unlocked the session: our own lock screen (if it is up) ends with it —
    // if that locker is one the compositor started (lock(): Enter on our lock screen). Any
    // other program could lock and unlock at once, without asking for a password.
    internal fun lockerUnlocked(lock: dev.caulis.core.SessionLock) {
        val own = fallbackLock ?: return
        val pid = lock.resource.client.pid
        if (!running.launcher.descendsFrom(pid, lockerPids)) {
            running.log.warn(
                "${lock.resource.client.name} locked and unlocked, but it is not the locker the compositor started: " +
                    "the compositor's own lock screen stays (press Enter on it to start ${Launcher.programName(running.config.lockCommand)})",
            )
            return
        }
        fallbackLock = null
        if (running.desktop.lockScreen === own) running.desktop.unlockInternally()
        running.log.info { "The locker unlocked: the compositor's own lock screen is gone too" }
    }
}
