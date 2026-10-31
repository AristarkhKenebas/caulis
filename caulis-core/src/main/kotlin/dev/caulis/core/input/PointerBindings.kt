package dev.caulis.core.input

import dev.caulis.core.input.KeyBindings.Modifier
import kotlin.math.abs

/**
 * What the mouse's buttons and wheels do, apart from the keyboard's combinations: any button (by
 * its name or Linux code, [KeyBindings.parse]: "Super+MouseSide", "BTN_TASK", "mouse:0x115"), and
 * the wheel in all four ways ("Super+WheelLeft": a side wheel), with any modifiers.
 *
 * ```
 * val mouse = PointerBindings()
 * mouse.press("Super+WheelRight", PointerBindings.Options(cooldownMillis = 150)) { nextWorkspace() }
 * mouse.press("MouseSide", PointerBindings.Options(pass = true)) { log("back") }     // the program gets it too
 * mouse.click("MouseMiddle") { overview() }
 * mouse.longPress("MouseMiddle", 500) { launcher() }
 * mouse.gesture("Super+MouseExtra", mapOf(PointerBindings.Direction.LEFT to { previous() }, PointerBindings.Direction.RIGHT to { next() }))
 * ```
 *
 * A button can have several of these at once. Feed it [button], [scroll] and [motion]; each
 * returns true when the event is taken (do not pass it on). A press and its release always go the
 * same way: a press given to a program is let go for that program too, whatever modifiers changed
 * in between. It knows nothing of windows or a seat, and holds no secrets: it logs nothing.
 *
 * The [modifiers][Modifier] are the caller's, usually [KeyBindings.heldModifiers]; they must match
 * exactly, as for key combinations (Super+Shift+button is not Super+button).
 */
class PointerBindings {

    /** A way the pointer goes while a button is held. */
    enum class Direction { LEFT, RIGHT, UP, DOWN }

    /**
     * How a binding behaves: [pass] the press and the release on to the program under the pointer
     * as well (false: taken, the program never hears of it); works [whileLocked] (false: behind a
     * lock screen it does not exist); runs at most once per [cooldownMillis] (0: always; a side
     * wheel spins fast); [description] says what it does (for lists of keys).
     */
    data class Options @JvmOverloads constructor(
        val pass: Boolean = false,
        val whileLocked: Boolean = false,
        val cooldownMillis: Long = 0,
        val description: String? = null,
    )

    /**
     * A button held and moved: after [threshold] units in a way the action of that way runs, again
     * after each [repeatEvery] further units (0: once per hold). With [freezePointer] the pointer
     * stays where it was while the button is held. A press and release without such a movement is
     * a click.
     */
    data class GestureOptions @JvmOverloads constructor(
        val threshold: Double = 40.0,
        val repeatEvery: Double = 0.0,
        val freezePointer: Boolean = true,
    )

    /** Hears the movement while a button is held, and says whether it is taken. */
    fun interface HoldMoved {
        fun moved(dx: Double, dy: Double): Boolean
    }

    private class Entry(val modifiers: Set<Modifier>, val code: Int, val combination: String) {
        var options = Options()
        var press: (() -> Unit)? = null
        var release: (() -> Unit)? = null
        var click: (() -> Unit)? = null
        var longPress: (() -> Unit)? = null
        var longPressMillis = 0L
        var directions: Map<Direction, () -> Unit> = emptyMap()
        var gesture = GestureOptions()
        var holdStart: (() -> Unit)? = null
        var holdMoved: HoldMoved? = null
        var holdEnd: (() -> Unit)? = null
        var lastRun = Long.MIN_VALUE / 2
    }

    // One button going on being held.
    private class Held(val entry: Entry, val code: Int) {
        var x = 0.0
        var y = 0.0
        var usedUp = false // (it became a gesture or a long press: no click at the end)
        var direction: Direction? = null
        var firedAt = 0.0
        var timer: AutoCloseable? = null
    }

    private val entries = LinkedHashMap<Pair<Set<Modifier>, Int>, Entry>()
    private val held = LinkedHashMap<Int, Held>()
    private var wheelParts = doubleArrayOf(0.0, 0.0)

    /** Runs `task` once after a delay in milliseconds; set by whoever has a loop. Needed for [longPress]. */
    var schedule: ((Long, () -> Unit) -> AutoCloseable)? = null

    /** Told when one of the bindings' actions throws (else it is ignored: input goes on). */
    var onFailure: ((Exception) -> Unit)? = null

    private fun entry(combination: String, options: Options?): Entry {
        val (modifiers, code) = KeyBindings.parse(combination)
        val entry = entries.getOrPut(modifiers to code) { Entry(modifiers, code, combination) }
        if (options != null) entry.options = options
        return entry
    }

    /** Runs [action] when [combination] is pressed (the wheel: at each step). */
    @JvmOverloads
    fun press(combination: String, options: Options = Options(), action: () -> Unit) {
        entry(combination, options).press = action
    }

    /** Runs [action] when the button of [combination] is let go. */
    @JvmOverloads
    fun release(combination: String, options: Options = Options(), action: () -> Unit) {
        entry(combination, options).release = action
    }

    /** Runs [action] when the button is pressed and let go with no gesture or long press in between. */
    @JvmOverloads
    fun click(combination: String, options: Options = Options(), action: () -> Unit) {
        entry(combination, options).click = action
    }

    /** Runs [action] when the button stays down [millis] without becoming a gesture (needs [schedule]). */
    @JvmOverloads
    fun longPress(combination: String, millis: Long, options: Options = Options(), action: () -> Unit) {
        val e = entry(combination, options)
        e.longPress = action
        e.longPressMillis = millis.coerceAtLeast(1)
    }

    /** The button held and moved: the action of the way it went (see [GestureOptions]). */
    @JvmOverloads
    fun gesture(combination: String, directions: Map<Direction, () -> Unit>, gesture: GestureOptions = GestureOptions(), options: Options = Options()) {
        val e = entry(combination, options)
        e.directions = directions
        e.gesture = gesture
    }

    /**
     * The whole of a hold, for what follows the pointer itself (a menu at the pointer): [start] on
     * the press, [moved] with each movement while held, [end] on the release, or when forgotten.
     */
    @JvmOverloads
    fun hold(combination: String, options: Options = Options(), start: () -> Unit, moved: HoldMoved? = null, end: (() -> Unit)? = null) {
        val e = entry(combination, options)
        e.holdStart = start
        e.holdMoved = moved
        e.holdEnd = end
    }

    /** [combination] does nothing any more. */
    fun unbind(combination: String) {
        val (modifiers, code) = KeyBindings.parse(combination)
        entries.remove(modifiers to code)
    }

    /** Forgets every binding (a hold going on ends quietly, but its release is still taken). */
    fun clear() {
        for (h in held.values) h.timer?.close()
        entries.clear()
    }

    /** True if something is bound to [code] with exactly [modifiers]. */
    fun has(code: Int, modifiers: Set<Modifier>): Boolean = (modifiers to code) in entries

    /** The combinations with a description: combination → what it does. */
    fun described(): List<Pair<String, String>> = entries.values.mapNotNull { e -> e.options.description?.let { e.combination to it } }

    /** A hold is going on whose movement is not for the pointer. */
    val frozen: Boolean get() = held.values.any { it.entry.frozen() }

    private fun Entry.frozen() = !options.pass && (holdMoved != null || (directions.isNotEmpty() && gesture.freezePointer))

    private fun safely(action: (() -> Unit)?) {
        try {
            action?.invoke()
        } catch (e: Exception) {
            onFailure?.invoke(e)
        }
    }

    /**
     * A button went down or up ([code]: Linux code), with [modifiers] held, at [nowMillis];
     * true: taken. [locked]: behind a lock screen only [Options.whileLocked] bindings exist.
     */
    @JvmOverloads
    fun button(code: Int, pressed: Boolean, modifiers: Set<Modifier>, nowMillis: Long = System.nanoTime() / 1_000_000, locked: Boolean = false): Boolean {
        if (!pressed) {
            val h = held.remove(code) ?: return false
            h.timer?.close()
            val e = h.entry
            safely(e.holdEnd)
            safely(e.release)
            if (!h.usedUp) safely(e.click)
            return !e.options.pass
        }
        // (The release of an earlier press was lost: it is over.)
        held.remove(code)?.timer?.close()
        val e = entries[modifiers to code] ?: return false
        if (locked && !e.options.whileLocked) return false
        val h = Held(e, code)
        held[code] = h
        if (e.press != null && canRun(e, nowMillis)) safely(e.press)
        safely(e.holdStart)
        val long = e.longPress
        val later = schedule
        if (long != null && later != null) {
            h.timer = later(e.longPressMillis) {
                // (Still this very hold.)
                if (held[code] === h && !h.usedUp) {
                    h.usedUp = true
                    safely(long)
                }
            }
        }
        return !e.options.pass
    }

    private fun canRun(e: Entry, nowMillis: Long): Boolean {
        if (e.options.cooldownMillis > 0 && nowMillis - e.lastRun < e.options.cooldownMillis) return false
        e.lastRun = nowMillis
        return true
    }

    /**
     * The wheel turned: [value120] parts of a step (120 is one, negative: up or left), hi-res wheels
     * sending parts. True: taken. A wheel with no binding that way is not taken, so it scrolls.
     */
    @JvmOverloads
    fun scroll(vertical: Boolean, value120: Int, modifiers: Set<Modifier>, nowMillis: Long = System.nanoTime() / 1_000_000, locked: Boolean = false): Boolean {
        if (value120 == 0) return false
        val code = when {
            vertical -> if (value120 < 0) KeyBindings.WHEEL_UP else KeyBindings.WHEEL_DOWN
            else -> if (value120 < 0) KeyBindings.WHEEL_LEFT else KeyBindings.WHEEL_RIGHT
        }
        val e = entries[modifiers to code] ?: return false
        if (locked && !e.options.whileLocked) return false
        val axis = if (vertical) 0 else 1
        // (Turned back: the parts of the other way count no more.)
        if (wheelParts[axis] * value120 < 0) wheelParts[axis] = 0.0
        wheelParts[axis] += value120 / 120.0
        while (abs(wheelParts[axis]) >= 1.0) {
            wheelParts[axis] -= Math.signum(wheelParts[axis])
            if (canRun(e, nowMillis)) safely(e.press)
        }
        return !e.options.pass
    }

    /** The pointer moved while buttons may be held; true: the movement is taken (the pointer stays). */
    fun motion(dx: Double, dy: Double): Boolean {
        if (held.isEmpty()) return false
        var taken = false
        for (h in held.values.toList()) {
            val e = h.entry
            e.holdMoved?.let { if (it.moved(dx, dy) && !e.options.pass) taken = true }
            if (e.directions.isEmpty()) continue
            h.x += dx
            h.y += dy
            directionOf(h)
            if (e.frozen()) taken = true
        }
        return taken
    }

    private fun directionOf(h: Held) {
        val e = h.entry
        val g = e.gesture
        // The way is chosen once, by the longer side; a hold then counts along it.
        var way = h.direction
        if (way == null) {
            if (Math.hypot(h.x, h.y) < g.threshold) return
            way = if (abs(h.x) >= abs(h.y)) (if (h.x < 0) Direction.LEFT else Direction.RIGHT) else (if (h.y < 0) Direction.UP else Direction.DOWN)
            h.direction = way
            h.usedUp = true
            h.timer?.close()
            safely(e.directions[way])
            h.firedAt = along(h, way)
        }
        if (g.repeatEvery <= 0.0) return
        while (along(h, way) - h.firedAt >= g.repeatEvery) {
            h.firedAt += g.repeatEvery
            safely(e.directions[way])
        }
    }

    private fun along(h: Held, way: Direction): Double = when (way) {
        Direction.LEFT -> -h.x
        Direction.RIGHT -> h.x
        Direction.UP -> -h.y
        Direction.DOWN -> h.y
    }

    /** Forgets what is held (the mouse went to another session): holds end, nothing else runs. */
    fun releaseAll() {
        for (h in held.values) {
            h.timer?.close()
            safely(h.entry.holdEnd)
        }
        held.clear()
        wheelParts = doubleArrayOf(0.0, 0.0)
    }

    companion object {
        /**
         * The usual words for a button or wheel of the Linux code [code]: "MouseSide (BTN_SIDE, 275)",
         * for tools that say what was pressed. Most mice's back button is BTN_SIDE, their forward
         * button BTN_EXTRA; BTN_BACK and BTN_FORWARD are other buttons.
         */
        @JvmStatic
        fun describe(code: Int): String {
            val btn = BTN_NAMES[code]
            val name = KeyBindings.nameOf(code)
            return when {
                btn != null -> "${name ?: "mouse:$code"} ($btn, $code)"
                code in 0x100..0x2ff -> "mouse:$code (code $code)"
                else -> name ?: "code $code"
            }
        }

        private val BTN_NAMES = mapOf(
            0x110 to "BTN_LEFT", 0x111 to "BTN_RIGHT", 0x112 to "BTN_MIDDLE", 0x113 to "BTN_SIDE",
            0x114 to "BTN_EXTRA", 0x115 to "BTN_FORWARD", 0x116 to "BTN_BACK", 0x117 to "BTN_TASK",
        )
    }
}
