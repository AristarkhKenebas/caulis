package dev.caulis.core

import dev.caulis.event.EventLoop

/**
 * Keys arriving at the desktop: to the widget with the keyboard (with key repeat), or to programs;
 * what to keep from programs.
 */
internal class DesktopKeys(private val desktop: Desktop) {

    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        // Never what is typed into a locker (a password).
        desktop.inputLog.trace { if (desktop.locked) "A key (not logged while locked)" else "Key $evdevCode ${if (pressed) "pressed" else "released"} → ${desktop.seat.keyboardFocus}" }
        // Typing (not just holding Ctrl or Super): the cursor makes way.
        if (pressed && desktop.hideCursorWhileTyping && evdevCode !in MODIFIER_KEYS && desktop.seat.keyboardFocus != null) desktop.cursors.hideCursor()
        if (pressed || evdevCode == widgetRepeatKey) stopWidgetRepeat()
        desktop.keyboardWidget?.takeIf { desktop.lockHost.widgetUsable(it) && desktop.overlay == null }?.let { widget ->
            val text = if (pressed) desktop.seat.textOf(evdevCode) else null
            desktop.seat.key(timeMillis, evdevCode, pressed) // (no program has the keyboard: only its state moves on)
            val m = desktop.seat.modifierBits
            val key = WidgetKey(evdevCode, pressed, text, m and 1 != 0, m and 4 != 0, m and 8 != 0, m and 64 != 0, timeMillis)
            userCode("Your widget (key)", Unit) { widget.key(key) }
            // Held, it comes again as programs would repeat it themselves.
            if (pressed && evdevCode !in MODIFIER_KEYS && desktop.seat.repeatRate > 0) repeatForWidget(widget, key, desktop.seat.repeatDelay.toLong())
            return
        }
        // A key pressed while the overview or an overlay had the keyboard: its release is not
        // for programs either (they never saw the press); the keyboard's state follows both.
        if (!pressed && keptKeys.remove(evdevCode)) {
            if (!desktop.locked && desktop.overlay != null) desktop.withOverlay { it.key(evdevCode, false) }
            desktop.seat.trackKey(evdevCode, false)
            return
        }
        if (!desktop.locked && desktop.overlay != null) {
            desktop.withOverlay { it.key(evdevCode, pressed) }
            if (pressed) return keepKey(evdevCode) // ...as with the overview below
        }
        if (desktop.inOverview && pressed) {
            if (evdevCode == KEY_ESC) desktop.setOverview(false)
            return keepKey(evdevCode) // new key presses are not for programs while the overview is open...
        }
        if (pressed) keptKeys.remove(evdevCode) // (its release, missed somehow, is the programs' now)
        desktop.seat.key(timeMillis, evdevCode, pressed) // ...but releases of keys they saw pressed are, or keys would stick
    }

    // Keys pressed while the overview or an overlay had them (programs never saw the press).
    private val keptKeys = HashSet<Int>()

    private fun keepKey(evdevCode: Int) {
        keptKeys += evdevCode
        desktop.seat.trackKey(evdevCode, true) // (Shift held there is still held for programs afterwards)
    }

    // The key a widget gets again and again while it is held (programs repeat keys themselves).
    private var widgetRepeat: EventLoop.Timer? = null

    private var widgetRepeatKey = -1

    private fun stopWidgetRepeat() {
        widgetRepeat?.cancel()
        widgetRepeat = null
        widgetRepeatKey = -1
    }

    private fun repeatForWidget(widget: Widget, key: WidgetKey, delayMillis: Long) {
        widgetRepeatKey = key.evdevCode
        widgetRepeat = desktop.compositor.display.loop.after(delayMillis) {
            widgetRepeat = null
            if (desktop.keyboardWidget !== widget || !desktop.lockHost.widgetUsable(widget) || desktop.overlay != null) {
                widgetRepeatKey = -1
                return@after
            }
            val again = WidgetKey(key.evdevCode, true, key.text, key.shift, key.ctrl, key.alt, key.logo, (System.nanoTime() / 1_000_000).toInt())
            userCode("Your widget (key)", Unit) { widget.key(again) }
            repeatForWidget(widget, key, (1000L / desktop.seat.repeatRate.coerceAtLeast(1)).coerceAtLeast(1))
        }
    }
}
