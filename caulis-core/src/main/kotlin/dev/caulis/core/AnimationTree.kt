package dev.caulis.core

/**
 * One named animation of an [AnimationTree] (as Hyprland's `windowsIn`, `workspaces`, `fade`…).
 * A setting for a name ([AnimationEntry]) is for everything under it too, unless something
 * lower down says otherwise.
 */
class AnimationNode @JvmOverloads constructor(
    /** Its name in settings (`windowsIn`). */
    val name: String,
    /** The name above it (null: right under the root, [AnimationTree.ROOT]). */
    val parent: String? = null,
    /** What it animates, in a few words (for documentation and messages). */
    val about: String = "",
    /**
     * Its own time in milliseconds when no setting gives one (null: the global time). A
     * compositor's own animations keep their usual length this way.
     */
    val millis: Long? = null,
    /** Its own curve when no setting gives one (null: the global curve). */
    val curve: Curve? = null,
    /** The looks it can take ([AnimationEntry.style]), e.g. "popin 80%" (empty: it has one look). */
    val styles: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank() && name != AnimationTree.ROOT) { "an animation is not called \"$name\"" }
        require(millis == null || millis in 0..10_000) { "an animation takes 0 to 10000 milliseconds, not $millis" }
    }

    override fun toString() = "AnimationNode($name${parent?.let { " under $it" } ?: ""})"
}

/**
 * The names of a compositor's animations as a tree, as in Hyprland: a setting for `windows`
 * is for `windowsIn`, `windowsOut` and `windowsMove` too, unless they have their own. Caulis'
 * own are [CAULIS]; a compositor on Caulis makes its own tree for its own animations and
 * reads them with [AnimationSettings].
 */
class AnimationTree(nodes: List<AnimationNode>) {
    /** Every animation, parents before their children. */
    val nodes: List<AnimationNode>

    private val byName: Map<String, AnimationNode>

    init {
        byName = LinkedHashMap<String, AnimationNode>().also { map ->
            for (node in nodes) {
                require(map.put(node.name, node) == null) { "two animations are called \"${node.name}\"" }
            }
        }
        for (node in nodes) {
            val parent = node.parent ?: continue
            require(parent in byName) { "${node.name}'s parent \"$parent\" is not an animation of the tree" }
        }
        // Parents first (for documentation), keeping the given order otherwise.
        val ordered = ArrayList<AnimationNode>()
        fun add(parent: String?) {
            for (node in nodes) if (node.parent == parent) {
                ordered += node
                add(node.name)
            }
        }
        add(null)
        require(ordered.size == nodes.size) { "the animations' parents go round in a circle" }
        this.nodes = ordered
    }

    /** All the names, parents before their children. */
    val names: List<String> get() = nodes.map { it.name }

    operator fun get(name: String): AnimationNode? = byName[name]

    operator fun contains(name: String) = name in byName

    /** [name], its parent, its parent's parent… (the root, [ROOT], is not in it). */
    fun chain(name: String): List<AnimationNode> {
        val out = ArrayList<AnimationNode>(4)
        var node = byName[name] ?: throw IllegalArgumentException(unknown(name))
        while (true) {
            out += node
            node = byName[node.parent ?: return out]!!
        }
    }

    /** The animations right under [name] (null or [ROOT]: those right under the root). */
    fun childrenOf(name: String?): List<AnimationNode> {
        val parent = name?.takeIf { it != ROOT }
        return nodes.filter { it.parent == parent }
    }

    /** "there is no animation X (there are: …)". */
    fun unknown(name: String) = "there is no animation \"$name\" (there are: ${names.joinToString()})"

    /** The tree as text, one name a line, indented under its parent, with what it animates. */
    fun describe(): String = buildString {
        fun add(parent: String?, depth: Int) {
            for (node in childrenOf(parent)) {
                append("  ".repeat(depth)).append(node.name)
                if (node.about.isNotEmpty()) append(" — ").append(node.about)
                append('\n')
                add(node.name, depth + 1)
            }
        }
        append(ROOT).append('\n')
        add(null, 1)
    }

    override fun toString() = "AnimationTree(${names.joinToString()})"

    companion object {
        /** The root's name: settings of the whole tree ([AnimationSettings.enabled], millis, curve). */
        const val ROOT = "global"

        /** The looks of a window's animation, by name (see [WindowAnimation.named]). */
        @JvmField val WINDOW_STYLES = listOf("popin [80%]", "pop [90%]", "fade", "scale [90%]", "slide [up|down|left|right] [40]", "slide-up", "genie", "fly", "move", "none")

        /** The looks of a workspace change, by name (see [WorkspaceAnimation.named]). */
        @JvmField val WORKSPACE_STYLES = listOf("slide", "slidevert", "fade", "none")

        /** Caulis' own animations (see [Animations]). */
        @JvmField val CAULIS = AnimationTree(
            listOf(
                AnimationNode("windows", null, "every animation of windows below", styles = WINDOW_STYLES),
                AnimationNode("windowsIn", "windows", "a window appearing (Animations.windowOpen)", styles = WINDOW_STYLES),
                AnimationNode("windowsOut", "windows", "a window going away (Animations.windowClose)", styles = WINDOW_STYLES),
                AnimationNode("windowsMove", "windows", "windows gliding to a new place or size: a layout change, maximize, a neighbour closing, the view settling after a swipe (Animations.windowMove)", styles = listOf("move", "none")),
                AnimationNode("scratchpads", "windows", "a scratchpad sliding in and out (Animations.windowMove's look)", styles = listOf("move", "none")),
                AnimationNode("minimize", "windows", "a window going into the dock (Animations.minimize)", styles = WINDOW_STYLES),
                AnimationNode("restore", "windows", "a minimized window coming back (Animations.restore)", styles = WINDOW_STYLES),
                AnimationNode("workspaces", null, "another workspace shown, also after a swipe (Animations.workspaceSwitch)", styles = WORKSPACE_STYLES),
                AnimationNode("overview", null, "the overview zooming out and in, also finishing a pinch (Animations.overviewMillis; 1.5 × the global time)"),
                AnimationNode("border", null, "border, shadow, opacity and dimming changing when the focus moves (Effects.focusChangeMillis, 150)", millis = 150),
                AnimationNode("screenshot", null, "the screenshot chooser coming in: the screen darkening, its toolbar rising (caulis-screenshot; ChooserBehaviour.appearMillis, 170)", millis = 170, curve = Curve.EASE_OUT),
                AnimationNode("fade", null, "every fade below"),
                AnimationNode("fadeScale", "fade", "the picture melting into the new one when a monitor's scale changes (Animations.scaleChangeMillis, 250)", millis = 250, curve = Curve.SMOOTH),
                AnimationNode("fadeBackground", "fade", "one background melting into the next (backgroundChangeMillis, 400)", millis = 400, curve = Curve.SMOOTH),
                AnimationNode("fadeNotices", "fade", "the compositor's notices fading in and out (NoticeStyle.fadeMillis, 180)", millis = 180, curve = Curve.SMOOTH),
                AnimationNode("fadeOsd", "fade", "the volume and brightness display fading in and out (150)", millis = 150, curve = Curve.LINEAR),
            ),
        )
    }
}

/**
 * The settings of one named animation (Hyprland's `animation = NAME, ONOFF, SPEED, CURVE,
 * STYLE`); each null is as the animation above it in the tree says (in the end: the global
 * settings, or the animation's usual).
 *
 * ```
 * AnimationEntry(millis = 300, curve = Curve.OVERSHOOT)   // windowsIn: longer, lively
 * AnimationEntry(style = "slide left")                     // another look
 * AnimationEntry.OFF                                       // this one (and those under it) not at all
 * AnimationEntry.ON                                        // on, even while the others are off
 * ```
 */
data class AnimationEntry @JvmOverloads constructor(
    /** On or off (null: as above it; at the top, [AnimationSettings.enabled]). */
    val enabled: Boolean? = null,
    /** How long it takes, in milliseconds (0: at once). */
    val millis: Long? = null,
    /** How it moves. */
    val curve: Curve? = null,
    /** Its look by name, for animations that have more than one (see [AnimationNode.styles]). */
    val style: String? = null,
) {
    init {
        require(millis == null || millis in 0..10_000) { "an animation takes 0 to 10000 milliseconds, not $millis" }
    }

    /** These settings, with [other]'s where it has some. */
    fun and(other: AnimationEntry) = AnimationEntry(other.enabled ?: enabled, other.millis ?: millis, other.curve ?: curve, other.style ?: style)

    companion object {
        /** Off (with everything under it that does not say otherwise). */
        @JvmField val OFF = AnimationEntry(enabled = false)

        /** On, with the usual time and curve (even while [AnimationSettings.enabled] is false). */
        @JvmField val ON = AnimationEntry(enabled = true)
    }
}

/** What a named animation comes to (see [AnimationSettings.resolve]). */
class ResolvedAnimation(
    val name: String,
    /** Turned on (by its own setting, one above it, or the global one). */
    val enabled: Boolean,
    /** How long it takes, in milliseconds. */
    val millis: Long,
    val curve: Curve,
    /** Its look by name (null: its usual). */
    val style: String?,
) {
    /** True when it runs at all: turned on, and longer than 0. Off: the change is there at once. */
    val on: Boolean get() = enabled && millis > 0

    /** Its time in milliseconds, 0 when it is off. */
    val effectiveMillis: Long get() = if (on) millis else 0L

    /** Its time in seconds, 0 when it is off. */
    val seconds: Double get() = effectiveMillis / 1000.0

    /** How far along it is when [t] of its time has passed (through the curve); 1 when off or over. */
    fun at(t: Double): Double = if (!on || t >= 1.0) 1.0 else curve.at(t.coerceAtLeast(0.0))

    /** How much of its time has passed (0 to 1) [nowNanos] for one that began at [startNanos]; null once over (or off). */
    fun time(startNanos: Long, nowNanos: Long): Double? {
        if (!on) return null
        val t = (nowNanos - startNanos) / (millis * 1_000_000.0)
        return if (t >= 1.0) null else t.coerceAtLeast(0.0)
    }

    override fun toString() = "$name: " + if (on) "$millis ms${style?.let { ", $it" } ?: ""}" else "off"
}

/**
 * Hyprland-like animation settings for an [AnimationTree]: a global switch ([enabled]), a
 * global time ([millis]) and curve, and settings by name ([entries]) for parts of the tree.
 * Caulis' own are in [Animations]; a compositor on Caulis keeps one of these for its own.
 *
 * What an animation comes to ([resolve]): for each of on/off, time, curve and look, its own
 * entry; else the nearest entry above it that says; else the animation's usual (what code
 * gives, or [AnimationNode.millis]/[AnimationNode.curve]); else the global one. An entry's
 * `enabled = true` turns an animation on even while [enabled] is false: one line is enough to
 * have just that one.
 */
class AnimationSettings @JvmOverloads constructor(
    val tree: AnimationTree,
    /** Whether animations run unless an entry says otherwise. Off (the default): everything changes at once. */
    val enabled: Boolean = false,
    /** The time of animations that have none of their own, in milliseconds. */
    val millis: Long = 220,
    /** The curve of animations that have none of their own. */
    val curve: Curve = Curve.EASE_OUT,
    /** Settings by animation name (names of [tree]). */
    val entries: Map<String, AnimationEntry> = emptyMap(),
) {
    init {
        require(millis in 0..10_000) { "an animation takes 0 to 10000 milliseconds, not $millis" }
        for (name in entries.keys) require(name in tree) { tree.unknown(name) }
    }

    /**
     * What [name] comes to; [usualMillis] and [usualCurve] are the animation's usual time and
     * curve (what the code, or an older setting, says), used when no entry says.
     */
    @JvmOverloads
    fun resolve(name: String, usualMillis: Long? = null, usualCurve: Curve? = null): ResolvedAnimation {
        val chain = tree.chain(name)
        var on: Boolean? = null
        var time: Long? = null
        var curve: Curve? = null
        var style: String? = null
        for (node in chain) {
            val entry = entries[node.name] ?: continue
            if (on == null) on = entry.enabled
            if (time == null) time = entry.millis
            if (curve == null) curve = entry.curve
            if (style == null) style = entry.style
        }
        val finalCurve = curve ?: usualCurve ?: chain.firstNotNullOfOrNull { it.curve } ?: this.curve
        val finalTime = time ?: usualMillis ?: chain.firstNotNullOfOrNull { it.millis } ?: finalCurve.naturalMillis ?: millis
        return ResolvedAnimation(name, on ?: enabled, finalTime, finalCurve, style)
    }

    /** The same with [entry] for [name] (added to what [name] had). */
    fun with(name: String, entry: AnimationEntry): AnimationSettings {
        require(name in tree) { tree.unknown(name) }
        return AnimationSettings(tree, enabled, millis, curve, entries + (name to (entries[name]?.and(entry) ?: entry)))
    }

    /** One line per animation: what it comes to now (for logs). */
    fun describe(): String = tree.nodes.joinToString("\n") { resolve(it.name).toString() }

    override fun toString() = "AnimationSettings(${if (enabled) "on" else "off"}, $millis ms, ${entries.size} entries)"
}
