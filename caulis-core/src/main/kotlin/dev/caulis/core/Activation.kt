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

    /**
     * How long a token stays good after it was given out, in milliseconds (30 s by default:
     * a program started by the compositor may take a while to show its window).
     */
    var tokenLifetimeMillis: Long = TOKEN_LIFETIME_MILLIS
        set(value) {
            require(value > 0) { "tokenLifetimeMillis must be positive, not $value" }
            field = value
        }

    /**
     * A dialog that takes the focus back from its window within [modalDialogWithinMillis] of
     * that window getting it becomes modal: its window hands it the focus from now on, instead
     * of the two taking it in turns (a flickering border). JetBrains' IDEs and other Java
     * programs open such dialogs without saying they are modal. On by default; false turns
     * this guess off (only dialogs that say so are modal).
     */
    var dialogsTakingFocusBackAreModal = true

    /** See [dialogsTakingFocusBackAreModal] (1000 ms by default). */
    var modalDialogWithinMillis: Long = MODAL_DIALOG_WITHIN_MILLIS
        set(value) {
            require(value >= 0) { "modalDialogWithinMillis cannot be negative, not $value" }
            field = value
        }

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
                            if (good) keep(token)
                            resource.sendDone(token)
                        }

                        override fun destroy(resource: XdgActivationTokenV1) {}
                    }
                }

                override fun activate(resource: XdgActivationV1, token: String, surface: WlSurface) {
                    val window = surface.dataAs<Surface>()?.let(::surfaceOf)?.roleObject as? Window ?: return
                    if (useToken(token) && focusOnActivate && WindowRequest.ACTIVATE !in desktop.behaviorOf(window).ignored) {
                        // A dialog taking the focus back from its window at once (JetBrains' and
                        // Java's do, without saying modal): it is modal — its window gives it the
                        // focus from now on, instead of the two taking it in turns (a flickering border).
                        val parent = window.parent
                        if (dialogsTakingFocusBackAreModal && parent != null && !window.modal && desktop.focusedWindow === parent &&
                            System.nanoTime() - desktop.focusedAt < modalDialogWithinMillis * 1_000_000L
                        ) {
                            window.modalAsked(true)
                            log.info { "$window took the focus back from $parent at once: modal from now on" }
                        }
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

    // A popup or a part of a window counts as the window (a menu's menu too: a few levels).
    private fun surfaceOf(surface: Surface): Surface {
        var current = surface
        repeat(16) {
            while (true) current = current.subsurface?.parent ?: break
            current = (current.roleObject as? Popup)?.parent?.surface ?: return current
        }
        return current
    }

    /** A good token for a program the compositor starts (it is told it in XDG_ACTIVATION_TOKEN). */
    fun issue(): String = newToken().also(::keep)

    // Keeps a good token: old ones go first, and never more than MAX_TOKENS are kept (a
    // program asking for tokens without end would otherwise fill the memory).
    private fun keep(token: String) {
        dropExpired(System.nanoTime())
        while (tokens.size >= MAX_TOKENS) {
            val oldest = tokens.keys.first()
            tokens.remove(oldest)
            log.trace("An old activation token was dropped: too many waiting")
        }
        tokens[token] = System.nanoTime()
    }

    private fun dropExpired(now: Long) {
        tokens.entries.removeIf { now - it.value > tokenLifetimeMillis * 1_000_000L }
    }

    private fun newToken(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // A good token is used once, within a while of being given out.
    private fun useToken(token: String): Boolean {
        dropExpired(System.nanoTime())
        return tokens.remove(token) != null
    }

    companion object {
        const val VERSION = 1

        /** The default [tokenLifetimeMillis]. */
        const val TOKEN_LIFETIME_MILLIS = 30_000L

        /** The default [modalDialogWithinMillis]. */
        const val MODAL_DIALOG_WITHIN_MILLIS = 1_000L

        // Good tokens kept at most (programs use theirs at once; a few are waiting at a time).
        private const val MAX_TOKENS = 256
    }
}
