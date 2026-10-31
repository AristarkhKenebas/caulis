package dev.caulis.wm.config

import dev.caulis.log.InternalCaulisApi
import dev.caulis.core.WallpaperShader
import dev.caulis.wm.Caulis

/**
 * Everything a configuration file (config.json, or what the Nix module writes) can say —
 * one description: read from JSON, checked, applied to a [Caulis], and turned into the Nix
 * module's typed options and a JSON Schema for editors.
 */
@InternalCaulisApi
object Settings {

    internal val configLog = dev.caulis.log.Logger("config")

    // --- the pieces ---------------------------------------------------------------------------

    val root = SectionType(
        listOf(
            Field("layout", SettingsPieces.layoutSection, "how windows are arranged"),
            Field("scale", DecimalType(0.5, 4.0), "how big everything is (1.0, 1.25, 1.5, 2.0, ...)"),
            Field(
                "workspaceMode",
                ChoiceType(listOf("per-monitor", "pinned", "separate", "spanning", "primary-only")),
                "shared numbers on any monitor, pinned to monitors, each monitor its own, one over all, or only on the primary monitor",
            ),
            Field("primaryMonitor", TextType("a connector"), "the primary monitor (\"DP-2\")"),
            Field("workspaces", SettingsPieces.workspaces, "how many workspaces, and how each is"),
            Field("monitorEdges", ChoiceType(listOf("touching", "proportional")), "how the mouse goes between monitors of different sizes"),
            Field(
                "monitors",
                MapType(
                    SectionType(
                        listOf(
                            Field("scale", DecimalType(0.5, 4.0), "its own scale"),
                            Field("position", ListType(SettingsPieces.sizeInt, 2, 2), "[x, y] on the desktop, in units"),
                            Field("mode", ListType(DecimalType(1.0, 20_000.0), 2, 3), "[width, height] or [width, height, hz]"),
                            Field("enabled", FlagType, "false: not used"),
                            Field("transform", ChoiceType(listOf("normal", "90", "180", "270", "flipped", "flipped-90", "flipped-180", "flipped-270")), "turned clockwise (a screen on its side: 90)"),
                            Field("maxBpc", IntegerType(6, 16), "the most bits per colour the link may use (default 8)"),
                            Field("adaptiveSync", FlagType, "variable refresh rate (FreeSync) if the monitor has it"),
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
                        Field("numLock", FlagType, "Num Lock on (true) or off (false) from the start (else as the keyboard is)"),
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
                        Field("pointers", SettingsPieces.device, "every mouse, trackball and touchpad"),
                        Field("touchpads", SettingsPieces.device, "touchpads (on top of pointers)"),
                        Field("tablets", SettingsPieces.tablet, "drawing tablets and their pens"),
                        Field("devices", MapType(SettingsPieces.anyDevice, "device name"), "single devices by name (--devices lists them; * matches anything)"),
                    ),
                    "input",
                ),
                "mice, touchpads, tablets, focus",
            ),
            Field(
                "idle",
                ListType(
                    SectionType(
                        listOf(
                            Field("after", IntegerType(1, 86_400), "seconds without input", required = true),
                            Field("action", ActionType, "what happens then (\"dpms off\", \"lock\", \"exec ...\")"),
                            Field("resume", ActionType, "what happens when someone is back"),
                        ),
                        "an idle timer",
                    ),
                ),
                "what happens when nobody uses the computer",
            ),
            Field("focusOnActivate", FlagType, "a window asking for the focus with a good token gets it (false: only marked urgent)"),
            Field("activationTokensNeedFocus", FlagType, "a program's activation token is good only while that program has the keyboard (default true)"),
            Field("fullscreenKeepsAwake", FlagType, "a fullscreen window keeps idle timers off"),
            Field("wakeScreensOnInput", FlagType, "input switches dark screens on again"),
            Field("wakeScreensAfterMillis", IntegerType(0, 60_000), "input this soon after screens went dark does not wake them"),
            Field("scratchpadStartMillis", IntegerType(0, 60_000), "how long a scratchpad's program may take before its key starts it again"),
            Field("scriptCallLimitMillis", IntegerType(10, 60_000), "how long one call into config.lua's code may run"),
            Field("compiledScriptsKept", IntegerType(1, 100), "how many compiled versions of config.kts stay in its cache"),
            Field(
                "lid",
                SectionType(
                    listOf(
                        Field("close", ActionType, "when the laptop's lid closes"),
                        Field("open", ActionType, "when it opens"),
                        Field("turnsScreenOff", FlagType, "the laptop's screen is off while the lid is closed and another monitor is in use"),
                    ),
                    "the lid",
                ),
                "the laptop's lid",
            ),
            Field(
                "sleep",
                SectionType(
                    listOf(
                        Field("lock", FlagType, "lock the screen before the computer sleeps"),
                        Field("lockWaitMillis", IntegerType(0, 30_000), "how long sleep waits for the lock screen to show"),
                        Field("ownLockShowMillis", IntegerType(0, 5_000), "how long the compositor's own lock screen shows before sleep, when the locker did not"),
                        Field("before", ActionType, "just before it sleeps"),
                        Field("after", ActionType, "when it woke up"),
                    ),
                    "sleep",
                ),
                "sleep and hibernation",
            ),
            Field(
                "displays",
                SectionType(listOf(Field("chooserOnPlug", FlagType, "a monitor never seen before shows the display modes")), "the display modes"),
                "a laptop with a monitor or projector",
            ),
            Field("cursor", SectionType(listOf(Field("theme", TextType("a cursor theme"), "an installed cursor theme"), Field("size", IntegerType(8, 256), "its size")), "the cursor"), "the cursor"),
            Field(
                "programs",
                SectionType(
                    listOf(
                        Field("environment", MapType(TextType("a value"), "variable"), "more environment for programs started inside (wins over Caulis's own choices)"),
                        Field("xwayland", FlagType, "run X11 programs too (only at the start)"),
                        Field("waylandFirst", FlagType, "start programs the Wayland way, through X11 when that fails"),
                        Field("x11Programs", ListType(TextType("a program name")), "programs always started through X11"),
                        Field("x11Fallback", FlagType, "a program that connected and failed at once is started again through X11"),
                        Field("x11FallbackMillis", IntegerType(0, 60_000), "how soon it must fail for that"),
                        Field("stopOnQuit", FlagType, "programs started inside are asked to end when the compositor quits"),
                        Field("sharpJavaScaling", FlagType, "Java programs sharp on fractional scales (only at the start)"),
                        Field("desktopName", TextType("a name"), "what programs are told the desktop is called (XDG_CURRENT_DESKTOP)"),
                        Field("exportEnvironment", FlagType, "hand the environment to systemd and D-Bus activation at the start (default: on a real screen only)"),
                        Field("portalsToRestart", ListType(TextType("a systemd user unit")), "services started again with this compositor's environment when they had another one"),
                        Field("output", ChoiceType(listOf("discard", "log", "file")), "where started programs' output goes"),
                        Field("outputFile", FileType(), "the file for output \"file\" (default ~/.cache/<name>/programs.log)"),
                        Field("start", ChoiceType(listOf("native", "java")), "how programs are started: native (posix_spawn; survives Java being updated meanwhile) or java (ProcessBuilder)"),
                        Field("javaUpdatedNotice", TextType(), "shown once when Java was updated under the running compositor (\"\": not shown)"),
                    ),
                    "programs",
                ),
                "programs started inside",
            ),
            Field(
                "performance",
                SectionType(
                    listOf(
                        Field("stallReportMillis", IntegerType(0, 60_000), "the main loop busy this long with one thing is logged with what it was (0: not watched)"),
                        Field("trimMemory", FlagType, "memory the C library freed is given back to the system after the start and now and then"),
                        Field("trimMemoryEveryMinutes", IntegerType(0, 1440), "how often after the start (0: only after the start)"),
                        Field("mallocPools", IntegerType(0, 64), "at most this many malloc pools for the compositor (0: glibc's own; only at the start)"),
                        Field("quickCursor", FlagType, "the cursor moves the moment the pointer does, not with the next frame"),
                        Field("mallocMapAboveKb", IntegerType(0, 65536), "blocks of this many kB or more are given back to the system the moment they are freed (0: glibc's own; only at the start)"),
                        Field("inputThread", FlagType, "keyboards, mice and touchpads are read on a thread of their own; the cursor keeps moving while the compositor is busy"),
                        Field("glVendorByDriver", FlagType, "only the EGL driver for the GPU in use is loaded, not every one installed (only at the start)"),
                        Field("widgetThreads", IntegerType(0, 16), "threads the compositor's own widgets (bars, menus) draw their pixels on (0: its own thread)"),
                        Field("movingPaintsFps", IntegerType(0, 240), "borders and shadows that move by themselves (turning gradients, shaders) drawn anew at most this often a second (0: every frame)"),
                    ),
                    "performance",
                ),
                "watching the main loop, giving memory back",
            ),
            Field(
                "systemKeys",
                SectionType(
                    listOf(
                        Field("volumeStep", IntegerType(1, 100), "percent per press of the volume keys"),
                        Field("maxVolume", DecimalType(0.1, 4.0), "the loudest they go (1.0: 100 %)"),
                        Field("unmuteOnVolumeChange", FlagType, "changing the volume switches the sound on again"),
                        Field("brightnessStep", IntegerType(1, 100), "percent per press of the brightness keys"),
                        Field("minBrightnessPercent", IntegerType(0, 100), "the darkest they go"),
                        Field("backlight", TextType("a backlight"), "the backlight, by its name in /sys/class/backlight (default: the first)"),
                        Field("wpctl", TextType("a program"), "the program for the volume and the microphone"),
                        Field("brightnessctl", TextType("a program"), "the program for the brightness without logind"),
                        Field("playerctl", TextType("a program"), "the program for music players"),
                        Field("timeoutMillis", IntegerType(100, 60_000), "how long these programs may take"),
                    ),
                    "the laptop's keys",
                ),
                "what the laptop's keys do: steps, limits, programs",
            ),
            Field(
                "screen",
                SectionType(
                    listOf(
                        Field("hardwareCursor", FlagType, "the pointer on the display's cursor plane (only at the start)"),
                        Field("directScanout", FlagType, "fullscreen programs' pictures directly on the monitor (only at the start)"),
                        Field("touchscreenMonitor", TextType("a connector"), "the monitor touch screens cover (default: the laptop's own)"),
                        Field("builtInConnectors", ListType(TextType("a connector's beginning"), 1), "how the laptop's own screen's connector begins (default eDP, LVDS, DSI)"),
                    ),
                    "the screen",
                ),
                "on a real screen",
            ),
            Field(
                "titleBarStyle",
                SectionType(
                    listOf(
                        Field("height", IntegerType(12, 120), "how high, in units"),
                        Field("font", TextType("a font"), "the title's font"),
                        Field("fontSize", IntegerType(6, 72), "the title's size"),
                        Field("bold", FlagType, "a bold title"),
                        Field("centerTitle", FlagType, "the title in the middle"),
                        Field("buttons", ListType(ChoiceType(listOf("minimize", "maximize", "close"))), "the buttons, in order"),
                        Field("buttonsLeft", FlagType, "the buttons on the left"),
                        Field("focusedBackground", ColorType, "behind the title with the focus"),
                        Field("unfocusedBackground", ColorType, "behind it without"),
                        Field("focusedText", ColorType, "the title with the focus"),
                        Field("unfocusedText", ColorType, "the title without"),
                        Field("buttonHover", ColorType, "behind a button under the pointer"),
                        Field("closeHover", ColorType, "behind the close button under the pointer"),
                    ),
                    "the title bars",
                ),
                "how the compositor's title bars look (titleBars \"compositor\")",
            ),
            Field("terminal", TextType("a command"), "the terminal the terminal key opens"),
            Field("lockCommand", TextType("a command"), "the screen locker"),
            Field("lockShowMillis", IntegerType(100, 60_000), "how long a lock may take to show before it counts as failed (lockFailure)"),
            Field("lockFailure", ChoiceType(listOf("own-lock", "notice")), "when a lock does not show: the compositor's own plain lock screen, or only a notice (and sleep unlocked)"),
            Field("titleBars", ChoiceType(listOf("program", "compositor", "none")), "who draws title bars"),
            Field("keys", SettingsPieces.keys, "the usual keys, changed or turned off"),
            Field("bind", MapType(BindType, "key combination"), "your own keys: combination → action (\"exec firefox\", \"workspace 3\", ...) or { action, repeat, release, locked, description }"),
            Field("modes", MapType(MapType(BindType, "key combination"), "mode"), "key modes (\"resize\"): their own keys, on with the action \"mode resize\", off with \"mode reset\""),
            Field("rules", ListType(SettingsPieces.rule), "rules for some windows"),
            Field("panelRules", ListType(SettingsPieces.panelRule), "rules for some panels (bars, launchers, notifications)"),
            Field(
                "specialWorkspaces",
                SectionType(
                    listOf(
                        Field("dim", DecimalType(0.0, 1.0), "how much darker everything behind a shown one is"),
                        Field("blur", OffOrType(SettingsPieces.blurSection), "what is behind it blurred"),
                        Field("gap", IntegerType(0, 1000), "space between it and the monitor's edges"),
                        Field("size", DecimalType(0.2, 1.0), "how much of the monitor it takes"),
                        Field("animation", ChoiceType(dev.caulis.core.AnimationTree.CAULIS.names), "the animation its windows come and go with"),
                        Field("hideWhenEmpty", FlagType, "hide when its last window closes or leaves"),
                        Field("newWindowsJoin", FlagType, "windows opening while it is shown go into it"),
                        Field("hideOnLock", FlagType, "hide when the screen locks"),
                        Field("clickOutsideHides", FlagType, "a click on the dimmed area hides it"),
                        Field("layout", SettingsPieces.layoutSection, "its own layout (else the usual one)"),
                    ),
                    "special workspaces",
                ),
                "special workspaces (Hyprland's special): shown over the current workspace with special-workspace toggle [name]",
            ),
            Field(
                "screenShader",
                SectionType(
                    listOf(
                        Field("preset", ChoiceType(listOf("grayscale", "invert")), "a ready one"),
                        Field("warm", DecimalType(0.0, 1.0), "a night light: this much less blue"),
                        Field("file", FileType("a GLSL file"), "your own: vec4 effect(vec4 color, vec2 at) (read again when saved)"),
                        Field("moving", FlagType, "drawn every frame (it uses time)"),
                    ),
                    "the screen shader",
                ),
                "a shader the whole screen goes through last",
            ),
            Field(
                "effects",
                SectionType(
                    listOf(
                        Field("preset", ChoiceType(listOf("none", "modern")), "start from no effects, or the modern look"),
                        Field("cornerRadius", IntegerType(0, 100), "how round the corners are"),
                        Field(
                            "border",
                            OffOrType(
                                SectionType(
                                    listOf(
                                        Field("width", IntegerType(1, 50), "its width"),
                                        Field("focused", SettingsPieces.paint, "with the focus"),
                                        Field("unfocused", SettingsPieces.paint, "without"),
                                        Field("urgent", SettingsPieces.paint, "a window that wants attention (else as the others)"),
                                        Field("groupedFocused", SettingsPieces.paint, "the focused window of a group (else focused)"),
                                        Field("groupedUnfocused", SettingsPieces.paint, "the other windows of a group (else unfocused)"),
                                        Field("animate", FlagType, "false: turning gradients and shaders using time stand still"),
                                    ),
                                    "a border",
                                ),
                            ),
                            "a frame around windows",
                        ),
                        Field("shadow", OffOrType(SettingsPieces.shadow), "the shadow (of the focused window)"),
                        Field("inactiveShadow", OffOrType(SettingsPieces.shadow), "the shadow of windows without the focus"),
                        Field("blur", OffOrType(SettingsPieces.blurSection), "blur behind see-through windows"),
                        Field("dimInactive", DecimalType(0.0, 1.0), "how much darker windows without the focus are (0: not at all)"),
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
                        Field("enabled", FlagType, "animations on (default false: everything changes at once; one turned on by name runs all the same)"),
                        Field("millis", IntegerType(0, 5000), "the global time in milliseconds, of those without one of their own (0: none at all)"),
                        Field("windowOpen", SettingsPieces.windowAnimation, "a window appearing, as code's look (older; windowsIn is the same by name)"),
                        Field("windowClose", SettingsPieces.windowAnimation, "a window going away, as code's look (older; windowsOut is the same by name)"),
                        Field("windowMove", SettingsPieces.animationType(ChoiceType(listOf("move", "none"))), "windows gliding to new places (older; windowsMove is the same by name)"),
                        Field("workspaceSwitch", SettingsPieces.animationType(ChoiceType(listOf("slide", "slide-vertical", "fade", "none"))), "another workspace shown (older; workspaces is the same by name)"),
                        Field("curve", SettingsPieces.curveName, "the global curve: how animations move"),
                        Field("curves", MapType(SettingsPieces.curve, "curve name"), "your own curves by name, for curve fields"),
                        Field("overviewMillis", IntegerType(0, 5000), "the overview zooming (older; overview.millis)"),
                        Field("scaleChangeMillis", IntegerType(0, 5000), "the picture melting when the scale changes (older; fadeScale.millis)"),
                    ) + dev.caulis.core.AnimationTree.CAULIS.nodes.map { node -> Field(node.name, SettingsPieces.namedAnimation(node), node.about) },
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
                        Field("colors", ListType(ColorType, 0, WallpaperShader.MAX_COLORS), "colours handed to the shader (colors[0].., colorCount)"),
                        Field("values", MapType(DecimalType(-1e6, 1e6), "value name"), "the shader's settings: each a float of its name in its code (speed, intensity, ...)"),
                        Field("fallback", ColorType, "shown if the shader does not compile"),
                        Field("resolution", DecimalType(0.1, 1.0), "how sharp the shader is drawn (0.5: half the pixels each way, a quarter of the work)"),
                        Field("fpsLimit", IntegerType(0, 240), "the most frames a second for any wallpaper shader (0: as each asks)"),
                        Field("paused", FlagType, "wallpaper shaders stand still"),
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
                        Field("errorRoom", ChoiceType(listOf("push-all", "push-windows", "cover")), "what makes way for an error's banner (else as room)"),
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
            Field(
                "appearance",
                SectionType(
                    listOf(
                        Field("colorScheme", ChoiceType(listOf("default", "dark", "light")), "light or dark for programs (default: each its own)"),
                        Field("darkFrom", TimeType, "with lightFrom: dark from this time of day"),
                        Field("lightFrom", TimeType, "with darkFrom: light from this time of day"),
                        Field("latitude", DecimalType(-90.0, 90.0), "with longitude: dark from sunset to sunrise there"),
                        Field("longitude", DecimalType(-180.0, 180.0), "with latitude: the place (east positive)"),
                        Field("accentColor", ColorType, "the accent colour programs are told"),
                        Field("gtkLightTheme", TextType("a GTK theme"), "with gtkDarkTheme: GTK 3 programs' theme when light (\"Adwaita\")"),
                        Field("gtkDarkTheme", TextType("a GTK theme"), "with gtkLightTheme: their theme when dark (\"Adwaita-dark\")"),
                        Field("toPrograms", FlagType, "tell programs (default: on a real screen only, not nested)"),
                    ),
                    "light and dark",
                ),
                "how programs look: light or dark, by a schedule",
            ),
            Field("hideCursorWhileTyping", FlagType, "hide the cursor while typing"),
            Field("hideCursorAfterSeconds", IntegerType(0, 3600), "hide the cursor when the mouse is still (0: never)"),
            Field("lockColor", ColorType, "behind the screen locker"),
            Field("backgroundColor", ColorType, "under everything (where no window or wallpaper is)"),
            Field("focusStealing", ChoiceType(listOf("same-program", "always", "never")), "which programs may take the keyboard by themselves"),
            Field("unlockFocus", ChoiceType(listOf("focused-before", "newest-before")), "which window gets the keyboard after unlocking (never one opened while locked)"),
            Field("idleInhibitors", FlagType, "programs' idle inhibitors (video players: idle-inhibit) keep the screen on (default true)"),
            Field("idleInhibitorsWhileLocked", FlagType, "idle inhibitors keep the screen on while it is locked too"),
            Field("maxForwardedStringBytes", IntegerType(16, 3000), "the longest title or app id passed on to other programs, in bytes"),
            Field("maxRegionParts", IntegerType(1, 100_000), "rectangles a program's region keeps exactly"),
            Field("windowListPrograms", ListType(TextType("a program name")), "the programs that may see the list of windows (default: the privileged ones)"),
            Field(
                "permissions",
                SectionType(
                    listOf(
                        Field(
                            "rules",
                            ListType(
                                SectionType(
                                    listOf(
                                        Field("program", TextType("a regular expression"), "the program's path (\"/usr/bin/grim\") or name (\"grim\"), a regular expression", required = true),
                                        Field("feature", ChoiceType(dev.caulis.wm.Permissions.FEATURES), "what it wants", required = true),
                                        Field("mode", ChoiceType(listOf("allow", "ask", "deny")), "allow, ask (screencopy only) or deny", required = true),
                                    ),
                                    "a permission rule",
                                ),
                            ),
                            "the rules, the first that matches decides",
                        ),
                        Field("defaults", MapType(ChoiceType(listOf("allow", "ask", "deny")), "feature"), "what a feature gets when no rule matches (default: the privileged rule)"),
                    ),
                    "permissions by program",
                ),
                "which programs may take pictures of the screen, see the window list, read the clipboard, show panels, lock, arrange monitors (as Hyprland's permissions)",
            ),
            Field("primarySelection", FlagType, "middle-click paste (default true; only at the start)"),
            Field("adaptiveSync", FlagType, "variable refresh rate on every monitor that has it, unless its own says otherwise (default false)"),
            Field("bindingModifiers", ChoiceType(listOf("xkb", "physical")), "modifiers in bindings as the keyboard layout says, or the physical keys"),
            Field("bindingsUseFirstLatinLayout", FlagType, "letters in bindings follow the layout, on Russian or Greek the first Latin layout's (default true); false: physical keys"),
            Field(
                "xwaylandRestart",
                SectionType(
                    listOf(
                        Field("millis", IntegerType(0, 600_000), "wait after a failed start (doubled each time)"),
                        Field("maxMillis", IntegerType(0, 3_600_000), "the longest wait"),
                        Field("maxFailedStarts", IntegerType(1, 1000), "failed starts in a row before giving up (until xwayland-retry)"),
                    ),
                    "Xwayland's restarts",
                ),
                "when Xwayland fails to start",
            ),
            Field("startup", ListType(TextType("a command")), "programs started with the compositor"),
            Field(
                "autostart",
                SectionType(
                    listOf(
                        Field("staggerMillis", IntegerType(0, 60_000), "the time between two programs starting (default 400)"),
                        Field("afterReveal", FlagType, "start them once the desktop is shown (default true)"),
                        Field("desktopEntries", FlagType, "also the .desktop files of ~/.config/autostart and /etc/xdg/autostart (default false)"),
                        Field("afterTimeoutMillis", IntegerType(0, 600_000), "how long an entry waits for those it starts after (default 10000)"),
                        Field("entries", ListType(SettingsPieces.autostartEntry), "the programs, in order"),
                    ),
                    "the programs started with the session",
                ),
                "programs started with the session, one after another, once per session (a reload starts only new entries)",
            ),
            Field(
                "reveal",
                SectionType(
                    listOf(
                        Field("enabled", FlagType, "dark until warm, then the desktop fades in (default false)"),
                        Field("fadeMillis", IntegerType(0, 60_000), "how long the fade takes (default 600)"),
                        Field("maxWaitMillis", IntegerType(0, 120_000), "the longest the screen stays dark (default 4000)"),
                        Field("color", ColorType, "the colour until then (default black)"),
                        Field("warmFrames", IntegerType(0, 120), "frames drawn dark before it fades in (default 3)"),
                        Field("hideCursor", FlagType, "the pointer hidden while dark (default true)"),
                    ),
                    "the start",
                ),
                "the start, smooth: dark until warm, then the desktop fades in",
            ),
            Field(
                "startupSound",
                SectionType(
                    listOf(
                        Field("enabled", FlagType, "play it (default false)"),
                        Field("preset", ChoiceType(dev.caulis.wm.StartupChimes.PRESETS), "one of the sounds Caulis makes itself (default airy)"),
                        Field("file", FileType("a sound file"), "a sound file of yours instead (ogg, wav, flac: what the player plays)"),
                        Field("volume", DecimalType(0.0, 1.0), "how loud, 0 to 1 (default 0.8)"),
                        Field("delayMillis", IntegerType(0, 60_000), "wait this long more before it plays"),
                        Field("at", ChoiceType(listOf("reveal", "start")), "when the desktop fades in (default), or right at the start"),
                        Field("whenHeadless", FlagType, "played in the headless backend too (default false)"),
                        Field("player", ListType(TextType("a word"), 1), "your player's command, its words ({file}, {volume}, {paVolume}, {percent})"),
                        Field("players", ListType(TextType("a command"), 1), "the players tried in order (default pw-play, paplay, aplay)"),
                        Field("playerTimeoutMillis", IntegerType(1_000, 600_000), "how long a player may play before it is stopped (default 30000)"),
                    ),
                    "the startup sound",
                ),
                "a sound when the session starts",
            ),
            Field("logLevel", IntegerType(0, 3), "how much the log says"),
        ),
        "the configuration",
    )

    // --- applying ----------------------------------------------------------------------------

    /**
     * What a file added to the lists of a [Caulis] (rules, keys, programs to start), so that a
     * new version replaces them instead of adding to them.
     */
    @InternalCaulisApi
    class Added(val rules: List<dev.caulis.wm.WindowRule>, val bindings: List<Caulis.Binding>, val startup: List<Caulis.() -> Unit>)

    /**
     * [apply]s each setting on its own, in the usual order: one that cannot be applied (a
     * picture that cannot be read, a mistake in code it calls) is a problem, the rest still apply.
     */
    fun applyEach(caulis: Caulis, values: Map<String, Any?>): List<String> = SettingsApply.applyEach(caulis, values)

    /** Applies [values] (read by [root]) to [caulis]; what it adds to lists comes back. */
    @Suppress("UNCHECKED_CAST") // (the values were read by their types: the casts only name them)
    fun apply(caulis: Caulis, values: Map<String, Any?>): Added = SettingsApply.apply(caulis, values)

}
