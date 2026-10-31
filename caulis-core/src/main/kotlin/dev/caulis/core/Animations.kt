package dev.caulis.core

/**
 * All of the compositor's animations, Hyprland-like: a global switch ([enabled], off by
 * default: everything changes at once), named animations in a tree ([AnimationTree.CAULIS]:
 * windows → windowsIn, windowsOut, windowsMove, scratchpads, minimize, restore; workspaces; overview;
 * border; screenshot; fade → fadeScale, fadeBackground, fadeNotices, fadeOsd) with settings by name
 * ([entries]: on/off, time, curve, look), and the looks themselves as code ([WindowAnimation],
 * [WorkspaceAnimation]). Times are [Desktop.animationMillis] unless an animation has its own.
 *
 * ```
 * Animations(enabled = true)                                        // the usual, on
 * Animations(enabled = true, minimize = WindowAnimation.genie())    // macOS's genie
 * Animations(enabled = true, windowOpen = WindowAnimation.fade() + WindowAnimation.slide(dy = 80),
 *            workspaceSwitch = WorkspaceAnimation.SLIDE_VERTICAL.lasting(300))
 * Animations(enabled = true)
 *     .with("windows", millis = 300, curve = Curve.bezier(0.05, 0.9, 0.1, 1.05))
 *     .with("windowsIn", style = "popin 80%")
 *     .with("fade", enabled = false)
 * Animations().with("workspaces", enabled = true)                   // just this one
 * Animations.NONE                                                   // nothing moves
 * ```
 */
data class Animations @JvmOverloads constructor(
    /** A window appearing ([Motion.progress] 0 → 1). */
    val windowOpen: WindowAnimation = WindowAnimation.pop(),
    /** A window going away (its last look; [Motion.progress] 1 → 0). */
    val windowClose: WindowAnimation = WindowAnimation.pop(),
    /** A window gliding to a new place or size (a layout change, maximize, a neighbour closing). */
    val windowMove: WindowAnimation = WindowAnimation.move(),
    /** A window minimized: [Motion.to] is its dock icon (or a small place at the bottom). */
    val minimize: WindowAnimation = WindowAnimation.genie(),
    /** A minimized window coming back ([Motion.from] is its dock icon). */
    val restore: WindowAnimation = WindowAnimation.genie(),
    /** Another workspace shown on a monitor. */
    val workspaceSwitch: WorkspaceAnimation = WorkspaceAnimation.SLIDE,
    /** The overview zooming out and in (its time is 1.5 × [Desktop.animationMillis] unless given). */
    val overviewMillis: Long? = null,
    /** The curve of every animation that has none of its own. */
    val curve: Curve = Curve.EASE_OUT,
    /** How long the picture before a scale change melts into the new one (milliseconds; 0: at once). */
    val scaleChangeMillis: Long = 250,
    /**
     * Whether animations run unless [entries] say otherwise. False (the default): nothing
     * moves, everything is where it goes at once (following fingers is not an animation: a
     * swipe or pinch still follows them, only what comes after they lift is at once).
     */
    val enabled: Boolean = false,
    /** Settings by animation name ([AnimationTree.CAULIS]), as Hyprland's `animation = …` lines. */
    val entries: Map<String, AnimationEntry> = emptyMap(),
) {
    init {
        require(scaleChangeMillis in 0..10_000) { "scaleChangeMillis is 0 to 10000" }
        for ((name, entry) in entries) {
            require(name in AnimationTree.CAULIS) { AnimationTree.CAULIS.unknown(name) }
            entry.style?.let { style -> styleProblem(name, style)?.let { throw IllegalArgumentException(it) } }
        }
    }

    /**
     * The same with settings for the animation [name] (of [AnimationTree.CAULIS]) — for it
     * and all under it that do not say otherwise; nulls are left as they were.
     */
    @JvmOverloads
    fun with(name: String, enabled: Boolean? = null, millis: Long? = null, curve: Curve? = null, style: String? = null): Animations {
        require(name in AnimationTree.CAULIS) { AnimationTree.CAULIS.unknown(name) }
        val entry = AnimationEntry(enabled, millis, curve, style)
        return copy(entries = entries + (name to (entries[name]?.and(entry) ?: entry)))
    }

    // Caulis 0.1.1's copy (code built against it calls it).
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    fun copy(
        windowOpen: WindowAnimation = this.windowOpen, windowClose: WindowAnimation = this.windowClose,
        windowMove: WindowAnimation = this.windowMove, minimize: WindowAnimation = this.minimize,
        restore: WindowAnimation = this.restore, workspaceSwitch: WorkspaceAnimation = this.workspaceSwitch,
        overviewMillis: Long? = this.overviewMillis, curve: Curve = this.curve, scaleChangeMillis: Long = this.scaleChangeMillis,
    ): Animations = Animations(windowOpen, windowClose, windowMove, minimize, restore, workspaceSwitch, overviewMillis, curve, scaleChangeMillis, enabled, entries)

    /** These as [AnimationSettings], with [millis] as the global time ([Desktop.animationMillis]). */
    fun settings(millis: Long): AnimationSettings = AnimationSettings(AnimationTree.CAULIS, enabled, millis, curve, entries)

    companion object {
        /** The usual animations, turned on. */
        @JvmField val ON = Animations(enabled = true)

        // The animations whose look is a window's, and those that only move windows.
        private val WINDOW_LOOKS = setOf("windows", "windowsIn", "windowsOut", "minimize", "restore")
        private val MOVES = setOf("windowsMove", "scratchpads")

        /**
         * Why [style] is not a look the animation [name] can take, or null when it is (a
         * window's look such as "popin 80%" for windows, "slide"/"slidevert"/"fade" for
         * workspaces; "move" or "none" for windows moving; animations with one look take none).
         */
        @JvmStatic
        fun styleProblem(name: String, style: String): String? = when (name) {
            in WINDOW_LOOKS -> if (WindowAnimation.named(style) == null) "$name: there is no window look \"$style\" (there are: ${AnimationTree.WINDOW_STYLES.joinToString()})" else null
            in MOVES -> if (style.trim().lowercase() !in setOf("move", "none")) "$name: its look is \"move\" or \"none\", not \"$style\"" else null
            "workspaces" -> if (WorkspaceAnimation.named(style) == null) "$name: there is no workspace look \"$style\" (there are: ${AnimationTree.WORKSPACE_STYLES.joinToString()})" else null
            else -> "$name has one look only (no style), not \"$style\""
        }

        /** Nothing moves: windows and workspaces change at once. */
        @JvmField val NONE = Animations(
            WindowAnimation.NONE, WindowAnimation.NONE, WindowAnimation.NONE, WindowAnimation.NONE, WindowAnimation.NONE,
            WorkspaceAnimation.NONE, overviewMillis = 0, scaleChangeMillis = 0,
        )
    }
}

/**
 * [Animations] put together line by line — what `caulis.animations { … }` gives, as
 * Hyprland's `animations { … }` section:
 *
 * ```
 * animations {
 *     enabled = true                                   // all of them (off by default)
 *     millis = 250                                     // the global time
 *     curve = Curve.bezier(0.05, 0.9, 0.1, 1.05)       // the global curve
 *     animation("windowsIn", style = "popin 80%")
 *     animation("workspaces", millis = 300, style = "slidevert")
 *     off("fade")                                      // and everything under it
 * }
 * ```
 */
class AnimationsBuilder(start: Animations) {
    /** What it has become so far. */
    var animations: Animations = start
        private set

    /** All animations on (unless one says otherwise) or off (the default). */
    var enabled: Boolean
        get() = animations.enabled
        set(value) {
            animations = animations.copy(enabled = value)
        }

    /** The global time in milliseconds (null: as it was; Desktop.animationMillis). */
    var millis: Long? = null
        set(value) {
            require(value == null || value in 0..10_000) { "an animation takes 0 to 10000 milliseconds, not $value" }
            field = value
        }

    /** The curve of every animation that has none of its own. */
    var curve: Curve
        get() = animations.curve
        set(value) {
            animations = animations.copy(curve = value)
        }

    /**
     * Settings for the animation [name] (see [AnimationTree.CAULIS]) and those under it: on
     * or off, its time ([millis]; or [speed] in Hyprland's tenths of a second), curve and look.
     */
    @JvmOverloads
    fun animation(name: String, enabled: Boolean? = null, millis: Long? = null, curve: Curve? = null, style: String? = null, speed: Double? = null) {
        require(speed == null || speed in 0.0..100.0) { "speed is 0 to 100 (tenths of a second), not $speed" }
        animations = animations.with(name, enabled, millis ?: speed?.let { Math.round(it * 100) }, curve, style)
    }

    /** Just [name] (and those under it) on, even while the rest is off. */
    fun on(name: String) = animation(name, enabled = true)

    /** [name] (and those under it) off. */
    fun off(name: String) = animation(name, enabled = false)

    /** Everything as code: the looks of windows and workspaces (see [Animations]). */
    fun looks(change: (Animations) -> Animations) {
        animations = change(animations)
    }
}
