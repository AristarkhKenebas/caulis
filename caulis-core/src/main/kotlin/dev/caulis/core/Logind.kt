package dev.caulis.core

import dev.caulis.log.InternalCaulisApi
import dev.caulis.core.dbus.DBusConnection
import dev.caulis.core.dbus.ObjectPath
import dev.caulis.event.EventLoop
import dev.caulis.ffi.Fd
import dev.caulis.log.Logger

/**
 * What systemd-logind tells a compositor and does for it, over the system bus:
 *
 * - The computer goes to sleep (suspend, hibernate): [onSleep] hears it *before* — while a
 *   delay lock holds it back (at most a few seconds) — so the screen can be locked first; and
 *   [onWake] after.
 * - The session is to be locked or unlocked (`loginctl lock-session`, idle tools): [onLock].
 * - The screen's brightness, without root ([setBrightness]).
 *
 * Without logind (no system bus), nothing happens and nothing fails.
 */
@InternalCaulisApi
class Logind private constructor(private val bus: DBusConnection, private val loop: EventLoop) : AutoCloseable {

    private val log = Logger("session")
    private var sleepLock: Fd? = null
    private var session: ObjectPath? = null

    /** The computer is about to sleep; call `ready` when done (the screen is locked): it then goes. */
    var onSleep: (ready: () -> Unit) -> Unit = { ready -> ready() }

    /** The computer woke up. */
    var onWake: () -> Unit = {}

    /** The session should be locked (true) or unlocked (false). */
    var onLock: (Boolean) -> Unit = {}

    init {
        takeSleepLock()
        bus.onSignal(MANAGER, "PrepareForSleep") { message ->
            val going = message.body.firstOrNull() as? Boolean ?: return@onSignal
            if (going) {
                log.info("The computer goes to sleep")
                var released = false
                val release = {
                    if (!released) {
                        released = true
                        sleepLock?.close()
                        sleepLock = null
                        log.info("Ready to sleep")
                    }
                }
                // Never longer than logind waits anyway (InhibitDelayMaxSec, 5 s by default).
                loop.after(MAX_WAIT_MILLIS) { release() }
                try {
                    onSleep(release)
                } catch (e: Exception) {
                    log.error("Getting ready to sleep failed", e)
                    release()
                }
            } else {
                log.info("The computer woke up")
                takeSleepLock()
                try {
                    onWake()
                } catch (e: Exception) {
                    log.error("Waking up failed", e)
                }
            }
        }
        bus.call(LOGIN, ObjectPath(MANAGER_PATH), MANAGER, "GetSessionByPID", "u", listOf(ProcessHandle.current().pid().toInt())) { reply ->
            if (reply.isError) {
                log.info { "Not in a logind session (${reply.errorName}): no lock requests, no brightness" }
                return@call
            }
            val path = reply.body.firstOrNull() as? ObjectPath ?: return@call
            session = path
            bus.onSignal(SESSION, "Lock", path.value) { onLock(true) }
            bus.onSignal(SESSION, "Unlock", path.value) { onLock(false) }
        }
    }

    // Holds the computer's sleep back a moment, so we hear of it before it happens.
    private fun takeSleepLock() {
        if (sleepLock != null) return
        bus.call(LOGIN, ObjectPath(MANAGER_PATH), MANAGER, "Inhibit", "ssss", listOf("sleep", "Caulis", "Locking the screen before sleep", "delay")) { reply ->
            if (reply.isError) {
                log.warn("logind does not let us know before sleep: ${reply.errorName}")
                return@call
            }
            val fd = reply.body.firstOrNull() as? Fd ?: return@call
            bus.keep(fd)
            sleepLock = fd
        }
    }

    /** Sets the brightness of the backlight [device] ("intel_backlight", "amdgpu_bl1") to [value] (its own scale). */
    fun setBrightness(device: String, value: Int) {
        val path = session ?: return
        bus.call(LOGIN, path, SESSION, "SetBrightness", "ssu", listOf("backlight", device, value)) { reply ->
            if (reply.isError) log.warn("The brightness could not be set: ${reply.errorName}")
        }
    }

    override fun close() {
        sleepLock?.close()
        sleepLock = null
        bus.close()
    }

    companion object {
        private const val LOGIN = "org.freedesktop.login1"
        private const val MANAGER = "org.freedesktop.login1.Manager"
        private const val MANAGER_PATH = "/org/freedesktop/login1"
        private const val SESSION = "org.freedesktop.login1.Session"
        private const val MAX_WAIT_MILLIS = 4000L

        /** logind on the system bus, or null (no bus, no logind). */
        @JvmStatic
        fun connect(loop: EventLoop): Logind? = DBusConnection.system(loop)?.let { Logind(it, loop) }
    }
}
