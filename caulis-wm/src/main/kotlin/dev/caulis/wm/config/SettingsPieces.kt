package dev.caulis.wm.config

import dev.caulis.render.Paint
import dev.caulis.tiling.Gaps
import dev.caulis.wm.Keys

/**
 * The building blocks of the settings: the types of sections used in more than one place.
 */
internal object SettingsPieces {

    internal val sizeInt = IntegerType(-10_000, 10_000)

    // What a plugin's settings may hold: anything JSON can say; it reads what it knows.
    private object AnyType : SettingType() {
        override val words get() = "any value"

        override fun read(json: JsonValue, path: String, problems: Problems): Any? = plain(json)

        private fun plain(json: JsonValue): Any? = when (json) {
            is JsonValue.Obj -> json.fields.mapValues { plain(it.value) }
            is JsonValue.Arr -> json.items.map { plain(it) }
            is JsonValue.Str -> json.value
            is JsonValue.Num -> json.value
            is JsonValue.Bool -> json.value
            else -> null
        }

        override fun nix() = "lib.types.anything"
        override fun jsonSchema() = "{}"
    }

    internal val pluginsField = Field(
        "plugins",
        SectionType(
            listOf(
                Field("enabled", FlagType, "false: no plugin runs and no folder is followed (restart; default true)"),
                Field("directories", ListType(FileType("a folder")), "the folders plugins are in (restart; default: plugins in the configuration's folder)"),
                Field("disabled", ListType(TextType("a plugin's id")), "plugins that stay off"),
                Field("watch", FlagType, "a jar replaced is hot-reloaded, a Lua file reloads on save (restart; default true)"),
                Field("settleMillis", IntegerType(0, 60_000), "a changed file must be quiet this long before it is read (default 300)"),
                Field("failuresBeforeDisable", IntegerType(1, 1000), "how many failures before a plugin is disabled (default 1)"),
                Field("hangReportMillis", IntegerType(0, 600_000), "a call into a plugin longer than this is reported, with its stack; 0: never (restart; default 2000)"),
                Field("hangInterruptMillis", IntegerType(0, 600_000), "a call this long has its thread interrupted; 0: never (restart; default 5000)"),
                Field("hangQuarantineMillis", IntegerType(0, 600_000), "a call this long leaves the plugin off at the next start; 0: never (restart; default 10000)"),
                Field("disableAfterHang", FlagType, "a plugin that held up the desktop is disabled once its call returns (default true)"),
                Field("leakCheckMillis", IntegerType(0, 600_000), "after an unload: when to look whether the plugin's classes went from memory; 0: never (default 30000)"),
                Field("maxLeakedGenerations", IntegerType(1, 1000), "with this many left in memory, hot reloads wait for a restart (default 8)"),
                Field("allowUnsafePermissions", FlagType, "run also what others can write to (default false: that would run their code as you)"),
                Field("stateFile", FileType("a file"), "where what `plugin disable` and a hang said is kept (default: plugins.properties in the state folder)"),
                Field("settings", MapType(MapType(AnyType, "setting"), "plugin id"), "what each plugin is told, by its id"),
                Field("allow", MapType(ListType(ChoiceType(listOf("exec"))), "plugin id"), "what Lua plugins may do beyond their sandbox, by id (\"exec\": start programs)"),
            ),
            "plugins",
        ),
        "plugins: where they are, and how much they are forgiven",
    )

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

    // A colour or a gradient as text: "#89b4fa", or Hyprland's "rgba(33ccffee) rgba(00ff99ee) 45deg".
    private object PaintText : SettingType() {
        override val words get() = "a colour (\"#rrggbb[aa]\") or Hyprland's colours and angle (\"rgba(33ccffee) rgba(00ff99ee) 45deg\")"

        override fun read(json: JsonValue, path: String, problems: Problems): Any? {
            val text = (json as? JsonValue.Str)?.value ?: return problems.add(path, json, "$words, not ${json.kind}").let { null }
            return try {
                Paint.parse(text)
            } catch (e: IllegalArgumentException) {
                problems.add(path, json, e.message ?: "\"$text\" is not $words")
                null
            }
        }

        override fun nix() = "lib.types.str"
        override fun jsonSchema() = """{"type":"string","minLength":1}"""
    }

    internal val paint: SettingType = EitherType(
        PaintText,
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

    internal val shadow = SectionType(
        listOf(
            Field("softness", IntegerType(1, 200), "how far it fades out"),
            Field("offsetX", IntegerType(-200, 200), "moved right"),
            Field("offsetY", IntegerType(-200, 200), "moved down"),
            Field("color", paint, "its colour or paint (a coloured one is a glow)"),
            Field("spread", IntegerType(-100, 200), "how much bigger than the window"),
        ),
        "a shadow",
    )

    private val pointerFields = listOf(
            Field("enabled", FlagType, "false: not used at all"),
            Field("offWithMouse", FlagType, "touchpads: off while a mouse is plugged in"),
            Field("speed", DecimalType(-1.0, 1.0), "pointer speed, -1 to 1 (0: the device's normal)"),
            Field("accelProfile", ChoiceType(listOf("flat", "adaptive")), "flat: as fast as the hand; adaptive: faster when moved fast"),
            Field("naturalScroll", FlagType, "content follows the fingers"),
            Field("scrollFactor", DecimalType(0.1, 10.0), "scrolling goes this many times as far"),
            Field("scrollMethod", ChoiceType(listOf("none", "two-finger", "edge", "button")), "how it scrolls"),
            Field("scrollButton", TextType("a mouse button"), "with scrollMethod button: the button (\"MouseMiddle\")"),
            Field("leftHanded", FlagType, "buttons swapped; tablets: turned around"),
            Field("middleEmulation", FlagType, "left and right together are a middle click"),
            Field("tap", FlagType, "touchpads: a tap is a click"),
            Field("tapButtons", ChoiceType(listOf("left-right-middle", "left-middle-right")), "touchpads: what two and three fingers tap"),
            Field("tapDrag", FlagType, "touchpads: tap, touch again and move to drag"),
            Field("dragLock", FlagType, "touchpads: a drag goes on when the finger lifts for a moment"),
            Field("disableWhileTyping", FlagType, "touchpads: off while typing"),
            Field("clickMethod", ChoiceType(listOf("areas", "fingers")), "touchpads: a click by the area pressed or by the number of fingers"),
    )

    private val tabletFields = listOf(
        Field("monitor", TextType("a monitor's name"), "tablets: the monitor it covers (else a pen screen its laptop's, others all monitors as one)"),
        Field("keepAspect", FlagType, "tablets: keep the monitor's shape, a strip of the tablet unused (default true)"),
        Field(
            "area",
            SectionType(
                listOf(
                    Field("x", DecimalType(0.0, 1.0), "where it starts across, 0 to 1 of the tablet"),
                    Field("y", DecimalType(0.0, 1.0), "where it starts down, 0 to 1 of the tablet"),
                    Field("width", DecimalType(0.01, 1.0), "how wide, 0 to 1 of the tablet (else to its edge)"),
                    Field("height", DecimalType(0.01, 1.0), "how tall, 0 to 1 of the tablet (else to its edge)"),
                ),
                "a part of the tablet",
            ),
            "tablets: only this part of the tablet is used",
        ),
        Field("pointerOnly", FlagType, "tablets: the pen is only ever the pointer (no pressure or tilt for programs)"),
        Field("tipPressure", DecimalType(0.01, 1.0), "tablets: how hard the pen presses to touch, 0.01 to 1 (else libinput decides)"),
        Field("lowerButton", TextType("a mouse button"), "tablets: the pen's lower button as a mouse button (\"MouseRight\", \"none\")"),
        Field("upperButton", TextType("a mouse button"), "tablets: the pen's upper button as a mouse button (\"MouseMiddle\", \"none\")"),
        Field("thirdButton", TextType("a mouse button"), "tablets: a third button of the pen as a mouse button (\"MouseSide\", \"none\")"),
    )

    // Mice and touchpads; tablets; and single devices by name, which may be either.
    internal val device = SectionType(pointerFields, "device settings")

    internal val tablet = SectionType(pointerFields.filter { it.name == "enabled" || it.name == "leftHanded" } + tabletFields, "tablet settings")

    internal val anyDevice = SectionType(pointerFields + tabletFields, "device settings")

    private val windowStyle = ChoiceType(listOf("pop", "fade", "scale", "slide-up", "genie", "fly", "none"))

    // A curve: a ready one, or one of "curves" by its name.
    internal val curveName = TextType("a curve (ease-out, ease-in-out, linear, overshoot, or one of curves)")

    // An animation: its style alone, or { style, millis, curve }.
    internal fun animationType(styles: ChoiceType) = EitherType(
        styles,
        SectionType(
            listOf(
                Field("style", styles, "how it looks"),
                Field("millis", IntegerType(0, 5000), "how long it takes (else animations.millis, or its spring's own time)"),
                Field("curve", curveName, "how it moves"),
            ),
            "an animation",
        ),
    )

    internal val windowAnimation = animationType(windowStyle)

    // One named animation's settings (Hyprland's `animation = NAME, ONOFF, SPEED, CURVE, STYLE`):
    // true or false, its look alone, or { enabled, millis or speed, curve, style }.
    internal fun namedAnimation(node: dev.caulis.core.AnimationNode) = EitherType(
        FlagType,
        EitherType(
            TextType("a look (${node.styles.joinToString()})"),
            SectionType(
                listOf(
                    Field("enabled", FlagType, "on or off (else as the one above it, in the end animations.enabled)"),
                    Field("millis", IntegerType(0, 10_000), "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"),
                    Field("speed", DecimalType(0.0, 100.0), "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"),
                    Field("curve", curveName, "how it moves"),
                    Field("style", TextType("a look"), "its look" + if (node.styles.isEmpty()) " (it has one only)" else ": ${node.styles.joinToString()}"),
                ),
                "an animation's settings",
            ),
        ),
    )

    internal val curve = SectionType(
        listOf(
            Field("bezier", ListType(DecimalType(-2.0, 3.0), 4, 4), "[x1, y1, x2, y2] as Hyprland's bezier (CSS cubic-bezier)"),
            Field("spring", SectionType(listOf(
                Field("stiffness", DecimalType(1.0, 5000.0), "how strongly it pulls"),
                Field("damping", DecimalType(0.1, 500.0), "how much it is held back (less: it bounces)"),
                Field("mass", DecimalType(0.01, 100.0), "how heavy"),
            ), "a spring"), "a spring: an animation takes as long as it needs to rest"),
        ),
        "a curve",
    )

    internal val keys = Keys().let { usual ->
        SectionType(
            usual.all().keys.map { name -> Field(name, OffOrType(CombinationType(usual.isGroup(name))), "the $name key (false: off)") } +
                Field("quitWhileLocked", FlagType, "the quit key works on a locked screen too (default false: it would get round the lock)") +
                Field("lockWhileInhibited", FlagType, "the lock key locks while a window keeps the keys (shortcuts inhibited; default true)"),
            "keys",
        )
    }

    // Which windows a rule is for (also inside "not" and "anyOf").
    private val matchFields = listOf(
        Field("appId", TextType("an app id"), "windows with this app id (case does not matter)"),
        Field("appIdPattern", RegexType, "windows whose app id fits this"),
        Field("title", TextType(), "windows whose title contains this"),
        Field("titlePattern", RegexType, "windows whose title fits this"),
        Field("program", TextType("a program name"), "windows of this program (as ps shows it)"),
        Field("initialAppId", TextType("an app id"), "windows whose app id was this when they opened"),
        Field("initialTitle", TextType(), "windows whose title contained this when they opened"),
        Field("hasTag", TextType("a tag"), "windows with this tag (from an earlier rule's tags, or the action tag)"),
        Field("isFloating", FlagType, "while it floats (true) or is tiled (false)"),
        Field("isFullscreen", FlagType, "while it is fullscreen (or not)"),
        Field("isMaximized", FlagType, "while it is maximized (or not)"),
        Field("isFocused", FlagType, "while it has the keyboard (or not)"),
        Field("isPinned", FlagType, "while it is pinned (or not)"),
        Field("isXwayland", FlagType, "X11 programs (true) or Wayland ones (false)"),
        Field("onWorkspace", IntegerType(1, 99), "while it is on this workspace"),
    )

    private val match = SectionType(matchFields, "which windows")

    internal val blurSection = SectionType(
        listOf(
            Field("size", IntegerType(1, 64), "how strong"),
            Field("passes", IntegerType(1, 6), "how smooth"),
            Field("tone", ChoiceType(listOf("none", "hyprland")), "start from no toning, or Hyprland's"),
            Field("noise", DecimalType(0.0, 1.0), "grain (a little hides banding)"),
            Field("contrast", DecimalType(0.0, 2.0), "1: as it is"),
            Field("brightness", DecimalType(0.0, 2.0), "1: as it is"),
            Field("vibrancy", DecimalType(0.0, 1.0), "stronger colours (0: as they are)"),
        ),
        "blur",
    )

    internal val panelRule = SectionType(
        listOf(
            Field("namespace", TextType("a panel's name"), "panels that call themselves this (waybar, fuzzel, ...)"),
            Field("namespacePattern", RegexType, "panels whose name fits this"),
            Field("layer", ChoiceType(listOf("background", "bottom", "top", "overlay")), "panels on this layer"),
            Field("blur", FlagType, "frosted glass behind it"),
            Field("cornerRadius", IntegerType(0, 200), "how round its corners are"),
            Field("shadow", FlagType, "a shadow under it"),
            Field("opacity", DecimalType(0.0, 1.0), "how solid it is"),
            Field("dimAround", DecimalType(0.0, 1.0), "everything behind it this much darker"),
            Field("hiddenFromCapture", FlagType, "screenshots and screen sharing show a black box in its place"),
        ),
        "a panel rule",
    )

    internal val rule = SectionType(
        matchFields + listOf(
            Field("unless", match, "windows that do NOT fit this"),
            Field("anyOf", ListType(match, 1), "windows that fit at least one of these"),
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
            Field("pinned", FlagType, "opens pinned: floating, on every workspace of its monitor"),
            Field("inactiveOpacity", DecimalType(0.0, 1.0), "how solid it is while another window has the keyboard"),
            Field("cornerRadius", IntegerType(0, 200), "how round its corners are, in pixels"),
            Field("border", FlagType, "false: no border; true: the usual one"),
            Field("borderWidth", IntegerType(1, 50), "its border's width in pixels"),
            Field("borderColor", EitherType(ColorType, ListType(ColorType, 2, 2)), "its border's colour, or [with the keyboard, without]"),
            Field(
                "borderPaint",
                SectionType(listOf(Field("focused", paint, "with the keyboard"), Field("unfocused", paint, "without"), Field("urgent", paint, "while it wants attention")), "its border's paints"),
                "its border's paints: gradients, shaders (focused, unfocused, urgent)",
            ),
            Field("specialWorkspace", SpecialWorkspaceType, "the special workspace it opens in, unseen (unless goToWorkspace)"),
            Field("shadow", FlagType, "false: no shadow; true: the usual one"),
            Field("blur", FlagType, "false: nothing blurred behind it; true: frosted glass"),
            Field("dimInactive", DecimalType(0.0, 1.0), "how much darker it is without the keyboard (0: never)"),
            Field("minSize", ListType(IntegerType(1, 10_000), 2, 2), "[width, height] it is at least while floating"),
            Field("maxSize", ListType(IntegerType(1, 10_000), 2, 2), "[width, height] it is at most while floating"),
            Field("keepAwake", FlagType, "the screen stays on while it is shown"),
            Field("animated", FlagType, "false: no animations when it opens, closes or moves"),
            Field("hiddenFromCapture", FlagType, "screenshots and screen sharing show a black box in its place"),
            Field("takesFocus", FlagType, "false: it never gets the keyboard"),
            Field("ignore", ListType(ChoiceType(listOf("fullscreen", "maximize", "minimize", "activate"))), "what its program asks that is not done"),
            Field("tags", ListType(TextType("a tag")), "tags it has while it fits (for later rules with hasTag)"),
        ),
        "a window rule",
    )

    internal val layoutSection = SectionType(
        listOf(
            Field("name", ChoiceType(Layouts.names), "how windows are arranged"),
            Field("gaps", gaps, "space around and between windows"),
            Field("ratio", DecimalType(0.1, 0.9), "the main window's share (master layouts)"),
            Field("side", ChoiceType(listOf("left", "right", "top", "bottom")), "the main window's side (master-stack)"),
            Field("masters", IntegerType(1, 20), "how many masters (master-stack)"),
            Field("newWindow", ChoiceType(listOf("stack-end", "stack-top", "master")), "where a new window goes (master-stack)"),
            Field("splitRatio", DecimalType(0.1, 0.9), "the old window's share when a new one splits it (tree)"),
            Field("columnWidths", ListType(DecimalType(0.1, 1.0), 1, 10), "the widths column-width goes through (scrolling-columns)"),
            Field("newColumnWidth", DecimalType(0.1, 1.0), "a new column's width (scrolling-columns)"),
            Field("centerFocused", FlagType, "the focused column always in the middle (scrolling-columns)"),
        ),
        "the layout",
    )

    internal val workspaces = SectionType(
        listOf(
            Field("count", IntegerType(1, 99), "how many there are (1: no workspaces, one desktop)"),
            Field(
                "rules",
                ListType(
                    SectionType(
                        listOf(
                            Field("workspace", IntegerType(1, 99), "its number", required = true),
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
    private val restartType: SettingType = EitherType(
        ChoiceType(listOf("never", "on-crash", "always")),
        SectionType(
            listOf(
                Field("when", ChoiceType(listOf("never", "on-crash", "always")), "when it is started again"),
                Field("maxTimes", IntegerType(0, 1000), "at most this many times (default 3)"),
                Field("withinMillis", IntegerType(1, 86_400_000), "within this time (default 60000)"),
            ),
            "a restart rule",
        ),
    )

    internal val autostartEntry = SectionType(
        listOf(
            Field("app", EitherType(TextType("a program"), ListType(TextType("a word"), 1)), "a program and its arguments (no shell): [\"nm-applet\", \"--indicator\"]"),
            Field("script", TextType("an executable file"), "an executable file (~ is your home)"),
            Field("shell", TextType("shell"), "a piece of shell (/bin/sh -c)"),
            Field("arguments", ListType(TextType("an argument")), "the script's arguments"),
            Field("name", TextType("a name"), "its name (for after, ifNotRunning and the status; default the program's)"),
            Field("delayMillis", IntegerType(0, 3_600_000), "wait this long more before it starts"),
            Field("ifNotRunning", FlagType, "not started when a program of its name runs already"),
            Field("restart", restartType, "started again when it ended: never (default), on-crash, always, or { when, maxTimes, withinMillis }"),
            Field("after", ListType(TextType("an entry's name")), "start only once these entries have started"),
            Field("env", MapType(TextType("a value"), "variable"), "more environment for it"),
            Field("dir", TextType("a folder"), "the folder it starts in (~ is your home)"),
        ),
        "a program to start",
    )
}
