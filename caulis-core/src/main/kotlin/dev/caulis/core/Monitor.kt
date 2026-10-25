package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * One monitor: the part of the desktop it shows. All monitors together make one desktop
 * in desktop units ([area] is this monitor's part of it); each has its own scale (how many
 * of its pixels one unit is), its own panels, and shows one workspace (see [Desktop.workspaceMode]).
 *
 * Get them from [Desktop.monitors]; the backend adds and removes them with [Desktop.addMonitor]
 * and [Desktop.removeMonitor].
 */
class Monitor internal constructor(
    /** The connector, e.g. "eDP-1", "DP-2", "HDMI-A-1", or "NESTED-1". */
    val name: String,
    /** Its wl_output (what programs see), or null (tests). */
    val output: Output?,
    pixelWidth: Int,
    pixelHeight: Int,
    scale: Double,
) {
    /** Its size in pixels (the current mode). */
    var pixelWidth = pixelWidth
        internal set
    var pixelHeight = pixelHeight
        internal set

    /** How many of its pixels one desktop unit is: 1.0, 1.25, 1.5, 2.0, ... */
    var scale = scale
        internal set

    /** Where it is on the desktop and how big, in desktop units. */
    var area: Rect = Rect(0, 0, units(pixelWidth), units(pixelHeight))
        internal set

    /** What panels and docks on it leave free: windows are tiled and maximized in here. */
    var usableArea: Rect = area
        internal set

    /** The workspace it shows. */
    lateinit var workspace: Workspace
        internal set

    /** How it is turned (its size is the turned one: width and height traded when sideways). */
    var transform: Transform = Transform.NORMAL
        internal set

    /** False while its screen is switched off (DPMS); everything on it stays. */
    var powered = true
        internal set

    /** What the backend can do with it (modes, power, gamma), or null. */
    var control: MonitorControl? = null

    // Set from the position the user gave (else it is placed after the others).
    internal var fixedPosition: Pair<Int, Int>? = null

    // The size in units its pixels make at its scale.
    internal fun units(pixels: Int) = Math.round(pixels / scale).toInt().coerceAtLeast(1)

    internal fun resized() {
        area = Rect(area.x, area.y, units(pixelWidth), units(pixelHeight))
    }

    /** A rectangle in desktop units as this monitor's pixels (edges rounded the same way everywhere). */
    fun toPixels(rect: Rect): Rect {
        val left = px(rect.x - area.x)
        val top = px(rect.y - area.y)
        return Rect(left, top, px(rect.right - area.x) - left, px(rect.bottom - area.y) - top)
    }

    internal fun px(value: Int): Int = Math.round(value * scale).toInt()

    /** True if the desktop point ([x], [y]) is on this monitor. */
    fun contains(x: Double, y: Double) = x >= area.x && x < area.right && y >= area.y && y < area.bottom

    override fun toString() = "Monitor($name, ${pixelWidth}x$pixelHeight at ${area.x},${area.y}, scale $scale)"
}

/**
 * How workspaces behave with several monitors.
 */
enum class WorkspaceMode {
    /**
     * Each monitor shows a workspace; the numbers are shared (as sway, Hyprland): Super+N
     * shows workspace N on the monitor with the focus — or, if another monitor shows it,
     * goes there. A workspace with a home monitor ([WorkspaceRule.monitor]) always goes there.
     */
    PER_MONITOR,

    /**
     * Workspaces are screens: each one has its monitor (the one in its rule, else the one
     * that showed it first) and stays there — Super+2 may simply be "the second monitor".
     * Fits one bar showing all workspaces.
     */
    PINNED,

    /**
     * Every monitor has workspaces of its own, 1, 2, 3, ... (as dwm, awesome, qtile): Super+N
     * switches the monitor with the focus; windows move between monitors freely.
     */
    SEPARATE,

    /**
     * A workspace spans all monitors (as GNOME): Super+N switches every monitor to its part
     * of workspace N at once.
     */
    SPANNING,

    /**
     * Only the primary monitor ([Desktop.primaryMonitor]) has workspaces; every other monitor
     * is one screen of its own (workspace number 0) — windows can be moved there, nothing
     * switches there.
     */
    PRIMARY_ONLY,
}

/**
 * How workspace [number] is: its name, its monitor, its own layout. Desktop.workspaceRules
 * (Caulis: `workspace(3) { name = "web"; monitor = "DP-2" }`).
 */
class WorkspaceRule(val number: Int) {
    init {
        require(number in 1..99) { "workspaces are numbered 1 to 99, not $number" }
    }

    /** A name for bars and other programs ("web", "chat"); null: its number. */
    var name: String? = null

    /** Its home monitor (a connector, "DP-2"): it is always shown there while that one is plugged in. */
    var monitor: String? = null

    /** Its own layout (null: the desktop's). */
    var layout: dev.caulis.tiling.Layout? = null

    /** It always exists, even empty (bars always show it). */
    var persistent = false

    override fun toString() = "workspace $number" + listOfNotNull(name?.let { "\"$it\"" }, monitor?.let { "on $it" }, layout?.let { it.name }, "persistent".takeIf { persistent }).joinToString(", ", " (", ")").takeIf { it != " ()" }.orEmpty()
}

/**
 * How the mouse goes from one monitor to the next when they are not the same size
 * (see [Desktop.monitorEdges]).
 */
enum class MonitorEdges {
    /**
     * Where monitors touch, the pointer goes across; past the rest of an edge it stops (as
     * Windows and GNOME): next to a taller monitor, a shorter one is reached only along the
     * part they share.
     */
    TOUCHING,

    /**
     * Every edge leads to the whole edge beside it, whatever their sizes: the top corner to
     * the top corner, the middle to the middle, the bottom corner to the bottom corner.
     */
    PROPORTIONAL,
}
