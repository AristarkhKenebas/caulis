package dev.caulis.wm.plugins

import dev.caulis.core.Desktop
import dev.caulis.core.Drawing
import dev.caulis.core.DrawingLevel
import dev.caulis.core.Effects
import dev.caulis.core.LayerSurface
import dev.caulis.core.Notice
import dev.caulis.core.NoticeLevel
import dev.caulis.core.ScreenShader
import dev.caulis.core.Widget
import dev.caulis.core.Window
import dev.caulis.core.appearance.ColorScheme
import dev.caulis.core.input.Gesture
import dev.caulis.geometry.Rect
import dev.caulis.plugins.Plugin
import dev.caulis.plugins.PluginGuard
import dev.caulis.plugins.PluginHandle
import dev.caulis.plugins.PluginScope
import dev.caulis.plugins.PluginSettings
import dev.caulis.protocol.Display
import dev.caulis.protocol.Global
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.tiling.Layout
import dev.caulis.tiling.LayoutWindow
import dev.caulis.tiling.TilingModel
import dev.caulis.wm.BindOptions
import dev.caulis.wm.Caulis
import dev.caulis.wm.KeyInput
import dev.caulis.wm.PanelRule
import dev.caulis.wm.WindowRule
import dev.caulis.wm.config.Actions
import java.util.function.Consumer

/** A jar plugin of any Caulis compositor; `api = caulis:0.1` in its manifest. */
interface CaulisPlugin : Plugin<PluginApi>

/**
 * What a jar plugin gets: every hook of the compositor. All it registers here is taken away when it
 * is disabled, and all its callbacks are guarded: what one throws disables the plugin with a notice,
 * and one that holds up the compositor's thread is reported. Everything else is reachable through
 * [caulis] and [desktop], as in a configuration; what you register there yourself goes into [scope]
 * (each such call returns something closable) or stays behind.
 *
 * It all runs on the compositor's thread (from another: [post]), and survives reloads of the configuration.
 */
open class PluginApi(
    /** The plugin's own: manifest, scope, guard, log, folder. */
    val handle: PluginHandle,
    private val caulisNow: () -> Caulis,
) {
    /** The compositor's configuration of the moment (a reload replaces it: don't keep it). */
    val caulis: Caulis get() = caulisNow()

    /** The desktop. */
    val desktop: Desktop get() = caulis.desktop

    /** Takes away what was registered, when it is disabled. */
    val scope: PluginScope get() = handle.scope

    /** Runs the plugin's own code safely: every callback below goes through it. */
    val guard: PluginGuard get() = handle.guard

    /** The configuration's settings for this plugin. */
    val settings: PluginSettings get() = handle.settings

    private val running get() = caulis.liveOrNull() ?: throw IllegalStateException("the compositor is not running")

    // What a plugin registers is the session's: it does not go with the configuration on a reload.
    private fun <T> asSession(body: Caulis.() -> T): T = running.asSession { caulis.body() }

    private fun tracked(what: String, body: Caulis.() -> AutoCloseable): AutoCloseable = scope.tracked(asSession(body), what)

    // --- windows and the desktop --------------------------------------------------------------

    @JvmSynthetic fun onWindowOpened(action: (Window) -> Unit) = tracked("listener") { onWindowOpened { w -> guard.call("window opened", Unit) { action(w) } } }
    @JvmSynthetic fun onWindowClosed(action: (Window) -> Unit) = tracked("listener") { onWindowClosed { w -> guard.call("window closed", Unit) { action(w) } } }
    @JvmSynthetic fun onWindowChanged(action: (Window) -> Unit) = tracked("listener") { onWindowChanged { w -> guard.call("window changed", Unit) { action(w) } } }
    @JvmSynthetic fun onFocusChanged(action: (Window?) -> Unit) = tracked("listener") { onFocusChanged { w -> guard.call("focus changed", Unit) { action(w) } } }
    @JvmSynthetic fun onWorkspaceChanged(action: (Int) -> Unit) = tracked("listener") { onWorkspaceChanged { n -> guard.call("workspace changed", Unit) { action(n) } } }
    @JvmSynthetic fun onMonitorAdded(action: (String) -> Unit) = tracked("listener") { onMonitorAdded { m -> guard.call("monitor added", Unit) { action(m) } } }
    @JvmSynthetic fun onMonitorRemoved(action: (String) -> Unit) = tracked("listener") { onMonitorRemoved { m -> guard.call("monitor removed", Unit) { action(m) } } }
    @JvmSynthetic fun onColorSchemeChanged(action: (ColorScheme) -> Unit) = tracked("listener") { onColorSchemeChanged { s -> guard.call("colour scheme changed", Unit) { action(s) } } }
    @JvmSynthetic fun onNotice(action: (Notice) -> Unit) = tracked("listener") { onNotice { n -> guard.call("notice", Unit) { action(n) } } }
    @JvmSynthetic fun onLid(action: (closed: Boolean) -> Unit) = tracked("listener") { onLid { c -> guard.call("lid", Unit) { action(c) } } }
    @JvmSynthetic fun onReload(action: () -> Unit) = tracked("listener") { onReload { guard.call("configuration reloaded", Unit) { action() } } }
    @JvmSynthetic fun onSleep(action: () -> Unit) = tracked("listener") { onSleep { guard.call("sleep", Unit) { action() } } }
    @JvmSynthetic fun onWake(action: () -> Unit) = tracked("listener") { onWake { guard.call("wake", Unit) { action() } } }
    @JvmSynthetic fun onQuit(action: () -> Unit) = tracked("listener") { onQuit { guard.call("quit", Unit) { action() } } }

    /** The screen was locked (true) or unlocked. */
    @JvmSynthetic fun onLockChanged(action: (locked: Boolean) -> Unit): AutoCloseable =
        scope.tracked(desktop.onLockChanged { l -> guard.call("lock changed", Unit) { action(l) } }, "listener")

    // (For Java: the same with Consumer.)
    fun onWindowOpened(action: Consumer<Window>) = onWindowOpened { w: Window -> action.accept(w) }
    fun onWindowClosed(action: Consumer<Window>) = onWindowClosed { w: Window -> action.accept(w) }
    fun onFocusChanged(action: Consumer<Window?>) = onFocusChanged { w: Window? -> action.accept(w) }
    fun onWorkspaceChanged(action: Consumer<Int>) = onWorkspaceChanged { n: Int -> action.accept(n) }

    // --- input: true takes it. Not while the screen is locked. --------------------------------

    @JvmSynthetic fun onKey(handler: (KeyInput) -> Boolean) = tracked("input hook") { onKey { k -> guard.call("key", false) { handler(k) } } }
    @JvmSynthetic fun onPointerMotion(handler: (dx: Double, dy: Double) -> Boolean) = tracked("input hook") { onPointerMotion { x, y -> guard.call("pointer motion", false) { handler(x, y) } } }
    @JvmSynthetic fun onPointerButton(handler: (button: Int, pressed: Boolean) -> Boolean) = tracked("input hook") { onPointerButton { b, p -> guard.call("pointer button", false) { handler(b, p) } } }
    @JvmSynthetic fun onScroll(handler: (vertical: Boolean, value: Double, steps: Double) -> Boolean) = tracked("input hook") { onScroll { v, a, s -> guard.call("scroll", false) { handler(v, a, s) } } }
    @JvmSynthetic fun onGesture(handler: (Gesture) -> Boolean) = tracked("input hook") { onGesture { g -> guard.call("gesture", false) { handler(g) } } }

    // --- keys and actions -----------------------------------------------------------------------

    /** Runs [action] on the keys [combination] ("Super+G"); [options] as in [Caulis.bind]. */
    @JvmSynthetic @JvmOverloads
    fun bind(combination: String, options: BindOptions = BindOptions(), action: () -> Unit): AutoCloseable {
        val binding = asSession { bind(combination, options) { guard.call("the key $combination", Unit) { action() } } }
        return scope.tracked(AutoCloseable { binding.unbind() }, "key")
    }

    /** The same for Java. */
    fun bind(combination: String, options: BindOptions, action: Runnable): AutoCloseable = bind(combination, options) { action.run() }

    /**
     * An action other things can name: keys in the configuration (`plugin run <name> [argument]`), the IPC
     * socket, [run]. [run] gets the text after its name and the window it is for (null: the focused one).
     */
    @JvmSynthetic @JvmOverloads
    fun action(name: String, argument: String = "", description: String = "", run: (argument: String, window: Window?) -> Unit): AutoCloseable {
        val plugins = running.rplugins
        require(name !in plugins.actions && name !in caulis.ownActions) { "the action \"$name\" is taken" }
        Actions.addOwn(name, argument, description)
        plugins.actions[name] = Actions.Own(name, argument, description) { text, window -> guard.call("the action $name", Unit) { run(text, window) } }
        return scope.tracked(
            AutoCloseable {
                plugins.actions.remove(name)
                Actions.removeOwn(name)
            },
            "action",
        )
    }

    /** Runs [action] written as text (`run("workspace 3")`, `run("exec firefox")`). */
    @JvmOverloads
    fun run(action: String, window: Window? = null) = caulis.run(action, window)

    /** Shows a notice. */
    @JvmOverloads
    fun notify(text: String, level: NoticeLevel = NoticeLevel.INFO, title: String? = handle.manifest.name): Notice = caulis.notify(text, level, title)

    /**
     * A command for other programs: `my-compositor --plugin cmd <name> <arguments>` answers with what
     * [run] returns (newm: `newm cmd <name> <arguments>`). [run] gets the arguments as one text.
     */
    @JvmSynthetic
    fun command(name: String, run: (arguments: String) -> String?): AutoCloseable {
        val plugins = running.rplugins
        require(name !in plugins.commands) { "the command \"$name\" is taken" }
        plugins.commands[name] = { text -> guard.call("the command $name", "the plugin failed") { run(text) } }
        return scope.tracked(AutoCloseable { plugins.commands.remove(name) }, "command")
    }

    // --- timers ------------------------------------------------------------------------------------

    @JvmSynthetic fun every(millis: Long, action: () -> Unit) = tracked("timer") { every(millis) { guard.call("a timer", Unit) { action() } } }
    @JvmSynthetic fun after(millis: Long, action: () -> Unit) = tracked("timer") { after(millis) { guard.call("a timer", Unit) { action() } } }

    /** Runs [action] soon on the compositor's thread; from any thread. Nothing runs once it is disabled. */
    fun post(action: Runnable) = caulis.post { guard.call("posted work", Unit) { action.run() } }

    fun every(millis: Long, action: Runnable): AutoCloseable = every(millis) { action.run() }
    fun after(millis: Long, action: Runnable): AutoCloseable = after(millis) { action.run() }

    // --- what is drawn -------------------------------------------------------------------------------

    /** A picture of your own at [level], on every monitor (see [Drawing]). */
    fun addDrawing(level: DrawingLevel, drawing: Drawing): AutoCloseable =
        tracked("drawing") { addDrawing(level, object : Drawing {
            override fun draw(monitor: dev.caulis.core.Monitor, canvas: dev.caulis.core.OverlayCanvas) = guard.call("drawing", Unit) { drawing.draw(monitor, canvas) }
            override fun animating(now: Long) = guard.call("drawing", false) { drawing.animating(now) }
        }) }

    /** A part of the interface: takes input, the keyboard, room on the screen (see [Widget]). */
    fun addWidget(widget: Widget): AutoCloseable = tracked("widget") { addWidget(GuardedWidget(widget, guard)) }

    /** Changes the effects of some windows (corners, blur, shadow): gets what a window would have. */
    @JvmSynthetic fun effectsFor(rule: (Window, Effects) -> Effects): AutoCloseable =
        tracked("effect rule") { addHook(effectRules, { w: Window, fx: Effects -> guard.call("window effects", fx) { rule(w, fx) } }) }

    /** Effects for panels by what they call themselves; null: this rule has nothing to say. */
    @JvmSynthetic fun panelEffects(rule: (LayerSurface) -> Effects?): AutoCloseable =
        tracked("effect rule") { addHook(panelEffectRules, { l: LayerSurface -> guard.call("panel effects", null) { rule(l) } }) }

    /** A shader over the whole screen, instead of the configuration's while it is held; the plugin enabled last is shown. */
    fun screenShader(shader: ScreenShader): AutoCloseable {
        val plugins = running.rplugins
        plugins.screenShaders += shader
        desktop.screenShader = shader
        return scope.tracked(
            AutoCloseable {
                plugins.screenShaders -= shader
                desktop.screenShader = plugins.screenShaders.lastOrNull() ?: caulis.screenShader
            },
            "screen shader",
        )
    }

    // --- windows' places -----------------------------------------------------------------------------

    /** A layout of your own, chosen with the action `plugin layout <name>`, or [useLayout]. A failure arranges side by side, and disables the plugin. */
    @JvmSynthetic
    fun layout(name: String, make: () -> Layout): AutoCloseable {
        val plugins = running.rplugins
        require(name !in plugins.layouts) { "the layout \"$name\" is taken" }
        plugins.layouts[name] = { GuardedLayout(make(), guard) }
        return scope.tracked(
            AutoCloseable {
                plugins.layouts.remove(name)
                // Being in use: back to the configuration's.
                if ((desktop.layout as? GuardedLayout)?.guard === guard) {
                    val configured = caulis.layout.takeIf { (it as? GuardedLayout)?.guard !== guard } ?: dev.caulis.tiling.Dwindle()
                    caulis.layout = configured
                }
            },
            "layout",
        )
    }

    /** Arranges windows with the layout [name] of this or another plugin. */
    fun useLayout(name: String) {
        desktop.layout = (running.rplugins.layouts[name] ?: throw IllegalArgumentException("no plugin has the layout $name"))()
    }

    /** A window rule while this plugin runs. */
    @JvmSynthetic fun rule(configure: WindowRule.() -> Unit): AutoCloseable =
        caulis.addRule(configure).let { rule -> scope.tracked(AutoCloseable { caulis.removeRule(rule) }, "rule") }

    /** A panel rule while this plugin runs. */
    @JvmSynthetic fun panelRule(configure: PanelRule.() -> Unit): AutoCloseable =
        caulis.addPanelRule(configure).let { rule -> scope.tracked(AutoCloseable { caulis.removePanelRule(rule) }, "rule") }

    // --- protocols --------------------------------------------------------------------------------

    /** The Wayland display, for protocols of your own. */
    val display: Display get() = running.display

    /** A Wayland global of your own, for every program; it goes when the plugin does (its clients get an error when they use it). */
    fun <T : Resource> global(type: ResourceType<T>, version: Int, onBind: (T) -> Unit): Global {
        val global = display.addGlobal(type, version) { resource -> guard.call("a program binding a global", Unit) { onBind(resource) } }
        scope.add(AutoCloseable { display.removeGlobal(global) }, "protocol")
        return global
    }
}

// A plugin's layout: what it throws goes to its guard, and windows stand side by side.
internal class GuardedLayout(private val inner: Layout, val guard: PluginGuard) : Layout {
    private val fallback = dev.caulis.tiling.Columns()
    override val name get() = inner.name

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect> =
        guard.call("layout $name", null) { inner.arrange(windows, area, focused) }?.takeIf { it.size == windows.size }
            ?: fallback.arrange(windows, area, focused)

    // (A model of its own runs as the core runs any: what throws there is logged. A plain one comes back here.)
    override fun createModel(): TilingModel =
        guard.call("layout $name", null) { inner.createModel() }?.takeIf { !it.javaClass.name.endsWith(".StatelessModel") } ?: super.createModel()
}

// A plugin's widget: every call through its guard.
internal class GuardedWidget(private val inner: Widget, private val guard: PluginGuard) : Widget, AutoCloseable {
    override fun area(monitor: dev.caulis.core.Monitor): Rect? = guard.call("a widget", null) { inner.area(monitor) }
    override val level: DrawingLevel get() = guard.call("a widget", DrawingLevel.ON_TOP) { inner.level }
    override fun reserve(monitor: dev.caulis.core.Monitor) = guard.call("a widget", null) { inner.reserve(monitor) }
    override fun draw(monitor: dev.caulis.core.Monitor, canvas: dev.caulis.core.OverlayCanvas, area: Rect) = guard.call("a widget", Unit) { inner.draw(monitor, canvas, area) }
    override fun animating(now: Long) = guard.call("a widget", false) { inner.animating(now) }
    override fun pointerMoved(monitor: dev.caulis.core.Monitor, x: Double, y: Double) = guard.call("a widget", Unit) { inner.pointerMoved(monitor, x, y) }
    override fun pointerLeft() = guard.call("a widget", Unit) { inner.pointerLeft() }
    override fun pointerButton(button: Int, pressed: Boolean) = guard.call("a widget", Unit) { inner.pointerButton(button, pressed) }
    override fun pressedOutside(button: Int, x: Double, y: Double, onAnother: Boolean) = guard.call("a widget", false) { inner.pressedOutside(button, x, y, onAnother) }
    override fun scroll(dx: Double, dy: Double) = guard.call("a widget", Unit) { inner.scroll(dx, dy) }
    override val cursor: String get() = guard.call("a widget", "default") { inner.cursor }
    override val takesKeyboard: Boolean get() = guard.call("a widget", false) { inner.takesKeyboard }
    override fun key(key: dev.caulis.core.WidgetKey) = guard.call("a widget", Unit) { inner.key(key) }
    override fun keyboardFocus(has: Boolean) = guard.call("a widget", Unit) { inner.keyboardFocus(has) }
    // (A plugin's widget is never shown above the lock: nothing of a plugin sees or draws there.)
    override val showsWhileLocked: Boolean get() = false

    override fun close() {
        (inner as? AutoCloseable)?.close()
    }
}
