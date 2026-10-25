package dev.caulis.wm.config

import dev.caulis.core.Animations
import dev.caulis.core.Background
import dev.caulis.core.Blur
import dev.caulis.core.Border
import dev.caulis.core.Curve
import dev.caulis.core.Effects
import dev.caulis.core.MonitorEdges
import dev.caulis.core.NoticePlacement
import dev.caulis.core.NoticeStyle
import dev.caulis.core.Paints
import dev.caulis.core.Shadow
import dev.caulis.core.TitleBars
import dev.caulis.core.WallpaperShader
import dev.caulis.core.WindowAnimation
import dev.caulis.core.WorkspaceAnimation
import dev.caulis.core.WorkspaceMode
import dev.caulis.core.input.KeyBindings
import dev.caulis.render.Color
import dev.caulis.render.Paint
import dev.caulis.tiling.BinaryTree
import dev.caulis.tiling.Canvas
import dev.caulis.tiling.CenteredMaster
import dev.caulis.tiling.Columns
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Gaps
import dev.caulis.tiling.Grid
import dev.caulis.tiling.Insertion
import dev.caulis.tiling.Layout
import dev.caulis.tiling.ManualTiling
import dev.caulis.tiling.MasterStack
import dev.caulis.tiling.MasterStrips
import dev.caulis.tiling.Monocle
import dev.caulis.tiling.Rows
import dev.caulis.tiling.ScrollingColumns
import dev.caulis.tiling.Side
import dev.caulis.tiling.Spiral
import dev.caulis.wm.Caulis
import dev.caulis.wm.Keys

/** The layouts a configuration file can name. */
object Layouts {
    val names = listOf(
        "floating", "dwindle", "spiral", "grid", "columns", "rows", "monocle", "master-stack", "centered-master",
        "master-strips", "tree", "tree-spiral", "manual", "scrolling-columns", "canvas",
    )

    /** The layout called [name], with [gaps] and the other settings that fit it. */
    fun byName(name: String, gaps: Gaps = Gaps(), ratio: Double? = null, side: Side = Side.LEFT): () -> Layout {
        val key = name.lowercase().replace('_', '-').replace(' ', '-')
        return when (key) {
            "floating" -> ({ Floating })
            "dwindle" -> ({ Dwindle(gaps) })
            "spiral" -> ({ Spiral(gaps) })
            "grid" -> ({ Grid(gaps) })
            "columns" -> ({ Columns(gaps) })
            "rows" -> ({ Rows(gaps) })
            "monocle" -> ({ Monocle(gaps) })
            "master-stack" -> ({ MasterStack(gaps, ratio ?: 0.55, side) })
            "centered-master" -> ({ CenteredMaster(gaps, ratio ?: 0.5) })
            "master-strips" -> ({ MasterStrips(gaps, ratio ?: 0.6) })
            "tree" -> ({ BinaryTree(gaps) })
            "tree-spiral" -> ({ BinaryTree(gaps, Insertion.SPIRAL, "Tree (spiral)") })
            "manual" -> ({ ManualTiling(gaps) })
            "scrolling-columns" -> ({ ScrollingColumns(gaps) })
            "canvas" -> ({ Canvas(gaps) })
            else -> throw IllegalArgumentException("\"$name\" is not a layout (there are: ${names.joinToString()})")
        }
    }
}

/**
 * Everything a configuration file (config.json, or what the Nix module writes) can say —
 * one description: read from JSON, checked, applied to a [Caulis], and turned into the Nix
 * module's typed options and a JSON Schema for editors.
 */
object Settings {

    // --- the pieces ---------------------------------------------------------------------------

    private val sizeInt = IntegerType(-10_000, 10_000)

    private val gaps = SectionType(
        listOf(
            Field("inner", IntegerType(0, Gaps.MAX), "space between tiled windows"),
            Field("outer", IntegerType(0, Gaps.MAX), "space between them and the screen's edges"),
            Field("top", IntegerType(0, Gaps.MAX), "space at the top edge (else outer)"),
            Field("right", IntegerType(0, Gaps.MAX), "space at the right edge (else outer)"),
            Field("bottom", IntegerType(0, Gaps.MAX), "space at the bottom edge (else outer)"),
            Field("left", IntegerType(0, Gaps.MAX), "space at the left edge (else outer)"),
        ),
        "gaps",
    )

    private val paint: SettingType = EitherType(
        ColorType,
        SectionType(
            listOf(
                Field("gradient", ListType(ColorType, 2, Paint.MAX_COLORS), "colours across the window"),
                Field("conic", ListType(ColorType, 2, Paint.MAX_COLORS), "colours around the window's middle"),
                Field("degrees", DecimalType(-3600.0, 3600.0), "the gradient's direction or start"),
                Field("turnsPerSecond", DecimalType(-10.0, 10.0), "how fast it turns"),
                Field("rainbow", DecimalType(0.5, 600.0), "a rainbow going round once every so many seconds"),
                Field("shader", FileType(), "a paint shader file (vec4 paint(vec2 at))"),
            ),
            "a paint: gradient, conic, rainbow or shader",
        ),
    )

    private val shadow = SectionType(
        listOf(
            Field("softness", IntegerType(1, 200), "how far it fades out"),
            Field("offsetX", IntegerType(-200, 200), "moved right"),
            Field("offsetY", IntegerType(-200, 200), "moved down"),
            Field("color", paint, "its colour or paint (a coloured one is a glow)"),
            Field("spread", IntegerType(-100, 200), "how much bigger than the window"),
        ),
        "a shadow",
    )

    private val device = SectionType(
        listOf(
            Field("enabled", FlagType, "false: not used at all"),
            Field("offWithMouse", FlagType, "touchpads: off while a mouse is plugged in"),
            Field("speed", DecimalType(-1.0, 1.0), "pointer speed, -1 to 1 (0: the device's normal)"),
            Field("accelProfile", ChoiceType(listOf("flat", "adaptive")), "flat: as fast as the hand; adaptive: faster when moved fast"),
            Field("naturalScroll", FlagType, "content follows the fingers"),
            Field("scrollFactor", DecimalType(0.1, 10.0), "scrolling goes this many times as far"),
            Field("scrollMethod", ChoiceType(listOf("none", "two-finger", "edge", "button")), "how it scrolls"),
            Field("scrollButton", TextType("a mouse button"), "with scrollMethod button: the button (\"MouseMiddle\")"),
            Field("leftHanded", FlagType, "buttons swapped"),
            Field("middleEmulation", FlagType, "left and right together are a middle click"),
            Field("tap", FlagType, "touchpads: a tap is a click"),
            Field("tapButtons", ChoiceType(listOf("left-right-middle", "left-middle-right")), "touchpads: what two and three fingers tap"),
            Field("tapDrag", FlagType, "touchpads: tap, touch again and move to drag"),
            Field("dragLock", FlagType, "touchpads: a drag goes on when the finger lifts for a moment"),
            Field("disableWhileTyping", FlagType, "touchpads: off while typing"),
            Field("clickMethod", ChoiceType(listOf("areas", "fingers")), "touchpads: a click by the area pressed or by the number of fingers"),
        ),
        "device settings",
    )

    private val windowAnimation = ChoiceType(listOf("pop", "fade", "scale", "slide-up", "genie", "fly", "none"))

    private val keys = Keys().let { usual ->
        SectionType(usual.all().keys.map { name -> Field(name, OffOrType(CombinationType(usual.isGroup(name))), "the $name key (false: off)") }, "keys")
    }

    private val rule = SectionType(
        listOf(
            Field("appId", TextType("an app id"), "windows with this app id (case does not matter)"),
            Field("appIdPattern", TextType("a regular expression"), "windows whose app id fits this"),
            Field("title", TextType(), "windows whose title contains this"),
            Field("titlePattern", TextType("a regular expression"), "windows whose title fits this"),
            Field("program", TextType("a program name"), "windows of this program (as ps shows it)"),
            Field("workspace", IntegerType(1, 99), "the workspace it opens on"),
            Field("monitor", TextType("a connector"), "the monitor it opens on"),
            Field("goToWorkspace", FlagType, "show its workspace when it opens"),
            Field("floating", FlagType, "float (true) or tile (false)"),
            Field("size", ListType(IntegerType(1, 10_000), 2, 2), "[width, height] when floating"),
            Field("position", ListType(sizeInt, 2, 2), "[x, y] when floating, from the monitor's free area"),
            Field("center", FlagType, "in the middle of its monitor when floating"),
            Field("maximized", FlagType, "starts maximized"),
            Field("fullscreen", FlagType, "starts fullscreen"),
            Field("focus", FlagType, "false: opens without taking the keyboard"),
            Field("titleBars", ChoiceType(listOf("program", "compositor", "none")), "who draws its title bar"),
            Field("opacity", DecimalType(0.0, 1.0), "how solid it is"),
            Field("margin", EitherType(IntegerType(-500, 500), ListType(IntegerType(-500, 500), 4, 4)), "room around it: one number, or [top, right, bottom, left]"),
        ),
        "a window rule",
    )

    private val layoutSection = SectionType(
        listOf(
            Field("name", ChoiceType(Layouts.names), "how windows are arranged"),
            Field("gaps", gaps, "space around and between windows"),
            Field("ratio", DecimalType(0.1, 0.9), "the main window's share (master layouts)"),
            Field("side", ChoiceType(listOf("left", "right", "top", "bottom")), "the main window's side (master-stack)"),
        ),
        "the layout",
    )

    private val workspaces = SectionType(
        listOf(
            Field("count", IntegerType(1, 99), "how many there are (1: no workspaces, one desktop)"),
            Field(
                "rules",
                ListType(
                    SectionType(
                        listOf(
                            Field("workspace", IntegerType(1, 99), "its number"),
                            Field("name", TextType("a name"), "its name for bars (\"web\")"),
                            Field("monitor", TextType("a connector"), "its home monitor (\"DP-2\")"),
                            Field("layout", layoutSection, "its own layout"),
                            Field("persistent", FlagType, "it always exists, even empty"),
                        ),
                        "a workspace rule",
                    ),
                ),
                "how single workspaces are",
            ),
        ),
        "workspaces",
    )

    /** The whole file. */
    val root = SectionType(
        listOf(
            Field("layout", layoutSection, "how windows are arranged"),
            Field("scale", DecimalType(0.5, 4.0), "how big everything is (1.0, 1.25, 1.5, 2.0, ...)"),
            Field(
                "workspaceMode",
                ChoiceType(listOf("per-monitor", "pinned", "separate", "spanning", "primary-only")),
                "shared numbers on any monitor, pinned to monitors, each monitor its own, one over all, or only on the primary monitor",
            ),
            Field("primaryMonitor", TextType("a connector"), "the primary monitor (\"DP-2\")"),
            Field("workspaces", workspaces, "how many workspaces, and how each is"),
            Field("monitorEdges", ChoiceType(listOf("touching", "proportional")), "how the mouse goes between monitors of different sizes"),
            Field(
                "monitors",
                MapType(
                    SectionType(
                        listOf(
                            Field("scale", DecimalType(0.5, 4.0), "its own scale"),
                            Field("position", ListType(sizeInt, 2, 2), "[x, y] on the desktop, in units"),
                            Field("mode", ListType(DecimalType(1.0, 20_000.0), 2, 3), "[width, height] or [width, height, hz]"),
                            Field("enabled", FlagType, "false: not used"),
                        ),
                        "a monitor",
                    ),
                    "connector",
                ),
                "settings per monitor, by connector (\"DP-2\", \"eDP-1\", ...)",
            ),
            Field(
                "keyboard",
                SectionType(
                    listOf(
                        Field("layouts", TextType("layouts"), "as for setxkbmap: \"us,ru\""),
                        Field("variants", TextType("variants"), "one per layout: \",phonetic\""),
                        Field("options", TextType("options"), "e.g. \"grp:alt_shift_toggle\""),
                        Field("model", TextType("a model"), "e.g. \"pc105\""),
                        Field("repeatRate", IntegerType(0, 100), "held keys repeat this many times a second (0: never)"),
                        Field("repeatDelay", IntegerType(100, 2000), "milliseconds before a held key repeats"),
                    ),
                    "the keyboard",
                ),
                "keyboard layouts and key repeat",
            ),
            Field(
                "input",
                SectionType(
                    listOf(
                        Field("focusFollowsMouse", FlagType, "the window under the pointer gets the keyboard"),
                        Field("pointers", device, "every mouse, trackball and touchpad"),
                        Field("touchpads", device, "touchpads (on top of pointers)"),
                        Field("devices", MapType(device, "device name"), "single devices by name (--devices lists them; * matches anything)"),
                    ),
                    "input",
                ),
                "mice, touchpads, focus",
            ),
            Field("lid", SectionType(listOf(Field("close", ActionType, "when the laptop's lid closes"), Field("open", ActionType, "when it opens")), "the lid"), "the laptop's lid"),
            Field("cursor", SectionType(listOf(Field("theme", TextType("a cursor theme"), "an installed cursor theme"), Field("size", IntegerType(8, 256), "its size")), "the cursor"), "the cursor"),
            Field("terminal", TextType("a command"), "the terminal the terminal key opens"),
            Field("lockCommand", TextType("a command"), "the screen locker"),
            Field("titleBars", ChoiceType(listOf("program", "compositor", "none")), "who draws title bars"),
            Field("keys", keys, "the usual keys, changed or turned off"),
            Field("bind", MapType(BindType, "key combination"), "your own keys: combination → action (\"exec firefox\", \"workspace 3\", ...) or { action, repeat, release, locked, description }"),
            Field("modes", MapType(MapType(BindType, "key combination"), "mode"), "key modes (\"resize\"): their own keys, on with the action \"mode resize\", off with \"mode reset\""),
            Field("rules", ListType(rule), "rules for some windows"),
            Field(
                "effects",
                SectionType(
                    listOf(
                        Field("preset", ChoiceType(listOf("none", "modern")), "start from no effects, or the modern look"),
                        Field("cornerRadius", IntegerType(0, 100), "how round the corners are"),
                        Field("border", OffOrType(SectionType(listOf(Field("width", IntegerType(1, 50), "its width"), Field("focused", paint, "with the focus"), Field("unfocused", paint, "without")), "a border")), "a frame around windows"),
                        Field("shadow", OffOrType(shadow), "the shadow (of the focused window)"),
                        Field("inactiveShadow", OffOrType(shadow), "the shadow of windows without the focus"),
                        Field("blur", OffOrType(SectionType(listOf(Field("size", IntegerType(1, 64), "how strong"), Field("passes", IntegerType(1, 6), "how smooth")), "blur")), "blur behind see-through windows"),
                        Field("opacity", DecimalType(0.0, 1.0), "how solid the focused window is"),
                        Field("inactiveOpacity", DecimalType(0.0, 1.0), "how solid the others are"),
                        Field("focusChangeMillis", IntegerType(0, 5000), "how long border and shadow change when the focus moves"),
                    ),
                    "effects",
                ),
                "round corners, borders, shadows, blur",
            ),
            Field(
                "animations",
                SectionType(
                    listOf(
                        Field("millis", IntegerType(0, 5000), "how long animations take (0: none at all)"),
                        Field("windowOpen", windowAnimation, "a window appearing"),
                        Field("windowClose", windowAnimation, "a window going away"),
                        Field("minimize", windowAnimation, "a window going into the dock"),
                        Field("restore", windowAnimation, "a window coming back out of the dock"),
                        Field("windowMove", ChoiceType(listOf("move", "none")), "windows gliding to new places"),
                        Field("workspaceSwitch", ChoiceType(listOf("slide", "slide-vertical", "fade", "none")), "another workspace shown"),
                        Field("curve", ChoiceType(listOf("ease-out", "ease-in-out", "linear", "overshoot")), "how animations move"),
                        Field("overviewMillis", IntegerType(0, 5000), "the overview zooming"),
                        Field("scaleChangeMillis", IntegerType(0, 5000), "the picture melting when the scale changes"),
                    ),
                    "animations",
                ),
                "animations",
            ),
            Field(
                "background",
                SectionType(
                    listOf(
                        Field("color", ColorType, "one colour"),
                        Field("gradient", ListType(ColorType, 2, 2), "[from, to]"),
                        Field("degrees", DecimalType(-360.0, 360.0), "the gradient's direction"),
                        Field("image", FileType(), "a picture (next to the configuration, ~/..., or /...)"),
                        Field("fit", ChoiceType(listOf("fill", "fit", "stretch", "center", "tile")), "how the picture covers the monitor"),
                        Field("behind", ColorType, "the colour around a picture that does not cover it"),
                        Field("span", FlagType, "one picture over all monitors"),
                        Field("shader", FileType("a file name, or \"aurora\""), "a wallpaper shader file (vec4 wallpaper(vec2 at))"),
                        Field("framesPerSecond", IntegerType(1, 240), "how often a moving shader is drawn"),
                        Field("changeMillis", IntegerType(0, 10_000), "how long one background melts into the next"),
                    ),
                    "the background",
                ),
                "what shows behind everything",
            ),
            Field(
                "notices",
                SectionType(
                    listOf(
                        Field("placement", ChoiceType(listOf("banner", "toast", "hidden")), "a strip, cards in a corner, or none"),
                        Field("bottom", FlagType, "a banner at the bottom"),
                        Field("room", ChoiceType(listOf("push-all", "push-windows", "cover")), "what makes way for a banner"),
                        Field("corner", ChoiceType(listOf("top-left", "top", "top-right", "bottom-left", "bottom", "bottom-right")), "where toasts go"),
                        Field("fontSize", IntegerType(6, 72), "their text size"),
                        Field("everyMonitor", FlagType, "on every monitor"),
                        Field("infoSeconds", DecimalType(0.0, 3600.0), "how long information stays (0: until clicked)"),
                        Field("warningSeconds", DecimalType(0.0, 3600.0), "how long warnings stay"),
                        Field("errorSeconds", DecimalType(0.0, 3600.0), "how long errors stay"),
                    ),
                    "notices",
                ),
                "the compositor's own messages",
            ),
            Field(
                "gestures",
                SectionType(
                    listOf(
                        Field("dragViewFingers", IntegerType(0, 5), "fingers that drag the view (0: off)"),
                        Field("overviewPinchFingers", IntegerType(0, 5), "fingers whose pinch opens the overview (0: off)"),
                        Field("workspaceSwipeFingers", IntegerType(0, 5), "fingers whose swipe changes the workspace (0: off)"),
                        Field("swipeDistance", DecimalType(10.0, 1000.0), "how far fingers move for a swipe"),
                    ),
                    "gestures",
                ),
                "touchpad gestures",
            ),
            Field("overviewFill", DecimalType(0.3, 1.0), "how much of the monitor the overview's windows take"),
            Field("resizeBorder", IntegerType(0, 50), "the edge to grab floating windows by"),
            Field("doubleClickMillis", IntegerType(50, 2000), "a double click on a title bar"),
            Field("hideCursorWhileTyping", FlagType, "hide the cursor while typing"),
            Field("hideCursorAfterSeconds", IntegerType(0, 3600), "hide the cursor when the mouse is still (0: never)"),
            Field("lockColor", ColorType, "behind the screen locker"),
            Field("startup", ListType(TextType("a command")), "programs started with the compositor"),
            Field("logLevel", IntegerType(0, 3), "how much the log says"),
        ),
        "the configuration",
    )

    // --- applying ----------------------------------------------------------------------------

    /**
     * What a file added to the lists of a [Caulis] (rules, keys, programs to start), so that a
     * new version replaces them instead of adding to them.
     */
    class Added(val rules: List<dev.caulis.wm.WindowRule>, val bindings: List<Caulis.Binding>, val startup: List<Caulis.() -> Unit>)

    /** Applies [values] (read by [root]) to [caulis]; what it adds to lists comes back. */
    @Suppress("UNCHECKED_CAST")
    fun apply(caulis: Caulis, values: Map<String, Any?>): Added {
        fun section(name: String) = values[name] as? Map<String, Any?>
        val rules = ArrayList<dev.caulis.wm.WindowRule>()
        val bindings = ArrayList<Caulis.Binding>()
        val startup = ArrayList<Caulis.() -> Unit>()

        section("layout")?.let { caulis.layout = layout(it) }
        (values["scale"] as Double?)?.let { caulis.scale = it }
        (values["workspaceMode"] as String?)?.let { caulis.workspaceMode = WorkspaceMode.valueOf(it.uppercase().replace('-', '_')) }
        (values["primaryMonitor"] as String?)?.let { caulis.primaryMonitor = it }
        section("workspaces")?.let { w ->
            (w["count"] as Int?)?.let { caulis.workspaceCount = it }
            (w["rules"] as List<Map<String, Any?>>?)?.forEach { r ->
                val number = r["workspace"] as Int? ?: return@forEach
                caulis.workspace(number) {
                    name = r["name"] as String?
                    monitor = r["monitor"] as String?
                    layout = (r["layout"] as Map<String, Any?>?)?.let(::layout)
                    persistent = r["persistent"] as Boolean? ?: false
                }
            }
        }
        (values["monitorEdges"] as String?)?.let { caulis.monitorEdges = if (it == "proportional") MonitorEdges.PROPORTIONAL else MonitorEdges.TOUCHING }
        (values["monitors"] as Map<String, Map<String, Any?>>?)?.forEach { (name, m) ->
            caulis.monitor(name) {
                (m["scale"] as Double?)?.let { scale = it }
                (m["position"] as List<Int>?)?.let { position(it[0], it[1]) }
                (m["mode"] as List<Double>?)?.let { mode(it[0].toInt(), it[1].toInt(), it.getOrNull(2)) }
                (m["enabled"] as Boolean?)?.let { enabled = it }
            }
        }
        section("keyboard")?.let { k ->
            (k["variants"] as String?)?.let { caulis.keyboardVariants = it }
            (k["model"] as String?)?.let { caulis.keyboardModel = it }
            (k["repeatRate"] as Int?)?.let { caulis.repeatRate = it }
            (k["repeatDelay"] as Int?)?.let { caulis.repeatDelay = it }
            (k["layouts"] as String?)?.let { caulis.keyboardLayouts = it }
            (k["options"] as String?)?.let { caulis.keyboardOptions = it }
        }
        section("cursor")?.let { c ->
            (c["theme"] as String?)?.let { caulis.cursorTheme = it }
            (c["size"] as Int?)?.let { caulis.cursorSize = it }
        }
        (values["terminal"] as String?)?.let { caulis.terminal = it }
        (values["lockCommand"] as String?)?.let { caulis.lockCommand = it }
        (values["titleBars"] as String?)?.let { caulis.titleBars = TitleBars.valueOf(it.uppercase()) }
        section("keys")?.let { k ->
            for ((name, value) in k) caulis.keys[name] = if (value === OffOrType.Off) null else value as String
        }
        (values["bind"] as Map<String, BindType.Bound>?)?.forEach { (combination, bound) ->
            caulis.bind(combination, bound.options, bound.action)
            bindings += caulis.bindings.last()
        }
        (values["modes"] as Map<String, Map<String, BindType.Bound>>?)?.forEach { (mode, keys) ->
            caulis.mode(mode) { for ((combination, bound) in keys) bind(combination, bound.options, bound.action) }
        }
        section("input")?.let { i ->
            (i["focusFollowsMouse"] as Boolean?)?.let { caulis.focusFollowsMouse = it }
            (i["pointers"] as Map<String, Any?>?)?.let { device(it, caulis.input.pointers) }
            (i["touchpads"] as Map<String, Any?>?)?.let { device(it, caulis.input.touchpads) }
            (i["devices"] as Map<String, Map<String, Any?>>?)?.forEach { (name, d) ->
                device(d, caulis.input.devices.getOrPut(name) { dev.caulis.core.input.DeviceSettings() })
            }
        }
        section("lid")?.let { l ->
            val close = l["close"] as (Caulis.() -> Unit)?
            val open = l["open"] as (Caulis.() -> Unit)?
            caulis.onLid { closed -> (if (closed) close else open)?.invoke(this) }
        }
        (values["rules"] as List<Map<String, Any?>>?)?.forEach { r -> rules += rule(caulis, r) }
        section("effects")?.let { caulis.effects = effects(caulis, it) }
        section("animations")?.let { a ->
            (a["millis"] as Int?)?.let { caulis.animationMillis = it.toLong() }
            val base = caulis.animations
            caulis.animations = base.copy(
                windowOpen = (a["windowOpen"] as String?)?.let(::windowAnimation) ?: base.windowOpen,
                windowClose = (a["windowClose"] as String?)?.let(::windowAnimation) ?: base.windowClose,
                minimize = (a["minimize"] as String?)?.let(::windowAnimation) ?: base.minimize,
                restore = (a["restore"] as String?)?.let(::windowAnimation) ?: base.restore,
                windowMove = (a["windowMove"] as String?)?.let { if (it == "none") WindowAnimation.NONE else WindowAnimation.move() } ?: base.windowMove,
                workspaceSwitch = when (a["workspaceSwitch"] as String?) {
                    "slide" -> WorkspaceAnimation.SLIDE
                    "slide-vertical" -> WorkspaceAnimation.SLIDE_VERTICAL
                    "fade" -> WorkspaceAnimation.FADE
                    "none" -> WorkspaceAnimation.NONE
                    else -> base.workspaceSwitch
                },
                curve = when (a["curve"] as String?) {
                    "ease-out" -> Curve.EASE_OUT
                    "ease-in-out" -> Curve.EASE_IN_OUT
                    "linear" -> Curve.LINEAR
                    "overshoot" -> Curve.OVERSHOOT
                    else -> base.curve
                },
                overviewMillis = (a["overviewMillis"] as Int?)?.toLong() ?: base.overviewMillis,
                scaleChangeMillis = (a["scaleChangeMillis"] as Int?)?.toLong() ?: base.scaleChangeMillis,
            )
        }
        section("background")?.let { b ->
            (b["changeMillis"] as Int?)?.let { caulis.backgroundChangeMillis = it.toLong() }
            background(caulis, b)?.let { caulis.background = it }
        }
        section("notices")?.let { n -> caulis.noticeStyle = notices(caulis.noticeStyle, n) }
        section("gestures")?.let { g ->
            (g["dragViewFingers"] as Int?)?.let { caulis.dragViewFingers = it }
            (g["overviewPinchFingers"] as Int?)?.let { caulis.overviewPinchFingers = it }
            (g["workspaceSwipeFingers"] as Int?)?.let { caulis.workspaceSwipeFingers = it }
            (g["swipeDistance"] as Double?)?.let { caulis.swipeDistance = it }
        }
        (values["overviewFill"] as Double?)?.let { caulis.overviewFill = it }
        (values["resizeBorder"] as Int?)?.let { caulis.resizeBorder = it }
        (values["doubleClickMillis"] as Int?)?.let { caulis.doubleClickMillis = it.toLong() }
        (values["hideCursorWhileTyping"] as Boolean?)?.let { caulis.hideCursorWhileTyping = it }
        (values["hideCursorAfterSeconds"] as Int?)?.let { caulis.hideCursorAfterSeconds = it }
        (values["lockColor"] as Color?)?.let { caulis.lockColor = it }
        (values["startup"] as List<String>?)?.forEach { command ->
            val action: Caulis.() -> Unit = { launch(command) }
            caulis.onStart(action)
            startup += action
        }
        (values["logLevel"] as Int?)?.let { caulis.logLevel = it }
        return Added(rules, bindings, startup)
    }

    private fun device(d: Map<String, Any?>, into: dev.caulis.core.input.DeviceSettings) {
        (d["enabled"] as Boolean?)?.let { into.enabled = it }
        (d["offWithMouse"] as Boolean?)?.let { into.offWithMouse = it }
        (d["speed"] as Double?)?.let { into.speed = it }
        (d["accelProfile"] as String?)?.let { into.accelProfile = dev.caulis.core.input.AccelProfile.valueOf(it.uppercase()) }
        (d["naturalScroll"] as Boolean?)?.let { into.naturalScroll = it }
        (d["scrollFactor"] as Double?)?.let { into.scrollFactor = it }
        (d["scrollMethod"] as String?)?.let { into.scrollMethod = dev.caulis.core.input.ScrollMethod.valueOf(it.uppercase().replace('-', '_')) }
        (d["scrollButton"] as String?)?.let { into.scrollButton = KeyBindings.keyCode(it) ?: Regex("(?i)mouse:(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toInt() }
        (d["leftHanded"] as Boolean?)?.let { into.leftHanded = it }
        (d["middleEmulation"] as Boolean?)?.let { into.middleEmulation = it }
        (d["tap"] as Boolean?)?.let { into.tap = it }
        (d["tapButtons"] as String?)?.let { into.tapButtons = dev.caulis.core.input.TapButtons.valueOf(it.uppercase().replace('-', '_')) }
        (d["tapDrag"] as Boolean?)?.let { into.tapDrag = it }
        (d["dragLock"] as Boolean?)?.let { into.dragLock = it }
        (d["disableWhileTyping"] as Boolean?)?.let { into.disableWhileTyping = it }
        (d["clickMethod"] as String?)?.let { into.clickMethod = dev.caulis.core.input.ClickMethod.valueOf(it.uppercase()) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun layout(l: Map<String, Any?>): Layout {
        val g = l["gaps"] as? Map<String, Any?>
        val outer = g?.get("outer") as Int? ?: 8
        val gaps = Gaps(
            inner = g?.get("inner") as Int? ?: 8, outer = outer,
            top = g?.get("top") as Int? ?: outer, right = g?.get("right") as Int? ?: outer,
            bottom = g?.get("bottom") as Int? ?: outer, left = g?.get("left") as Int? ?: outer,
        )
        val side = (l["side"] as String?)?.let { Side.valueOf(it.uppercase()) } ?: Side.LEFT
        return Layouts.byName(l["name"] as String? ?: "dwindle", gaps, l["ratio"] as Double?, side)()
    }

    private fun windowAnimation(name: String): WindowAnimation = when (name) {
        "pop" -> WindowAnimation.pop()
        "fade" -> WindowAnimation.fade()
        "scale" -> WindowAnimation.scale()
        "slide-up" -> WindowAnimation.fade() + WindowAnimation.slide()
        "genie" -> WindowAnimation.genie()
        "fly" -> WindowAnimation.flyTo()
        else -> WindowAnimation.NONE
    }

    @Suppress("UNCHECKED_CAST")
    private fun rule(caulis: Caulis, r: Map<String, Any?>): dev.caulis.wm.WindowRule {
        caulis.rule {
            (r["appId"] as String?)?.let { appId(it) }
            (r["appIdPattern"] as String?)?.let { appId(Regex(it)) }
            (r["title"] as String?)?.let { title(it) }
            (r["titlePattern"] as String?)?.let { title(Regex(it)) }
            (r["program"] as String?)?.let { program(it) }
            (r["workspace"] as Int?)?.let { workspace = it }
            (r["monitor"] as String?)?.let { monitor = it }
            (r["goToWorkspace"] as Boolean?)?.let { goToWorkspace = it }
            (r["floating"] as Boolean?)?.let { floating = it }
            (r["size"] as List<Int>?)?.let { size(it[0], it[1]) }
            (r["position"] as List<Int>?)?.let { position(it[0], it[1]) }
            if (r["center"] == true) center()
            (r["maximized"] as Boolean?)?.let { maximized = it }
            (r["fullscreen"] as Boolean?)?.let { fullscreen = it }
            (r["focus"] as Boolean?)?.let { focus = it }
            (r["titleBars"] as String?)?.let { titleBars = TitleBars.valueOf(it.uppercase()) }
            (r["opacity"] as Double?)?.let { opacity = it.toFloat() }
            when (val m = r["margin"]) {
                is Int -> margin(m)
                is List<*> -> (m as List<Int>).let { margin(it[0], it[1], it[2], it[3]) }
            }
        }
        return caulis.rules.last()
    }

    @Suppress("UNCHECKED_CAST")
    private fun effects(caulis: Caulis, e: Map<String, Any?>): Effects {
        var fx = when (e["preset"] as String?) {
            "modern" -> Effects.MODERN
            "none" -> Effects.NONE
            else -> caulis.effects
        }
        (e["cornerRadius"] as Int?)?.let { fx = fx.copy(cornerRadius = it) }
        when (val b = e["border"]) {
            OffOrType.Off -> fx = fx.copy(border = null)
            is Map<*, *> -> {
                val base = fx.border ?: Border()
                fx = fx.copy(
                    border = Border(
                        width = b["width"] as Int? ?: base.width,
                        focused = b["focused"]?.let { paint(caulis, it) } ?: base.focused,
                        unfocused = b["unfocused"]?.let { paint(caulis, it) } ?: base.unfocused,
                    ),
                )
            }
        }
        fun shadowOf(s: Any?, base: Shadow?): Shadow? = when (s) {
            OffOrType.Off -> null
            is Map<*, *> -> {
                val from = base ?: Shadow()
                Shadow(
                    softness = s["softness"] as Int? ?: from.softness,
                    offsetX = s["offsetX"] as Int? ?: from.offsetX,
                    offsetY = s["offsetY"] as Int? ?: from.offsetY,
                    color = s["color"]?.let { paint(caulis, it) } ?: from.color,
                    spread = s["spread"] as Int? ?: from.spread,
                )
            }
            else -> base
        }
        if ("shadow" in e) fx = fx.copy(shadow = shadowOf(e["shadow"], fx.shadow))
        if ("inactiveShadow" in e) fx = fx.copy(inactiveShadow = shadowOf(e["inactiveShadow"], fx.inactiveShadow) ?: Shadow.NONE)
        when (val b = e["blur"]) {
            OffOrType.Off -> fx = fx.copy(blur = null)
            is Map<*, *> -> fx = fx.copy(blur = Blur(b["size"] as Int? ?: 4, b["passes"] as Int? ?: 2))
        }
        (e["opacity"] as Double?)?.let { fx = fx.copy(opacity = it.toFloat(), inactiveOpacity = (e["inactiveOpacity"] as Double?)?.toFloat() ?: it.toFloat()) }
        (e["inactiveOpacity"] as Double?)?.let { fx = fx.copy(inactiveOpacity = it.toFloat()) }
        (e["focusChangeMillis"] as Int?)?.let { fx = fx.copy(focusChangeMillis = it.toLong()) }
        return fx
    }

    @Suppress("UNCHECKED_CAST")
    private fun paint(caulis: Caulis, value: Any): Paint = when (value) {
        is Color -> Paint.solid(value)
        is Map<*, *> -> {
            val degrees = value["degrees"] as Double?
            val turns = value["turnsPerSecond"] as Double? ?: 0.0
            when {
                value["gradient"] != null -> Paint.Gradient(value["gradient"] as List<Color>, degrees ?: 45.0, conic = false, turnsPerSecond = turns)
                value["conic"] != null -> Paint.Gradient(value["conic"] as List<Color>, degrees ?: 0.0, conic = true, turnsPerSecond = turns)
                value["rainbow"] != null -> Paint.rainbow(value["rainbow"] as Double)
                value["shader"] != null -> Paints.file(caulis.file(value["shader"] as String))
                else -> Paint.solid(Color(1f, 1f, 1f))
            }
        }
        else -> Paint.solid(Color(1f, 1f, 1f))
    }

    @Suppress("UNCHECKED_CAST")
    private fun background(caulis: Caulis, b: Map<String, Any?>): Background? {
        (b["shader"] as String?)?.let { name ->
            val fps = b["framesPerSecond"] as Int? ?: 30
            if (name == "aurora") return Background.shader(WallpaperShader.AURORA)
            return Background.shader(WallpaperShader.file(caulis.file(name), (b["image"] as String?)?.let(caulis::file), fps))
        }
        (b["image"] as String?)?.let { name ->
            val fit = (b["fit"] as String?)?.let { Background.Fit.valueOf(it.uppercase()) } ?: Background.Fit.FILL
            return Background.image(caulis.file(name), fit, b["behind"] as Color? ?: Color(0f, 0f, 0f), b["span"] as Boolean? ?: false)
        }
        (b["gradient"] as List<Color>?)?.let { return Background.gradient(it[0], it[1], b["degrees"] as Double? ?: 90.0) }
        (b["color"] as Color?)?.let { return Background.color(it) }
        return null
    }

    private fun notices(base: NoticeStyle, n: Map<String, Any?>): NoticeStyle {
        val placement = when (n["placement"] as String?) {
            "banner" -> NoticePlacement.Banner(
                bottom = n["bottom"] as Boolean? ?: false,
                room = (n["room"] as String?)?.let { NoticePlacement.Banner.Room.valueOf(it.uppercase().replace('-', '_')) } ?: NoticePlacement.Banner.Room.PUSH_ALL,
            )
            "toast" -> NoticePlacement.Toast((n["corner"] as String?)?.let { NoticePlacement.Toast.Corner.valueOf(it.uppercase().replace('-', '_')) } ?: NoticePlacement.Toast.Corner.BOTTOM_RIGHT)
            "hidden" -> NoticePlacement.Hidden
            else -> base.placement
        }
        return base.copy(
            placement = placement,
            fontSize = n["fontSize"] as Int? ?: base.fontSize,
            everyMonitor = n["everyMonitor"] as Boolean? ?: base.everyMonitor,
            infoSeconds = n["infoSeconds"] as Double? ?: base.infoSeconds,
            warningSeconds = n["warningSeconds"] as Double? ?: base.warningSeconds,
            errorSeconds = n["errorSeconds"] as Double? ?: base.errorSeconds,
        )
    }
}

/** A key's action as text (see [Actions]), checked when read. */
object ActionType : SettingType() {
    override val words get() = "an action (\"close\", \"exec firefox\", \"workspace 3\", ...)"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val text = (json as? JsonValue.Str)?.value ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
        // The name of the field is the key combination: checked too.
        val combination = path.substringAfterLast(".\"", "").removeSuffix("\"")
        if (combination.isNotEmpty()) {
            try {
                KeyBindings.parse(combination)
            } catch (e: IllegalArgumentException) {
                problems.add(path, json, e.message ?: "not a key combination")
                return null
            }
        }
        return try {
            Actions.parse(text)
        } catch (e: IllegalArgumentException) {
            problems.add(path, json, e.message ?: "not an action")
            null
        }
    }

    override fun nix() = "lib.types.str"
    override fun jsonSchema() = """{"type":"string","minLength":1}"""
}

/**
 * One of your keys in a file: an action ("exec firefox"), or one with how it behaves:
 * `{ "action": "exec wpctl ...", "repeat": true, "locked": true, "description": "louder" }`.
 * The combination (the name it is given under) is checked too.
 */
object BindType : SettingType() {
    /** What a key does, and how it behaves. */
    class Bound(val action: Caulis.() -> Unit, val options: dev.caulis.wm.BindOptions)

    private val details = SectionType(
        listOf(
            Field("action", TextType("an action"), "what it does (\"exec firefox\", \"workspace 3\", ...)"),
            Field("repeat", FlagType, "repeats while held"),
            Field("release", FlagType, "runs when the key is let go"),
            Field("locked", FlagType, "works on a locked screen too"),
            Field("description", TextType(), "what it does, in words"),
        ),
        "a key",
    )

    override val words get() = "an action (\"exec firefox\"), or { action, repeat, release, locked, description }"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val combination = path.substringAfterLast(".\"", "").removeSuffix("\"")
        if (combination.isNotEmpty()) {
            try {
                KeyBindings.parseAllowingTap(combination)
            } catch (e: IllegalArgumentException) {
                problems.add(path, json, e.message ?: "not a key combination")
                return null
            }
        }
        val (text, options) = when (json) {
            is JsonValue.Str -> json.value to dev.caulis.wm.BindOptions()
            is JsonValue.Obj -> {
                @Suppress("UNCHECKED_CAST")
                val m = details.read(json, path, problems) as Map<String, Any?>? ?: return null
                val action = m["action"] as String? ?: return problems.add(path, json, "it needs an \"action\"").let { null }
                action to dev.caulis.wm.BindOptions(
                    repeat = m["repeat"] == true,
                    release = m["release"] == true,
                    whileLocked = m["locked"] == true,
                    description = m["description"] as String?,
                )
            }
            else -> return problems.add(path, json, "$words, not ${json.kind}").let { null }
        }
        return try {
            Bound(Actions.parse(text), options)
        } catch (e: IllegalArgumentException) {
            problems.add(path, json, e.message ?: "not an action")
            null
        }
    }

    override fun nix() = "(lib.types.either lib.types.str ${details.nix().replace("\n", "\n  ")})"
    override fun jsonSchema() = """{"anyOf":[{"type":"string","minLength":1},${details.jsonSchema()}]}"""
}

/** A key combination ("Super+Shift+Q"), or only modifiers ("Super+Shift") for a group of keys. */
class CombinationType(private val modifiersOnly: Boolean) : SettingType() {
    override val words get() = if (modifiersOnly) "modifiers (\"Super\", \"Super+Shift\")" else "a key combination (\"Super+Q\")"

    override fun read(json: JsonValue, path: String, problems: Problems): Any? {
        val text = (json as? JsonValue.Str)?.value ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
        return try {
            if (modifiersOnly) require(KeyBindings.modifiers(text).isNotEmpty()) { "\"$text\" has no modifier" } else KeyBindings.parse(text)
            text
        } catch (e: IllegalArgumentException) {
            problems.add(path, json, e.message ?: "not $words")
            null
        }
    }

    override fun nix() = "lib.types.str"
    override fun jsonSchema() = """{"type":"string","minLength":1}"""
}
