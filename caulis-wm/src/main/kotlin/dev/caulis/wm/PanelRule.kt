package dev.caulis.wm

import dev.caulis.core.Blur
import dev.caulis.core.Effects
import dev.caulis.core.LayerBehavior
import dev.caulis.core.LayerShell
import dev.caulis.core.LayerSurface
import dev.caulis.core.Shadow

/**
 * A rule for some panels (layer surfaces: bars, docks, launchers, notifications), written in
 * `panelRule { }`: which ones ([namespace], [layer], [where]) and how they look and behave.
 *
 * ```
 * panelRule { namespace("waybar"); blur = true }                        // frosted glass behind the bar
 * panelRule { namespace("fuzzel"); dimAround = 0.4f; cornerRadius = 12 }  // the desktop darker behind the launcher
 * panelRule { namespace("notifications"); hiddenFromCapture = true }    // not in screen sharing
 * ```
 *
 * It holds for as long as the panel is there; later rules win where both say something.
 */
class PanelRule internal constructor() {

    private val conditions = ArrayList<Pair<String, (LayerSurface) -> Boolean>>()

    /** Panels that call themselves [name] ("waybar", "launcher", "notifications"; case does not matter). */
    fun namespace(name: String) {
        conditions += "namespace($name)" to { it.namespace.equals(name, ignoreCase = true) }
    }

    /** Panels whose name fits [pattern] somewhere. */
    fun namespace(pattern: Regex) {
        conditions += "namespace(/$pattern/)" to { pattern.containsMatchIn(it.namespace) }
    }

    /** Panels on [layer]: BACKGROUND (wallpapers), BOTTOM, TOP (bars), OVERLAY (launchers, lock screens). */
    fun layer(layer: LayerShell.Layer) {
        conditions += "layer($layer)" to { it.current.layer == layer }
    }

    /** Panels for which [condition] is true. */
    fun where(condition: (LayerSurface) -> Boolean) {
        conditions += "where(${condition.javaClass.name})" to condition
    }

    // --- how they look -------------------------------------------------------------------

    /** False: nothing blurred behind it; true: frosted glass behind its see-through parts. */
    var blur: Boolean? = null

    /** How round its corners are, in pixels. */
    var cornerRadius: Int? = null
        set(value) {
            require(value == null || value in 0..200) { "a corner radius is 0 to 200, not $value" }
            field = value
        }

    /** True: a shadow under it. */
    var shadow: Boolean? = null

    /** How solid it is, 0 to 1. */
    var opacity: Float? = null
        set(value) {
            require(value == null || value in 0f..1f) { "opacity is 0 to 1, not $value" }
            field = value
        }

    internal var effectsChange: ((Effects) -> Effects)? = null

    /** Changes its effects: `effects { it.copy(blur = Blur(8, 3)) }`. */
    fun effects(change: (Effects) -> Effects) {
        effectsChange = change
    }

    // --- how they behave -----------------------------------------------------------------

    /** Everything behind it on its monitor this much darker while it is there (0 to 1). */
    var dimAround: Float? = null
        set(value) {
            require(value == null || value in 0f..1f) { "dimAround is 0 to 1, not $value" }
            field = value
        }

    /** True: screenshots and screen sharing show a black box in its place. */
    var hiddenFromCapture: Boolean? = null

    // --- used by Caulis ------------------------------------------------------------------

    internal fun fits(layer: LayerSurface): Boolean = conditions.all { (_, condition) -> condition(layer) }

    internal fun effectsOf(fx: Effects): Effects {
        var changed = effectsChange?.invoke(fx) ?: fx
        when (blur) {
            false -> changed = changed.copy(blur = null)
            true -> if (changed.blur == null) changed = changed.copy(blur = Blur())
            null -> {}
        }
        cornerRadius?.let { changed = changed.copy(cornerRadius = it) }
        when (shadow) {
            false -> changed = changed.copy(shadow = null, inactiveShadow = null)
            true -> if (changed.shadow == null) changed = changed.copy(shadow = Shadow())
            null -> {}
        }
        opacity?.let { changed = changed.copy(opacity = it, inactiveOpacity = it) }
        return changed
    }

    internal fun behaviorOf(behavior: LayerBehavior): LayerBehavior = behavior.copy(
        dimAround = dimAround ?: behavior.dimAround,
        hiddenFromCapture = hiddenFromCapture ?: behavior.hiddenFromCapture,
    )

    override fun toString() = "panelRule(${conditions.joinToString(", ") { it.first }.ifEmpty { "every panel" }})"
}
