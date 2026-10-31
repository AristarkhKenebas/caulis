package dev.caulis.compose

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.caulis.core.Monitor
import dev.caulis.core.WidgetKey

/**
 * Input for the content: pointer, keys and the pointer's shape.
 */
internal class ComposeInput(private val composeWidget: ComposeWidget) {

    internal var last: Offset? = null

    fun pointerMoved(monitor: Monitor, x: Double, y: Double) {
        val part = composeWidget.sceneOf(monitor.name) ?: return
        val at = composeWidget.inScene(monitor, part, x, y)
        if (at == null) return pointerLeft()
        if (composeWidget.hoverOn != monitor.name) {
            send(composeWidget.hoverOn, PointerEventType.Exit, Offset.Zero)
            composeWidget.hoverOn = monitor.name
            send(monitor.name, PointerEventType.Enter, at, modifiers = modifiers())
        }
        last = at
        // One move a turn of the compositor's loop, the latest: a mouse sends up to 1000 a
        // second, and each went through the scene at once (finding what is under it) — a
        // waste on the compositor's thread while the pointer is over a menu or a card.
        if (!moveQueued) {
            moveQueued = true
            composeWidget.post(Runnable { sendMove() })
        }
    }

    // A move waits to be sent (see pointerMoved).
    private var moveQueued = false

    // The latest move to the scene the pointer is on, if one waits.
    private fun sendMove() {
        if (!moveQueued) return
        moveQueued = false
        val at = last ?: return
        send(composeWidget.hoverOn, PointerEventType.Move, at, modifiers = modifiers())
    }

    fun pointerLeft() {
        sendMove()
        send(composeWidget.hoverOn, PointerEventType.Exit, Offset.Zero)
        composeWidget.hoverOn = null
        last = null
        pointerIcon(PointerIcon.Default)
    }

    fun pointerButton(button: Int, pressed: Boolean) {
        sendMove() // (the press where the pointer is now)
        if (composeWidget.sceneOf(composeWidget.hoverOn) == null) return
        val at = last ?: return
        if (pressed) composeWidget.keysOn = composeWidget.hoverOn
        val which = when (button) {
            BTN_LEFT -> PointerButton.Primary
            BTN_RIGHT -> PointerButton.Secondary
            BTN_MIDDLE -> PointerButton.Tertiary
            BTN_SIDE -> PointerButton.Back
            BTN_EXTRA -> PointerButton.Forward
            else -> return
        }
        send(composeWidget.hoverOn, if (pressed) PointerEventType.Press else PointerEventType.Release, at, modifiers = modifiers(), button = which)
    }

    fun scroll(dx: Double, dy: Double) {
        sendMove()
        val at = last ?: return
        // Compose counts in lines (one wheel notch is one; the compositor gives units, a notch
        // being WHEEL_UNITS of them).
        send(composeWidget.hoverOn, PointerEventType.Scroll, at, scrollDelta = Offset((dx / WHEEL_UNITS).toFloat(), (dy / WHEEL_UNITS).toFloat()), modifiers = modifiers())
    }

    // The modifiers held, as the last key said (Shift+click, Ctrl+wheel). (Known while it has
    // the keyboard: else keys go to the programs.)
    private var lastKey: WidgetKey? = null

    private fun modifiers(): PointerKeyboardModifiers {
        val key = lastKey ?: return PointerKeyboardModifiers()
        return PointerKeyboardModifiers(isCtrlPressed = key.ctrl, isMetaPressed = key.logo, isAltPressed = key.alt, isShiftPressed = key.shift)
    }

    fun key(key: WidgetKey) {
        val name = (composeWidget.keysOn ?: composeWidget.hoverOn)?.takeIf { composeWidget.sceneOf(it) != null } ?: composeWidget.parts.entries.firstOrNull { !it.value.failed }?.key?.takeIf { !composeWidget.closed } ?: return
        if (key.pressed) composeWidget.heldKeys[key.evdevCode] = key else composeWidget.heldKeys.remove(key.evdevCode)
        lastKey = key
        sendKeys(name, key)
    }

    // A pointer event to the scene on [name] (none: nothing; one that throws fails).
    private fun send(
        name: String?,
        type: PointerEventType,
        at: Offset,
        scrollDelta: Offset = Offset.Zero,
        modifiers: PointerKeyboardModifiers? = null,
        button: PointerButton? = null,
    ) {
        val part = composeWidget.sceneOf(name) ?: return
        composeWidget.contentFailures.guarded(name!!, part) { part.scene.sendPointerEvent(type, at, scrollDelta, modifiers, button) }
    }

    // [key] as Compose's key events to the scene on [name].
    private fun sendKeys(name: String, key: WidgetKey) {
        val part = composeWidget.sceneOf(name) ?: return
        for (event in AwtKeys.events(key, composeWidget.log)) composeWidget.contentFailures.guarded(name, part) { part.scene.sendKeyEvent(event) } ?: return
    }

    fun keyboardFocus(has: Boolean) {
        composeWidget.hasKeyboard = has
        for (part in composeWidget.parts.values) part.scene.focused = has
        if (has) {
            // Given without a click (a key that opens a launcher): the keys go to the scene on
            // the monitor with the pointer, else on the focused one.
            composeWidget.keysOn = composeWidget.hoverOn ?: composeWidget.desktop.focusedMonitor.name.takeIf { it in composeWidget.parts } ?: composeWidget.keysOn
            return
        }
        // Keys held now are let go (else Compose thinks they still are).
        val name = (composeWidget.keysOn ?: composeWidget.hoverOn)?.takeIf { composeWidget.sceneOf(it) != null } ?: composeWidget.parts.entries.firstOrNull { !it.value.failed }?.key
        for (held in composeWidget.heldKeys.values.toList()) {
            val up = WidgetKey(held.evdevCode, false, null, held.shift, held.ctrl, held.alt, held.logo, (System.nanoTime() / 1_000_000).toInt())
            if (name != null) sendKeys(name, up)
        }
        composeWidget.heldKeys.clear()
        lastKey = null
        composeWidget.keyboardLost++
    }

    internal fun pointerIcon(icon: PointerIcon) {
        val name = CURSORS.firstOrNull { (awt, _) -> PointerIcon(java.awt.Cursor(awt)) == icon }?.second ?: "default"
        if (name == composeWidget.cursor) return
        composeWidget.cursor = name
        composeWidget.log.trace { "Compose's pointer: $name" }
        composeWidget.desktop.requestRedraw()
    }
}
