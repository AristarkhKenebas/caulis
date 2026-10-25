package dev.caulis.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.caulis.core.WidgetKey
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as Awt

/**
 * Keys as Compose on the desktop gets them from a window of its own: each one an AWT key
 * event (pressed, typed, released — no window is made: their source is a component never
 * shown) wrapped as Compose wraps them, so shortcuts and text fields work as usual.
 */
internal object AwtKeys {

    private val source = object : Component() {}

    // (Compose's own factory of key events: marked as for its modules only, used here with the
    // exact Compose version this module is built against — see libs.versions.toml.)
    @OptIn(InternalComposeUiApi::class)
    fun events(key: WidgetKey): List<KeyEvent> {
        val modifiers = (if (key.shift) InputEvent.SHIFT_DOWN_MASK else 0) or
            (if (key.ctrl) InputEvent.CTRL_DOWN_MASK else 0) or
            (if (key.alt) InputEvent.ALT_DOWN_MASK else 0) or
            (if (key.logo) InputEvent.META_DOWN_MASK else 0)
        val text = key.text
        val code = VK[key.evdevCode] ?: text?.singleOrNull()?.let { Awt.getExtendedKeyCodeForChar(it.code) } ?: Awt.VK_UNDEFINED
        val char = text?.singleOrNull() ?: Awt.CHAR_UNDEFINED
        val time = key.timeMillis.toLong()
        fun wrap(awt: Awt, type: KeyEventType, codePoint: Int) = KeyEvent(
            Key(awt.keyCode, awt.keyLocation), type, codePoint,
            isCtrlPressed = key.ctrl, isMetaPressed = key.logo, isAltPressed = key.alt, isShiftPressed = key.shift, nativeEvent = awt,
        )
        if (!key.pressed) {
            return listOf(wrap(Awt(source, Awt.KEY_RELEASED, time, modifiers, code, char, Awt.KEY_LOCATION_STANDARD), KeyEventType.KeyUp, 0))
        }
        val events = ArrayList<KeyEvent>(2)
        events += wrap(Awt(source, Awt.KEY_PRESSED, time, modifiers, code, char, Awt.KEY_LOCATION_STANDARD), KeyEventType.KeyDown, 0)
        // Typed text (not with Ctrl or Logo: those are shortcuts, as on every desktop).
        if (text != null && !key.ctrl && !key.logo) {
            for (c in text) events += wrap(Awt(source, Awt.KEY_TYPED, time, modifiers, Awt.VK_UNDEFINED, c, Awt.KEY_LOCATION_UNKNOWN), KeyEventType.Unknown, c.code)
        }
        return events
    }

    // Linux key codes of keys that type nothing, and the AWT ones Compose knows them by.
    private val VK = mapOf(
        1 to Awt.VK_ESCAPE, 14 to Awt.VK_BACK_SPACE, 15 to Awt.VK_TAB, 28 to Awt.VK_ENTER,
        96 to Awt.VK_ENTER, 111 to Awt.VK_DELETE, 110 to Awt.VK_INSERT,
        102 to Awt.VK_HOME, 107 to Awt.VK_END, 104 to Awt.VK_PAGE_UP, 109 to Awt.VK_PAGE_DOWN,
        103 to Awt.VK_UP, 108 to Awt.VK_DOWN, 105 to Awt.VK_LEFT, 106 to Awt.VK_RIGHT,
        42 to Awt.VK_SHIFT, 54 to Awt.VK_SHIFT, 29 to Awt.VK_CONTROL, 97 to Awt.VK_CONTROL,
        56 to Awt.VK_ALT, 100 to Awt.VK_ALT_GRAPH, 125 to Awt.VK_META, 126 to Awt.VK_META,
        57 to Awt.VK_SPACE,
        59 to Awt.VK_F1, 60 to Awt.VK_F2, 61 to Awt.VK_F3, 62 to Awt.VK_F4, 63 to Awt.VK_F5,
        64 to Awt.VK_F6, 65 to Awt.VK_F7, 66 to Awt.VK_F8, 67 to Awt.VK_F9, 68 to Awt.VK_F10,
        87 to Awt.VK_F11, 88 to Awt.VK_F12,
    )
}
