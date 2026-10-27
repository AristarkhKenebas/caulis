package dev.caulis.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.caulis.core.WidgetKey
import dev.caulis.log.Logger
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
    fun events(key: WidgetKey, log: Logger): List<KeyEvent> = try {
        make(key)
    } catch (e: LinkageError) {
        // (Another Compose than this module was built with: said once, keys go nowhere.)
        if (!failed) log.error("Keys cannot reach Compose: Compose on the class path is not the version caulis-compose was built with (it needs exactly that one)", e)
        failed = true
        emptyList()
    }

    private var failed = false

    @OptIn(InternalComposeUiApi::class)
    private fun make(key: WidgetKey): List<KeyEvent> {
        val modifiers = (if (key.shift) InputEvent.SHIFT_DOWN_MASK else 0) or
            (if (key.ctrl) InputEvent.CTRL_DOWN_MASK else 0) or
            (if (key.alt) InputEvent.ALT_DOWN_MASK else 0) or
            (if (key.logo) InputEvent.META_DOWN_MASK else 0)
        val text = key.text
        // The key itself, whatever the layout and the modifiers (Ctrl+A is Key.A in a Russian
        // layout too, Shift+1 is Key.One); the same for its press and its release.
        val code = VK[key.evdevCode] ?: Awt.VK_UNDEFINED
        val location = LOCATION[key.evdevCode] ?: Awt.KEY_LOCATION_STANDARD
        val char = text?.singleOrNull() ?: Awt.CHAR_UNDEFINED
        val time = key.timeMillis.toLong()
        fun wrap(awt: Awt, type: KeyEventType, codePoint: Int) = KeyEvent(
            Key(awt.keyCode, awt.keyLocation), type, codePoint,
            isCtrlPressed = key.ctrl, isMetaPressed = key.logo, isAltPressed = key.alt, isShiftPressed = key.shift, nativeEvent = awt,
        )
        if (!key.pressed) {
            return listOf(wrap(Awt(source, Awt.KEY_RELEASED, time, modifiers, code, Awt.CHAR_UNDEFINED, location), KeyEventType.KeyUp, 0))
        }
        val events = ArrayList<KeyEvent>(2)
        events += wrap(Awt(source, Awt.KEY_PRESSED, time, modifiers, code, char, location), KeyEventType.KeyDown, text?.codePointAt(0) ?: 0)
        // Typed text (not with Ctrl or Logo: those are shortcuts, as on every desktop).
        if (text != null && !key.ctrl && !key.logo) {
            for (c in text) events += wrap(Awt(source, Awt.KEY_TYPED, time, modifiers, Awt.VK_UNDEFINED, c, Awt.KEY_LOCATION_UNKNOWN), KeyEventType.Unknown, c.code)
        }
        return events
    }

    // Linux key codes (input-event-codes.h) and the AWT keys of the same place on a US keyboard.
    private val VK: Map<Int, Int> = buildMap {
        put(1, Awt.VK_ESCAPE)
        "1234567890".forEachIndexed { i, c -> put(2 + i, c.code) } // VK_1..VK_9, VK_0
        put(12, Awt.VK_MINUS); put(13, Awt.VK_EQUALS); put(14, Awt.VK_BACK_SPACE); put(15, Awt.VK_TAB)
        "QWERTYUIOP".forEachIndexed { i, c -> put(16 + i, c.code) }
        put(26, Awt.VK_OPEN_BRACKET); put(27, Awt.VK_CLOSE_BRACKET); put(28, Awt.VK_ENTER); put(29, Awt.VK_CONTROL)
        "ASDFGHJKL".forEachIndexed { i, c -> put(30 + i, c.code) }
        put(39, Awt.VK_SEMICOLON); put(40, Awt.VK_QUOTE); put(41, Awt.VK_BACK_QUOTE); put(42, Awt.VK_SHIFT); put(43, Awt.VK_BACK_SLASH)
        "ZXCVBNM".forEachIndexed { i, c -> put(44 + i, c.code) }
        put(51, Awt.VK_COMMA); put(52, Awt.VK_PERIOD); put(53, Awt.VK_SLASH); put(54, Awt.VK_SHIFT)
        put(55, Awt.VK_MULTIPLY); put(56, Awt.VK_ALT); put(57, Awt.VK_SPACE); put(58, Awt.VK_CAPS_LOCK)
        for (i in 0 until 10) put(59 + i, Awt.VK_F1 + i)
        put(69, Awt.VK_NUM_LOCK); put(70, Awt.VK_SCROLL_LOCK)
        put(71, Awt.VK_NUMPAD7); put(72, Awt.VK_NUMPAD8); put(73, Awt.VK_NUMPAD9); put(74, Awt.VK_SUBTRACT)
        put(75, Awt.VK_NUMPAD4); put(76, Awt.VK_NUMPAD5); put(77, Awt.VK_NUMPAD6); put(78, Awt.VK_ADD)
        put(79, Awt.VK_NUMPAD1); put(80, Awt.VK_NUMPAD2); put(81, Awt.VK_NUMPAD3); put(82, Awt.VK_NUMPAD0); put(83, Awt.VK_DECIMAL)
        put(86, Awt.VK_LESS); put(87, Awt.VK_F11); put(88, Awt.VK_F12)
        put(96, Awt.VK_ENTER); put(97, Awt.VK_CONTROL); put(98, Awt.VK_DIVIDE); put(99, Awt.VK_PRINTSCREEN); put(100, Awt.VK_ALT_GRAPH)
        put(102, Awt.VK_HOME); put(103, Awt.VK_UP); put(104, Awt.VK_PAGE_UP); put(105, Awt.VK_LEFT); put(106, Awt.VK_RIGHT)
        put(107, Awt.VK_END); put(108, Awt.VK_DOWN); put(109, Awt.VK_PAGE_DOWN); put(110, Awt.VK_INSERT); put(111, Awt.VK_DELETE)
        put(119, Awt.VK_PAUSE); put(125, Awt.VK_META); put(126, Awt.VK_META); put(127, Awt.VK_CONTEXT_MENU)
    }

    // Keys there are two of (left and right), and those of the number block.
    private val LOCATION: Map<Int, Int> = buildMap {
        for (k in listOf(29, 42, 56, 125)) put(k, Awt.KEY_LOCATION_LEFT)
        for (k in listOf(97, 54, 100, 126)) put(k, Awt.KEY_LOCATION_RIGHT)
        for (k in listOf(55, 69, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 96, 98)) put(k, Awt.KEY_LOCATION_NUMPAD)
    }
}
