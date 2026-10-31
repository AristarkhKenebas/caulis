package dev.caulis.wm

import dev.caulis.core.input.KeyBindings
import dev.caulis.core.input.Keymap

/**
 * Input in a running compositor: what your handlers take, keys and their modes, wheel and buttons.
 */
internal class RunningInput(private val running: Running) {

    /** A key from the keyboard: the compositor's combination, or passed to the focused program. */
    /** The pointer moved by [dx], [dy] (units; raw: the mouse's own movement) — onPointerMotion first. */
    fun pointerMovedBy(timeMillis: Int, dx: Double, dy: Double, rawDx: Double = dx, rawDy: Double = dy) {
        // (The input thread moves the cursor ahead only while the movement moves the pointer.)
        if (motionTaken(dx, dy) || running.config.pointerBindings.motion(dx, dy)) {
            running.desktop.earlyCursorMoves = false
            return running.desktop.onUserActivity()
        }
        running.desktop.earlyCursorMoves = true
        running.desktop.pointerMovedBy(timeMillis, dx, dy, rawDx, rawDy)
    }

    /** The pointer is at [x], [y] (units: nested, tablets) — onPointerMotion first, with the movement. */
    fun pointerMovedTo(timeMillis: Int, x: Double, y: Double) {
        val dx = x - running.desktop.pointerX
        val dy = y - running.desktop.pointerY
        if (motionTaken(dx, dy) || running.config.pointerBindings.motion(dx, dy)) return running.desktop.onUserActivity()
        running.desktop.pointerMoved(timeMillis, x, y)
    }

    private fun motionTaken(dx: Double, dy: Double): Boolean =
        !running.desktop.locked && running.desktop.overlay == null && running.config.motionHandlers.isNotEmpty() && (dx != 0.0 || dy != 0.0) &&
            running.config.motionHandlers.asReversed().any { h -> running.safely("onPointerMotion", false) { h(running.config, dx, dy) } }

    // Modifiers held now (KeyInput.Modifiers bits), for onKey.
    private var modifiersHeld = 0

    private val modifierKeysDown = HashSet<Int>()

    // Asks the configuration's onKey handlers (last added first); true if one took the key.
    private fun keyTaken(timeMillis: Int, evdevCode: Int, pressed: Boolean): Boolean {
        val before = modifiersHeld
        val bit = KeyInput.Modifiers.of(evdevCode)
        if (bit != 0) {
            if (pressed) modifierKeysDown += evdevCode else modifierKeysDown -= evdevCode
            modifiersHeld = modifierKeysDown.fold(0) { m, code -> m or KeyInput.Modifiers.of(code) }
        }
        if (running.config.keyHandlers.isEmpty()) {
            handlersSawDown.clear()
            return false
        }
        // (Latin whatever the layout: Super+D on the Russian layout is Super+D, not Super+в.)
        val keysym = running.seat.shortcutName(evdevCode)
        val locked = running.desktop.locked
        // The release of a key the handlers saw go down: theirs too, whatever opened since (a
        // lock, a screenshot chooser) — else they thought it held for good (Super pressed,
        // the chooser opened, Super let go: every binding of theirs with Super broke).
        val sawDown = !pressed && handlersSawDown.remove(evdevCode)
        if (!sawDown) {
            if (locked && !keysym.startsWith("XF86")) return false // (never what is typed into the locker)
            // An overlay (a screenshot chooser) has the keys to itself, as it has the mouse; its
            // releases too (else a Ctrl taken here stayed held for it). The laptop's keys go on.
            if (running.desktop.overlay != null && !keysym.startsWith("XF86")) return false
        } else {
            running.keyboardLog.trace { "A release for onKey: it saw the key go down" }
        }
        if (pressed) handlersSawDown += evdevCode
        val input = KeyInput(evdevCode, pressed, keysym, modifiersHeld, before, locked, timeMillis)
        input.layoutKeysym = running.seat.keysymName(evdevCode)
        return running.config.keyHandlers.asReversed().any { handler -> running.safely("onKey", false) { handler(running.config, input) } }
    }

    // Keys onKey handlers were told went down (and not yet that they were let go).
    private val handlersSawDown = HashSet<Int>()

    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        running.userActive()
        // A key a program saw go down is let go for it too, whoever takes the release (1 pressed
        // in a terminal, then Super: Super+1 took the release, and the terminal typed 1 for ever).
        val programHolds = !pressed && running.seat.holds(evdevCode)
        if (keyTaken(timeMillis, evdevCode, pressed)) {
            // The keyboard's state still follows the key: modifiers would stick, and a layout
            // switch or Caps Lock on a key the configuration took would do nothing.
            if (KeyInput.Modifiers.of(evdevCode) != 0 || programHolds) running.seat.key(timeMillis, evdevCode, pressed) else running.seat.trackKey(evdevCode, pressed)
            // The combinations stop counting it as held too (its press may have reached them:
            // a Super held there for good broke every combination without Super).
            if (!pressed) for (bindings in allKeys()) bindings.forget(evdevCode)
            return
        }
        val active = activeKeys()
        // A key pressed before locking may be released after it (Super+L): all must see the release.
        if (!pressed) for (other in allKeys()) if (other !== active) other.key(evdevCode, false)
        val bound = active.key(evdevCode, pressed)
        if (bound && programHolds) running.seat.key(timeMillis, evdevCode, false)
        if (!bound) {
            // A special key (a laptop's Fn keys, media keys) nobody binds: said once, with its
            // name, so it can be bound. Never a typing key, never while the screen is locked.
            if (pressed && evdevCode >= FIRST_SPECIAL_KEY && evdevCode !in 0x100..0x15f && !running.desktop.locked && unboundSeen.add(evdevCode)) {
                val name = KeyBindings.nameOf(evdevCode)
                running.log.info { "The key ${name ?: "with code $evdevCode"} is not bound${name?.let { ": bind(\"$it\") { ... } binds it" } ?: ""}" }
            }
            running.desktop.key(timeMillis, evdevCode, pressed)
        }
    }

    // Other layouts, variants, options or model in the settings: the keymap made anew, given
    // to every program (a mistake in them keeps the keymap there was).
    fun applyKeymap() {
        val wanted = listOf(running.config.keyboardLayouts, running.config.keyboardVariants, running.config.keyboardOptions, running.config.keyboardModel)
        if (wanted == running.keymapOf) return
        val next = try {
            Keymap.fromNames(layouts = wanted[0], variants = wanted[1], options = wanted[2], model = wanted[3])
        } catch (e: Exception) {
            running.keyboardLog.warn("The keyboard layouts \"${wanted[0]}\" (options \"${wanted[2]}\") cannot be used: ${e.message}")
            running.keymapOf = wanted
            return
        }
        running.seat.setKeymap(next)
        running.keymapOf = wanted
        running.keyboardLog.info { "Keyboard layouts: ${wanted[0]}${if (wanted[2].isNotEmpty()) " (${wanted[2]})" else ""}" }
    }

    // Special keys said once already (see key).
    private val unboundSeen = HashSet<Int>()

    // The keys that are the compositor's now.
    internal fun activeKeys(): KeyBindings = when {
        running.desktop.locked -> running.lockedKeys
        // A window keeps the keys (a nested compositor): only quit, consoles and restoreShortcuts are ours.
        running.shortcutsInhibit.active -> running.inhibitedKeys
        // An overlay of the compositor's own (a screenshot chooser) has the keys: only quit and consoles stay.
        running.desktop.overlay != null -> running.inhibitedKeys
        else -> running.keyMode?.let(running.modeKeys::get) ?: running.keys
    }

    internal fun allKeys(): List<KeyBindings> = listOf(running.keys, running.lockedKeys, running.inhibitedKeys) + running.modeKeys.values

    /** Forgets every key held (the keyboard went to another session or terminal). */
    fun releaseKeys() {
        for (bindings in allKeys()) bindings.releaseAll()
        // onKey handlers hear the releases of what they saw go down (they keep their own count of
        // what is held); then nothing counts as held for them.
        val now = (System.nanoTime() / 1_000_000).toInt()
        for (code in handlersSawDown.toList()) keyTaken(now, code, false)
        handlersSawDown.clear()
        modifierKeysDown.clear()
        modifiersHeld = 0
    }

    /** Turns the key mode [name] on; null: the usual keys again. */
    fun enterMode(name: String?) {
        val wanted = name?.takeIf { it != "reset" && it != "default" }
        require(wanted == null || wanted in running.modeKeys) { "there is no key mode \"$name\" (there are: ${running.modeKeys.keys.joinToString().ifEmpty { "none" }})" }
        if (wanted == running.keyMode) return
        running.keyMode = wanted
        running.log.info { if (wanted == null) "The usual keys again" else "Key mode \"$wanted\"" }
        running.ipc?.keyModeChanged(wanted)
    }

    // The wheel's steps, for combinations with it (hi-res wheels send parts of a step).
    private var wheelSteps = doubleArrayOf(0.0, 0.0)

    /** The mouse wheel or a touchpad scrolled: a combination with the wheel, or the program under the pointer. */
    fun pointerAxis(timeMillis: Int, scroll: dev.caulis.core.input.Scroll) {
        running.userActive()
        // Each direction on its own for the handlers and wheel combinations; what they leave
        // goes to the program under the pointer (both directions still in one scroll).
        val vertical = scroll.verticalOnly()?.takeIf { !pointerAxisTaken(true, it.vertical, it.vertical120) }
        val horizontal = scroll.horizontalOnly()?.takeIf { !pointerAxisTaken(false, it.horizontal, it.horizontal120) }
        if (vertical == null && horizontal == null) return
        activeKeys().interrupted()
        running.desktop.pointerAxis(timeMillis, if (vertical != null && horizontal != null) scroll else vertical ?: horizontal!!)
    }

    // A scroll handler or a wheel combination takes this direction of a scroll.
    private fun pointerAxisTaken(vertical: Boolean, value: Double, value120: Int): Boolean {
        // (An overlay — a screenshot chooser — has the mouse to itself: no handler takes it first.)
        if (!running.desktop.locked && running.desktop.overlay == null && running.config.scrollHandlers.asReversed().any { h -> running.safely("onScroll", false) { h(running.config, vertical, value, value120 / 120.0) } }) return true
        val active = activeKeys()
        if (running.config.pointerBindings.scroll(vertical, value120, running.keys.heldModifiers(), locked = running.desktop.locked)) return true
        if (value120 != 0 && !running.desktop.locked) {
            val (back, forth) = if (vertical) KeyBindings.WHEEL_UP to KeyBindings.WHEEL_DOWN else KeyBindings.WHEEL_LEFT to KeyBindings.WHEEL_RIGHT
            val code = if (value120 < 0) back else forth
            if (active.has(code)) {
                val axis = if (vertical) 0 else 1
                wheelSteps[axis] += value120 / 120.0
                while (Math.abs(wheelSteps[axis]) >= 1.0) {
                    active.wheel(code)
                    wheelSteps[axis] -= Math.signum(wheelSteps[axis])
                }
                return true
            }
        }
        return false
    }

    /**
     * A mouse button: Super + left button drags a window, Super + right button resizes it
     * (from the nearest corner). Everything else goes to the program under the pointer.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        running.userActive()
        // (An overlay — a screenshot chooser — has the mouse to itself: no handler takes it first.)
        // (The release of a press they saw is theirs too, whatever opened since: they count what is held.)
        val sawDown = !pressed && buttonHandlersSawDown.remove(button)
        val toHandlers = sawDown || (!running.desktop.locked && running.desktop.overlay == null)
        if (pressed && toHandlers) buttonHandlersSawDown += button
        if (toHandlers && running.config.buttonHandlers.asReversed().any { h -> running.safely("onPointerButton", false) { h(running.config, button, pressed) } }) return
        // The mouse's own bindings (a press and its release go the same way).
        if (running.config.pointerBindings.button(button, pressed, running.keys.heldModifiers(), locked = running.desktop.locked)) return
        // A combination with a mouse button ("Super+MouseSide", "MouseBack").
        val active = activeKeys()
        if (!pressed) for (other in allKeys()) if (other !== active) other.key(button, false)
        if (active.key(button, pressed)) return
        if (pressed && !running.desktop.locked && running.desktop.overlay == null && dragging() && (button == BTN_LEFT || button == BTN_RIGHT)) {
            val window = running.desktop.windowAt(running.desktop.pointerX, running.desktop.pointerY)
            if (window != null) {
                running.desktop.pointerButton(timeMillis, button, true, passToApps = false)
                if (button == BTN_LEFT) {
                    running.desktop.beginMove(window)
                } else {
                    running.desktop.beginResize(window, running.desktop.nearestEdges(window, running.desktop.pointerX, running.desktop.pointerY))
                }
                return
            }
        }
        running.desktop.pointerButton(timeMillis, button, pressed)
    }

    // Buttons onPointerButton handlers were told went down (and not yet that they were let go).
    private val buttonHandlersSawDown = HashSet<Int>()

    // The modifiers for dragging windows are held (and no others).
    private fun dragging(): Boolean {
        val modifiers = running.config.keys.windowDrag ?: return false
        return running.keys.heldModifiers() == KeyBindings.modifiers(modifiers)
    }
}
