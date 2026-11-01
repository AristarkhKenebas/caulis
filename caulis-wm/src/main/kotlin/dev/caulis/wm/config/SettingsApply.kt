package dev.caulis.wm.config

import dev.caulis.core.Animations
import dev.caulis.core.Curve
import dev.caulis.core.MonitorEdges
import dev.caulis.core.TitleBars
import dev.caulis.core.WindowAnimation
import dev.caulis.core.WorkspaceAnimation
import dev.caulis.core.WorkspaceMode
import dev.caulis.core.input.KeyBindings
import dev.caulis.render.Color
import dev.caulis.tiling.Gaps
import dev.caulis.tiling.Layout
import dev.caulis.tiling.NewWindow
import dev.caulis.tiling.Side
import dev.caulis.wm.Caulis

/**
 * What the settings read do to a Caulis.
 */
internal object SettingsApply {

    fun applyEach(caulis: Caulis, values: Map<String, Any?>): List<String> {
        val problems = ArrayList<String>()
        val order = Settings.root.fields.map { it.name }
        for (name in values.keys.sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }) {
            try {
                apply(caulis, mapOf(name to values[name]))
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) throw e
                problems += "$name could not be applied: ${e.message ?: e}"
            }
        }
        return problems
    }

    @Suppress("UNCHECKED_CAST") // (the values were read by their types: the casts only name them)
    fun apply(caulis: Caulis, values: Map<String, Any?>): Settings.Added {
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
                (m["maxBpc"] as Int?)?.let { maxBpc = it }
                (m["adaptiveSync"] as Boolean?)?.let { adaptiveSync = it }
                (m["transform"] as String?)?.let { t ->
                    val flipped = t.startsWith("flipped")
                    val degrees = t.substringAfter("flipped").removePrefix("-").ifEmpty { "0" }.let { if (it == "normal") "0" else it }.toInt()
                    transform = dev.caulis.core.Transform.entries.first { it.flipped == flipped && it.degrees == degrees }
                }
            }
        }
        section("keyboard")?.let { k ->
            (k["variants"] as String?)?.let { caulis.keyboardVariants = it }
            (k["model"] as String?)?.let { caulis.keyboardModel = it }
            (k["repeatRate"] as Int?)?.let { caulis.repeatRate = it }
            (k["repeatDelay"] as Int?)?.let { caulis.repeatDelayMillis = it.toLong() }
            (k["numLock"] as Boolean?)?.let { caulis.numLock = it }
            (k["layouts"] as String?)?.let { caulis.keyboardLayouts = it }
            (k["options"] as String?)?.let { caulis.keyboardOptions = it }
        }
        section("performance")?.let { p ->
            (p["stallReportMillis"] as Int?)?.let { caulis.stallReportMillis = it.toLong() }
            (p["stallWarnMillis"] as Int?)?.let { caulis.stallWarnMillis = it.toLong() }
            (p["trimMemory"] as Boolean?)?.let { caulis.trimMemory = it }
            (p["trimMemoryEveryMinutes"] as Int?)?.let { caulis.trimMemoryEveryMinutes = it }
            (p["mallocPools"] as Int?)?.let { caulis.mallocPools = it }
            (p["quickCursor"] as Boolean?)?.let { caulis.quickCursorMoves = it }
            (p["inputThread"] as Boolean?)?.let { caulis.inputThread = it }
            (p["mallocMapAboveKb"] as Int?)?.let { caulis.mallocMapAboveKb = if (it in 1..15) 16 else it }
            (p["glVendorByDriver"] as Boolean?)?.let { caulis.glVendorByDriver = it }
            (p["widgetThreads"] as Int?)?.let { caulis.widgetThreads = it }
            (p["waitForClientGpu"] as Boolean?)?.let { caulis.waitForClientGpu = it }
            (p["maxRenderMillis"] as Int?)?.let { caulis.maxRenderMillis = it }
            (p["movingPaintsFps"] as Int?)?.let { caulis.movingPaintsFps = it }
        }
        section("cursor")?.let { c ->
            (c["theme"] as String?)?.let { caulis.cursorTheme = it }
            (c["size"] as Int?)?.let { caulis.cursorSize = it }
        }
        section("programs")?.let { p ->
            (p["environment"] as Map<String, String>?)?.let { caulis.environment.putAll(it) }
            (p["xwayland"] as Boolean?)?.let { caulis.xwayland = it }
            (p["waylandFirst"] as Boolean?)?.let { caulis.waylandFirst = it }
            (p["x11Programs"] as List<String>?)?.let { caulis.x11Programs += it }
            (p["x11Fallback"] as Boolean?)?.let { caulis.x11Fallback = it }
            (p["x11FallbackMillis"] as Int?)?.let { caulis.x11FallbackMillis = it.toLong() }
            (p["stopOnQuit"] as Boolean?)?.let { caulis.stopProgramsOnQuit = it }
            (p["sharpJavaScaling"] as Boolean?)?.let { caulis.sharpJavaScaling = it }
            (p["desktopName"] as String?)?.let { caulis.desktopName = it }
            (p["exportEnvironment"] as Boolean?)?.let { caulis.exportEnvironment = it }
            (p["portalsToRestart"] as List<String>?)?.let { caulis.portalsToRestart.clear(); caulis.portalsToRestart += it }
            (p["output"] as String?)?.let { caulis.programOutput = dev.caulis.wm.ProgramOutput.valueOf(it.uppercase()) }
            (p["outputFile"] as String?)?.let { caulis.programOutputFile = caulis.file(it) }
            (p["start"] as String?)?.let { caulis.processStart = dev.caulis.ffi.Processes.Method.valueOf(it.uppercase()) }
            (p["javaUpdatedNotice"] as String?)?.let { caulis.javaUpdatedNotice = it.ifEmpty { null } }
        }
        section("systemKeys")?.let { k ->
            val keys = caulis.systemKeys
            (k["volumeStep"] as Int?)?.let { keys.volumeStep = it }
            (k["maxVolume"] as Double?)?.let { keys.maxVolume = it }
            (k["unmuteOnVolumeChange"] as Boolean?)?.let { keys.unmuteOnVolumeChange = it }
            (k["brightnessStep"] as Int?)?.let { keys.brightnessStep = it }
            (k["minBrightnessPercent"] as Int?)?.let { keys.minBrightnessPercent = it }
            (k["backlight"] as String?)?.let { keys.backlight = it }
            (k["wpctl"] as String?)?.let { keys.wpctl = it }
            (k["brightnessctl"] as String?)?.let { keys.brightnessctl = it }
            (k["playerctl"] as String?)?.let { keys.playerctl = it }
            (k["timeoutMillis"] as Int?)?.let { keys.timeoutMillis = it.toLong() }
        }
        section("screen")?.let { sc ->
            (sc["hardwareCursor"] as Boolean?)?.let { caulis.hardwareCursor = it }
            (sc["directScanout"] as Boolean?)?.let { caulis.directScanout = it }
            (sc["touchscreenMonitor"] as String?)?.let { caulis.touchscreenMonitor = it }
            (sc["builtInConnectors"] as List<String>?)?.let { caulis.builtInConnectors.clear(); caulis.builtInConnectors += it }
        }
        section("titleBarStyle")?.let { t ->
            val base = caulis.titleBarStyle
            caulis.titleBarStyle = base.copy(
                height = t["height"] as Int? ?: base.height,
                font = t["font"] as String? ?: base.font,
                fontSize = t["fontSize"] as Int? ?: base.fontSize,
                bold = t["bold"] as Boolean? ?: base.bold,
                centerTitle = t["centerTitle"] as Boolean? ?: base.centerTitle,
                buttons = (t["buttons"] as List<String>?)?.map { dev.caulis.core.TitleButton.valueOf(it.uppercase()) } ?: base.buttons,
                buttonsLeft = t["buttonsLeft"] as Boolean? ?: base.buttonsLeft,
                focusedBackground = t["focusedBackground"] as Color? ?: base.focusedBackground,
                unfocusedBackground = t["unfocusedBackground"] as Color? ?: base.unfocusedBackground,
                focusedText = t["focusedText"] as Color? ?: base.focusedText,
                unfocusedText = t["unfocusedText"] as Color? ?: base.unfocusedText,
                buttonHover = t["buttonHover"] as Color? ?: base.buttonHover,
                closeHover = t["closeHover"] as Color? ?: base.closeHover,
            )
        }
        (values["terminal"] as String?)?.let { caulis.terminal = it }
        (values["lockCommand"] as String?)?.let { caulis.lockCommand = it }
        (values["lockShowMillis"] as Int?)?.let { caulis.lockShowMillis = it.toLong() }
        (values["lockFailure"] as String?)?.let { caulis.lockFailure = dev.caulis.wm.LockFailure.valueOf(it.uppercase().replace('-', '_')) }
        (values["titleBars"] as String?)?.let { caulis.titleBars = TitleBars.valueOf(it.uppercase()) }
        section("keys")?.let { k ->
            for ((name, value) in k) {
                if (name == "quitWhileLocked") caulis.keys.quitWhileLocked = value as Boolean
                else if (name == "lockWhileInhibited") caulis.keys.lockWhileInhibited = value as Boolean
                else caulis.keys[name] = if (value === OffOrType.Off) null else value as String
            }
        }
        (values["bind"] as Map<String, BindType.Bound>?)?.forEach { (combination, bound) ->
            caulis.bind(combination, bound.options, bound.action)
            bindings += caulis.bindings.last()
        }
        (values["modes"] as Map<String, Map<String, BindType.Bound>>?)?.forEach { (mode, keys) ->
            caulis.keyMode(mode) { for ((combination, bound) in keys) bind(combination, bound.options, bound.action) }
        }
        section("input")?.let { i ->
            (i["focusFollowsMouse"] as Boolean?)?.let { caulis.focusFollowsMouse = it }
            (i["pointers"] as Map<String, Any?>?)?.let { device(it, caulis.input.pointers) }
            (i["touchpads"] as Map<String, Any?>?)?.let { device(it, caulis.input.touchpads) }
            (i["tablets"] as Map<String, Any?>?)?.let { device(it, caulis.input.tablets) }
            (i["devices"] as Map<String, Map<String, Any?>>?)?.forEach { (name, d) ->
                device(d, caulis.input.devices.getOrPut(name) { dev.caulis.core.input.DeviceSettings() })
            }
        }
        (values["idle"] as List<Map<String, Any?>>?)?.forEach { i ->
            val action = i["action"] as (Caulis.() -> Unit)? ?: {}
            val resume = i["resume"] as (Caulis.() -> Unit)? ?: {}
            val after = i["after"] as Int? ?: return@forEach // (its check said so: "after" is required)
            caulis.onIdle(after.toLong(), resume = resume, idle = action)
        }
        (values["focusOnActivate"] as Boolean?)?.let { caulis.focusOnActivate = it }
        (values["activationTokensNeedFocus"] as Boolean?)?.let { caulis.activationTokensNeedFocus = it }
        (values["fullscreenKeepsAwake"] as Boolean?)?.let { caulis.fullscreenKeepsAwake = it }
        (values["wakeScreensOnInput"] as Boolean?)?.let { caulis.wakeScreensOnInput = it }
        (values["wakeScreensAfterMillis"] as Int?)?.let { caulis.wakeScreensAfterMillis = it.toLong() }
        (values["scratchpadStartMillis"] as Int?)?.let { caulis.scratchpadStartMillis = it.toLong() }
        (values["scriptCallLimitMillis"] as Int?)?.let { caulis.scriptCallLimitMillis = it.toLong() }
        (values["compiledScriptsKept"] as Int?)?.let { caulis.compiledScriptsKept = it }
        section("lid")?.let { l ->
            val close = l["close"] as (Caulis.() -> Unit)?
            val open = l["open"] as (Caulis.() -> Unit)?
            if (close != null || open != null) caulis.onLid { closed -> (if (closed) close else open)?.invoke(this) }
            (l["turnsScreenOff"] as Boolean?)?.let { caulis.lidTurnsScreenOff = it }
        }
        section("sleep")?.let { z ->
            (z["lock"] as Boolean?)?.let { caulis.lockBeforeSleep = it }
            (z["lockWaitMillis"] as Int?)?.let { caulis.sleepLockWaitMillis = it.toLong() }
            (z["ownLockShowMillis"] as Int?)?.let { caulis.sleepOwnLockShowMillis = it.toLong() }
            (z["before"] as (Caulis.() -> Unit)?)?.let { caulis.onSleep(it) }
            (z["after"] as (Caulis.() -> Unit)?)?.let { caulis.onWake(it) }
        }
        section("displays")?.let { d -> (d["chooserOnPlug"] as Boolean?)?.let { caulis.displayChooserOnPlug = it } }
        (values["rules"] as List<Map<String, Any?>>?)?.forEach { r -> rules += SettingsApplyLooks.rule(caulis, r) }
        (values["panelRules"] as List<Map<String, Any?>>?)?.forEach { r ->
            caulis.panelRule {
                (r["namespace"] as String?)?.let { namespace(it) }
                (r["namespacePattern"] as String?)?.let { namespace(Regex(it)) }
                (r["layer"] as String?)?.let { layer(dev.caulis.core.LayerShell.Layer.valueOf(it.uppercase())) }
                (r["blur"] as Boolean?)?.let { blur = it }
                (r["cornerRadius"] as Int?)?.let { cornerRadius = it }
                (r["shadow"] as Boolean?)?.let { shadow = it }
                (r["opacity"] as Double?)?.let { opacity = it.toFloat() }
                (r["dimAround"] as Double?)?.let { dimAround = it.toFloat() }
                (r["hiddenFromCapture"] as Boolean?)?.let { hiddenFromCapture = it }
            }
        }
        section("screenShader")?.let { sh ->
            val moving = sh["moving"] as Boolean? ?: false
            caulis.screenShader = when {
                sh["file"] != null -> dev.caulis.core.ScreenShader.file(caulis.file(sh["file"] as String), moving)
                sh["warm"] != null -> dev.caulis.core.ScreenShader.warm((sh["warm"] as Double).toFloat())
                sh["preset"] == "grayscale" -> dev.caulis.core.ScreenShader.GRAYSCALE
                sh["preset"] == "invert" -> dev.caulis.core.ScreenShader.INVERT
                else -> null
            }
        }
        section("effects")?.let { caulis.effects = SettingsApplyLooks.effects(caulis, it) }
        section("specialWorkspaces")?.let { sw ->
            val base = caulis.specialWorkspaces
            caulis.specialWorkspaces = dev.caulis.core.SpecialWorkspaces(
                dim = (sw["dim"] as Double?)?.toFloat() ?: base.dim,
                blur = when (val b = sw["blur"]) {
                    OffOrType.Off -> null
                    is Map<*, *> -> SettingsApplyLooks.blurOf(b)
                    else -> base.blur
                },
                gap = sw["gap"] as Int? ?: base.gap,
                size = sw["size"] as Double? ?: base.size,
                animation = sw["animation"] as String? ?: base.animation,
                hideWhenEmpty = sw["hideWhenEmpty"] as Boolean? ?: base.hideWhenEmpty,
                newWindowsJoin = sw["newWindowsJoin"] as Boolean? ?: base.newWindowsJoin,
                hideOnLock = sw["hideOnLock"] as Boolean? ?: base.hideOnLock,
                clickOutsideHides = sw["clickOutsideHides"] as Boolean? ?: base.clickOutsideHides,
                layout = (sw["layout"] as Map<String, Any?>?)?.let(::layout) ?: base.layout,
            )
        }
        section("animations")?.let { a ->
            (a["millis"] as Int?)?.let { caulis.animationMillis = it.toLong() }
            val base = caulis.animations
            // Curves by name: the ready ones and yours.
            val curves = HashMap<String, Curve>(mapOf("ease-out" to Curve.EASE_OUT, "ease-in-out" to Curve.EASE_IN_OUT, "linear" to Curve.LINEAR, "overshoot" to Curve.OVERSHOOT, "smooth" to Curve.SMOOTH))
            (a["curves"] as Map<String, Map<String, Any?>>?)?.forEach { (name, c) ->
                val bezier = c["bezier"] as List<Double>?
                val spring = c["spring"] as Map<String, Any?>?
                curves[name] = when {
                    bezier != null -> Curve.bezier(bezier[0], bezier[1], bezier[2], bezier[3])
                    spring != null -> Curve.spring(spring["stiffness"] as Double? ?: 200.0, spring["damping"] as Double? ?: 20.0, spring["mass"] as Double? ?: 1.0)
                    else -> return@forEach
                }
            }
            fun curveNamed(name: String?): Curve? = name?.let { curves[it] ?: null.also { Settings.configLog.warn("animations: there is no curve \"$name\" (there are: ${curves.keys.sorted().joinToString()})") } }
            // An animation: its style alone, or { style, millis, curve }.
            fun <T> animation(value: Any?, style: (String) -> T, lasting: T.(Long) -> T, curved: T.(Curve) -> T): T? = when (value) {
                is String -> style(value)
                is Map<*, *> -> {
                    var made = style(value["style"] as String? ?: "default")
                    (value["millis"] as Int?)?.let { made = made.lasting(it.toLong()) }
                    curveNamed(value["curve"] as String?)?.let { made = made.curved(it) }
                    made
                }
                else -> null
            }
            fun window(value: Any?, usual: WindowAnimation) = animation(value, { if (it == "default") usual else SettingsApplyLooks.windowAnimation(it) }, WindowAnimation::lasting, WindowAnimation::curved) ?: usual
            fun workspace(value: Any?, usual: WorkspaceAnimation) = animation(value, {
                when (it) {
                    "slide" -> WorkspaceAnimation.SLIDE
                    "slide-vertical" -> WorkspaceAnimation.SLIDE_VERTICAL
                    "fade" -> WorkspaceAnimation.FADE
                    "none" -> WorkspaceAnimation.NONE
                    else -> usual
                }
            }, WorkspaceAnimation::lasting, WorkspaceAnimation::curved) ?: usual
            // Settings by name: true/false, a look, or { enabled, millis or speed, curve, style }.
            var entries = base.entries
            for (node in dev.caulis.core.AnimationTree.CAULIS.nodes) {
                val value = a[node.name] ?: continue
                val given = value as? Map<String, Any?>
                var style = (value as? String ?: given?.get("style") as String?)?.takeIf { it != "default" }
                style?.let { dev.caulis.core.Animations.styleProblem(node.name, it) }?.let { problem ->
                    Settings.configLog.warn("animations: $problem (its usual look is used)")
                    style = null
                }
                val entry = dev.caulis.core.AnimationEntry(
                    enabled = value as? Boolean ?: given?.get("enabled") as Boolean?,
                    millis = (given?.get("millis") as Int?)?.toLong() ?: (given?.get("speed") as Double?)?.let { Math.round(it * 100) },
                    curve = curveNamed(given?.get("curve") as String?),
                    style = style,
                )
                entries = entries + (node.name to (entries[node.name]?.and(entry) ?: entry))
            }
            Settings.configLog.trace { "animations: ${if ((a["enabled"] as Boolean?) ?: base.enabled) "on" else "off"}, ${entries.size} set by name" }
            caulis.animations = base.copy(
                enabled = (a["enabled"] as Boolean?) ?: base.enabled,
                entries = entries,
                windowOpen = window(a["windowOpen"], base.windowOpen),
                windowClose = window(a["windowClose"], base.windowClose),
                windowMove = animation(a["windowMove"], { if (it == "none") WindowAnimation.NONE else if (it == "default") base.windowMove else WindowAnimation.move() }, WindowAnimation::lasting, WindowAnimation::curved) ?: base.windowMove,
                workspaceSwitch = workspace(a["workspaceSwitch"], base.workspaceSwitch),
                curve = curveNamed(a["curve"] as String?) ?: base.curve,
                overviewMillis = (a["overviewMillis"] as Int?)?.toLong() ?: base.overviewMillis,
                scaleChangeMillis = (a["scaleChangeMillis"] as Int?)?.toLong() ?: base.scaleChangeMillis,
            )
        }
        section("background")?.let { b ->
            (b["changeMillis"] as Int?)?.let { caulis.backgroundChangeMillis = it.toLong() }
            (b["fpsLimit"] as Int?)?.let { caulis.wallpaperFpsLimit = it }
            (b["paused"] as Boolean?)?.let { caulis.wallpaperShadersPaused = it }
            SettingsApplyLooks.background(caulis, b)?.let { caulis.background = it }
        }
        section("notices")?.let { n -> caulis.noticeStyle = SettingsApplyLooks.notices(caulis.noticeStyle, n) }
        section("gestures")?.let { g ->
            (g["dragViewFingers"] as Int?)?.let { caulis.dragViewFingers = it }
            (g["overviewPinchFingers"] as Int?)?.let { caulis.overviewPinchFingers = it }
            (g["workspaceSwipeFingers"] as Int?)?.let { caulis.workspaceSwipeFingers = it }
            (g["swipeDistance"] as Double?)?.let { caulis.swipeDistance = it }
        }
        (values["overviewFill"] as Double?)?.let { caulis.overviewFill = it }
        (values["resizeBorder"] as Int?)?.let { caulis.resizeBorder = it }
        (values["doubleClickMillis"] as Int?)?.let { caulis.doubleClickMillis = it.toLong() }
        section("appearance")?.let { a ->
            (a["colorScheme"] as String?)?.let { caulis.colorScheme = dev.caulis.core.appearance.ColorScheme.valueOf(it.uppercase()) }
            val darkFrom = a["darkFrom"] as java.time.LocalTime?
            val lightFrom = a["lightFrom"] as java.time.LocalTime?
            val latitude = a["latitude"] as Double?
            val longitude = a["longitude"] as Double?
            when {
                darkFrom != null && lightFrom != null && darkFrom != lightFrom -> caulis.colorSchedule = dev.caulis.core.appearance.ColorSchedule.Times(darkFrom, lightFrom)
                darkFrom != null || lightFrom != null -> Settings.configLog.warn("appearance: darkFrom and lightFrom go together (and differ)")
                latitude != null && longitude != null -> caulis.colorSchedule = dev.caulis.core.appearance.ColorSchedule.Sun(latitude, longitude)
                latitude != null || longitude != null -> Settings.configLog.warn("appearance: latitude and longitude go together")
            }
            (a["accentColor"] as Color?)?.let { caulis.accentColor = it }
            val light = a["gtkLightTheme"] as String?
            val dark = a["gtkDarkTheme"] as String?
            if (light != null && dark != null) caulis.gtkThemes = light to dark else if (light != null || dark != null) Settings.configLog.warn("appearance: gtkLightTheme and gtkDarkTheme go together")
            (a["toPrograms"] as Boolean?)?.let { caulis.appearanceToPrograms = it }
        }
        (values["hideCursorWhileTyping"] as Boolean?)?.let { caulis.hideCursorWhileTyping = it }
        (values["hideCursorAfterSeconds"] as Int?)?.let { caulis.hideCursorAfterSeconds = it }
        (values["lockColor"] as Color?)?.let { caulis.lockColor = it }
        (values["backgroundColor"] as Color?)?.let { caulis.backgroundColor = it }
        (values["focusStealing"] as String?)?.let { caulis.focusStealing = dev.caulis.wm.FocusStealing.valueOf(it.uppercase().replace('-', '_')) }
        (values["unlockFocus"] as String?)?.let { caulis.unlockFocus = dev.caulis.core.UnlockFocus.valueOf(it.uppercase().replace('-', '_')) }
        (values["idleInhibitors"] as Boolean?)?.let { caulis.idleInhibitors = it }
        (values["idleInhibitorsWhileLocked"] as Boolean?)?.let { caulis.idleInhibitorsWhileLocked = it }
        (values["maxForwardedStringBytes"] as Int?)?.let { caulis.maxForwardedStringBytes = it }
        (values["maxRegionParts"] as Int?)?.let { caulis.maxRegionParts = it }
        (values["windowListPrograms"] as List<String>?)?.let { caulis.windowListPrograms = LinkedHashSet(it) }
        section("permissions")?.let { p ->
            // (The file's rules replace those of before: a new version of the file replaces them.)
            val rules = p["rules"] as List<Map<String, Any?>>?
            val defaults = p["defaults"] as Map<String, String>?
            caulis.permissions.clear()
            rules?.forEach { r -> caulis.permissions.rule(r["program"] as String, r["feature"] as String, r["mode"] as String) }
            defaults?.forEach { (feature, mode) -> caulis.permissions.default(feature, mode) }
        }
        (values["primarySelection"] as Boolean?)?.let { caulis.primarySelection = it }
        (values["adaptiveSync"] as Boolean?)?.let { caulis.adaptiveSync = it }
        (values["bindingModifiers"] as String?)?.let { caulis.bindingModifiers = dev.caulis.wm.BindingModifiers.valueOf(it.uppercase()) }
        (values["bindingsUseFirstLatinLayout"] as Boolean?)?.let { caulis.bindingsUseFirstLatinLayout = it }
        section("xwaylandRestart")?.let { x ->
            (x["millis"] as Int?)?.let { caulis.xwaylandRestartMillis = it.toLong() }
            (x["maxMillis"] as Int?)?.let { caulis.xwaylandRestartMaxMillis = it.toLong() }
            (x["maxFailedStarts"] as Int?)?.let { caulis.xwaylandMaxFailedStarts = it }
        }
        (values["startup"] as List<String>?)?.forEach { command ->
            val action: Caulis.() -> Unit = { launch(command) }
            caulis.onStart(action)
            startup += action
        }
        section("autostart")?.let { a -> autostart(caulis.autostart, a) }
        section("plugins")?.let { p -> applyPlugins(caulis.plugins, p) }
        section("reveal")?.let { r ->
            val reveal = caulis.reveal
            (r["enabled"] as Boolean?)?.let { reveal.enabled = it }
            (r["fadeMillis"] as Int?)?.let { reveal.fadeMillis = it.toLong() }
            (r["maxWaitMillis"] as Int?)?.let { reveal.maxWaitMillis = it.toLong() }
            (r["color"] as Color?)?.let { reveal.color = it }
            (r["warmFrames"] as Int?)?.let { reveal.warmFrames = it }
            (r["hideCursor"] as Boolean?)?.let { reveal.hideCursor = it }
        }
        section("startupSound")?.let { o ->
            val sound = caulis.startupSound
            (o["enabled"] as Boolean?)?.let { sound.enabled = it }
            (o["preset"] as String?)?.let { sound.preset = it.lowercase() }
            (o["file"] as String?)?.let { sound.file = it }
            (o["volume"] as Double?)?.let { sound.volume = it }
            (o["delayMillis"] as Int?)?.let { sound.delayMillis = it.toLong() }
            (o["at"] as String?)?.let { sound.at = dev.caulis.wm.SoundMoment.valueOf(it.uppercase()) }
            (o["whenHeadless"] as Boolean?)?.let { sound.whenHeadless = it }
            (o["player"] as List<String>?)?.let { sound.player = it }
            (o["players"] as List<String>?)?.let { sound.players.clear(); sound.players += it }
            (o["playerTimeoutMillis"] as Int?)?.let { sound.playerTimeoutMillis = it.toLong() }
        }
        (values["logLevel"] as Int?)?.let { caulis.logLevel = it }
        (values["logFileLimitMb"] as Int?)?.let { caulis.logFileLimitBytes = it * 1024L * 1024L }
        (values["logFileKeep"] as Int?)?.let { caulis.logFileKeep = it }
        (values["logFileLevel"] as Int?)?.let { caulis.logFileLevel = it }
        (values["crashReports"] as Boolean?)?.let { caulis.crashReports = it }
        (values["crashReportLines"] as Int?)?.let { caulis.crashReportLines = it }
        (values["crashReportsKept"] as Int?)?.let { caulis.crashReportsKept = it }
        return Settings.Added(rules, bindings, startup)
    }

    // The autostart section onto [into]: its settings, its entries added.
    @Suppress("UNCHECKED_CAST")
    private fun applyPlugins(into: dev.caulis.wm.plugins.Plugins, p: Map<String, Any?>) {
        (p["enabled"] as Boolean?)?.let { into.enabled = it }
        (p["directories"] as List<String>?)?.let { list -> into.directories = list.map { java.nio.file.Path.of(it.replaceFirst("~", System.getProperty("user.home"))) } }
        (p["disabled"] as List<String>?)?.let { into.disabled = it.toSet() }
        (p["watch"] as Boolean?)?.let { into.watch = it }
        (p["settleMillis"] as Int?)?.let { into.settleMillis = it.toLong() }
        (p["failuresBeforeDisable"] as Int?)?.let { into.failuresBeforeDisable = it }
        (p["hangReportMillis"] as Int?)?.let { into.hangReportMillis = it.toLong() }
        (p["hangInterruptMillis"] as Int?)?.let { into.hangInterruptMillis = it.toLong() }
        (p["hangQuarantineMillis"] as Int?)?.let { into.hangQuarantineMillis = it.toLong() }
        (p["disableAfterHang"] as Boolean?)?.let { into.disableAfterHang = it }
        (p["leakCheckMillis"] as Int?)?.let { into.leakCheckMillis = it.toLong() }
        (p["maxLeakedGenerations"] as Int?)?.let { into.maxLeakedGenerations = it }
        (p["allowUnsafePermissions"] as Boolean?)?.let { into.allowUnsafePermissions = it }
        (p["stateFile"] as String?)?.let { into.stateFile = java.nio.file.Path.of(it.replaceFirst("~", System.getProperty("user.home"))) }
        (p["settings"] as Map<String, Map<String, Any?>>?)?.let { into.settings.putAll(it) }
        (p["allow"] as Map<String, List<String>>?)?.let { all -> all.forEach { (id, rights) -> into.allow[id] = rights.toSet() } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun autostart(into: dev.caulis.wm.Autostart, a: Map<String, Any?>) {
        (a["staggerMillis"] as Int?)?.let { into.staggerMillis = it.toLong() }
        (a["afterReveal"] as Boolean?)?.let { into.afterReveal = it }
        (a["desktopEntries"] as Boolean?)?.let { into.desktopEntries = it }
        (a["afterTimeoutMillis"] as Int?)?.let { into.afterTimeoutMillis = it.toLong() }
        (a["entries"] as List<Map<String, Any?>>?)?.forEachIndexed { i, e ->
            val app = when (val value = e["app"]) {
                is String -> value.trim().split(Regex("\\s+"))
                is List<*> -> value.map { it.toString() }
                else -> null
            }
            val kinds = listOfNotNull(app, e["script"], e["shell"])
            require(kinds.size == 1) { "autostart entry ${i + 1}: one of app, script or shell" }
            val entry = when {
                app != null -> into.app(app)
                e["script"] != null -> into.script(e["script"] as String, *((e["arguments"] as List<String>?).orEmpty().toTypedArray()))
                else -> into.shell(e["shell"] as String)
            }
            (e["name"] as String?)?.let { entry.name = it }
            (e["delayMillis"] as Int?)?.let { entry.delayMillis = it.toLong() }
            (e["ifNotRunning"] as Boolean?)?.let { entry.ifNotRunning = it }
            e["restart"]?.let { entry.restart = restart(it) }
            (e["after"] as List<String>?)?.forEach { entry.after(it) }
            (e["env"] as Map<String, String>?)?.forEach { (k, v) -> entry.env(k, v) }
            (e["dir"] as String?)?.let { entry.dir = it }
        }
    }

    // A restart rule: "on-crash", or { when, maxTimes, withinMillis }.
    private fun restart(value: Any): dev.caulis.wm.Restart {
        val r = value as? Map<*, *>
        val kind = (if (r != null) r["when"] as String? ?: "on-crash" else value as String).lowercase().replace('_', '-')
        val times = r?.get("maxTimes") as Int? ?: 3
        val within = (r?.get("withinMillis") as Int?)?.toLong() ?: 60_000L
        return when (kind) {
            "never" -> dev.caulis.wm.Restart.NEVER
            "always" -> dev.caulis.wm.Restart.always(times, within)
            else -> dev.caulis.wm.Restart.onCrash(times, within)
        }
    }

    private fun device(d: Map<String, Any?>, into: dev.caulis.core.input.DeviceSettings) {
        (d["enabled"] as Boolean?)?.let { into.enabled = it }
        (d["offWithMouse"] as Boolean?)?.let { into.offWithMouse = it }
        (d["speed"] as Double?)?.let { into.speed = it }
        (d["accelProfile"] as String?)?.let { into.accelProfile = dev.caulis.core.input.AccelProfile.valueOf(it.uppercase()) }
        (d["naturalScroll"] as Boolean?)?.let { into.naturalScroll = it }
        (d["scrollFactor"] as Double?)?.let { into.scrollFactor = it }
        (d["scrollMethod"] as String?)?.let { into.scrollMethod = dev.caulis.core.input.ScrollMethod.valueOf(it.uppercase().replace('-', '_')) }
        (d["scrollButton"] as String?)?.let { into.scrollButton = mouseButton(it) }
        (d["leftHanded"] as Boolean?)?.let { into.leftHanded = it }
        (d["middleEmulation"] as Boolean?)?.let { into.middleEmulation = it }
        (d["tap"] as Boolean?)?.let { into.tap = it }
        (d["tapButtons"] as String?)?.let { into.tapButtons = dev.caulis.core.input.TapButtons.valueOf(it.uppercase().replace('-', '_')) }
        (d["tapDrag"] as Boolean?)?.let { into.tapDrag = it }
        (d["dragLock"] as Boolean?)?.let { into.dragLock = it }
        (d["disableWhileTyping"] as Boolean?)?.let { into.disableWhileTyping = it }
        (d["clickMethod"] as String?)?.let { into.clickMethod = dev.caulis.core.input.ClickMethod.valueOf(it.uppercase()) }
        (d["monitor"] as String?)?.let { into.monitor = it }
        (d["keepAspect"] as Boolean?)?.let { into.keepAspect = it }
        (d["area"] as Map<*, *>?)?.let { a ->
            val x = a["x"] as Double? ?: 0.0
            val y = a["y"] as Double? ?: 0.0
            try {
                into.area = dev.caulis.core.input.TabletArea(x, y, a["width"] as Double? ?: (1.0 - x), a["height"] as Double? ?: (1.0 - y))
            } catch (e: IllegalArgumentException) {
                Settings.configLog.warn("input: ${e.message}")
            }
        }
        (d["pointerOnly"] as Boolean?)?.let { into.pointerOnly = it }
        (d["tipPressure"] as Double?)?.let { into.tipPressure = it }
        (d["lowerButton"] as String?)?.let { into.lowerButton = mouseButton(it, none = true) }
        (d["upperButton"] as String?)?.let { into.upperButton = mouseButton(it, none = true) }
        (d["thirdButton"] as String?)?.let { into.thirdButton = mouseButton(it, none = true) }
    }

    // A mouse button by its name ("MouseRight", "mouse:274"); with [none], "none" is 0: no button.
    private fun mouseButton(name: String, none: Boolean = false): Int? {
        if (none && name.equals("none", ignoreCase = true)) return 0
        val code = KeyBindings.keyCode(name) ?: Regex("(?i)mouse:(\\d+)").matchEntire(name)?.groupValues?.get(1)?.toInt()
        if (code == null) Settings.configLog.warn("input: \"$name\" is not a mouse button (\"MouseRight\", \"mouse:274\"${if (none) ", \"none\"" else ""})")
        return code
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
        val usual = Layouts.Options()
        val options = Layouts.Options(
            masters = l["masters"] as Int? ?: usual.masters,
            newWindow = (l["newWindow"] as String?)?.let { NewWindow.valueOf(it.uppercase().replace('-', '_')) } ?: usual.newWindow,
            splitRatio = l["splitRatio"] as Double? ?: usual.splitRatio,
            columnWidths = l["columnWidths"] as List<Double>? ?: usual.columnWidths,
            newColumnWidth = l["newColumnWidth"] as Double? ?: usual.newColumnWidth,
            centerFocused = l["centerFocused"] as Boolean? ?: usual.centerFocused,
        )
        return Layouts.byName(l["name"] as String? ?: "dwindle", gaps, l["ratio"] as Double?, side, options)()
    }
}
