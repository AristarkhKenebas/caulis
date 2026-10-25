package dev.caulis.core

import dev.caulis.core.input.Seat
import dev.caulis.log.Logger
import dev.caulis.protocol.Display
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface
import dev.caulis.protocol.xdgactivationv1.XdgActivationTokenV1
import dev.caulis.protocol.xdgactivationv1.XdgActivationV1
import java.security.SecureRandom

/**
 * xdg-activation: a window asks for the focus — a link clicked in a chat opens the browser
 * and brings it forward, a second start of a program brings the first one. A request with
 * a good token (asked for by the program that had the keyboard, or given by the compositor
 * to a program it starts) gets the focus, even on another workspace; any other one only
 * marks the window as wanting attention ([Window.urgent]), so nothing steals the focus.
 */
class Activation(display: Display, private val seat: Seat, private val desktop: Desktop) {

    private val log = Logger("focus")
    private val random = SecureRandom()

    // Good tokens, with when they were given out (they last a while, and once).
    private val tokens = LinkedHashMap<String, Long>()

    /** Activation requests with a good token focus the window (false: they only mark it urgent). */
    var focusOnActivate = true

    init {
        display.addGlobal(XdgActivationV1, VERSION) { manager ->
            manager.requests = object : XdgActivationV1.Requests {
                override fun getActivationToken(resource: XdgActivationV1, id: XdgActivationTokenV1) {
                    var committed = false
                    id.requests = object : XdgActivationTokenV1.Requests {
                        override fun setSerial(resource: XdgActivationTokenV1, serial: Int, seat: WlSeat) {}
                        override fun setAppId(resource: XdgActivationTokenV1, appId: String) {}
                        override fun setSurface(resource: XdgActivationTokenV1, surface: WlSurface) {}

                        override fun commit(resource: XdgActivationTokenV1) {
                            if (committed) return
                            committed = true
                            // Good if the program asking has the keyboard now (it acts for the user).
                            val focus = seat.keyboardFocus
                            val good = focus != null && focus.resource.client === resource.client
                            val token = newToken()
                            if (good) tokens[token] = System.nanoTime()
                            resource.sendDone(token)
                        }

                        override fun destroy(resource: XdgActivationTokenV1) {}
                    }
                }

                override fun activate(resource: XdgActivationV1, token: String, surface: WlSurface) {
                    val window = surface.dataAs<Surface>()?.let(::surfaceOf)?.roleObject as? Window ?: return
                    if (useToken(token) && focusOnActivate && WindowRequest.ACTIVATE !in desktop.behaviorOf(window).ignored) {
                        log.info { "$window is brought forward (activated)" }
                        desktop.focus(window)
                    } else if (window !== desktop.focusedWindow) {
                        desktop.setUrgent(window, true)
                    }
                }

                override fun destroy(resource: XdgActivationV1) {}
            }
        }
    }

    // A popup or a part of a window counts as the window.
    private fun surfaceOf(surface: Surface): Surface {
        var current = surface
        while (true) current = current.subsurface?.parent ?: return current
    }

    /** A good token for a program the compositor starts (it is told it in XDG_ACTIVATION_TOKEN). */
    fun issue(): String = newToken().also { tokens[it] = System.nanoTime() }

    private fun newToken(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // A good token is used once, within a while of being given out.
    private fun useToken(token: String): Boolean {
        val now = System.nanoTime()
        tokens.entries.removeIf { now - it.value > LIFETIME_NANOS }
        return tokens.remove(token) != null
    }

    companion object {
        const val VERSION = 1
        private const val LIFETIME_NANOS = 30_000_000_000L
    }
}
