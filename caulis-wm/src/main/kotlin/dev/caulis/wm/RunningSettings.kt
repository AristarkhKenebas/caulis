package dev.caulis.wm

import dev.caulis.xwayland.Xwayland
import dev.caulis.core.input.KeyBindings

/**
 * The configuration's settings put to work: looks, the core's settings, idle watches, key repeat,
 * workspaces, scratchpads, events, key bindings.
 */
internal class RunningSettings(private val running: Running) {

    // The configuration's own idle timers (replaced on a reload).
    private val idleWatches = ArrayList<AutoCloseable>()

    private var appliedNumLock: Boolean? = null

    /** Hands the background rule over again (it was changed while running). */
    fun applyLooks() {
        running.desktop.backgroundFor = running.config.backgroundFor?.let { rule -> { monitor, workspace -> running.safely("backgroundFor", null) { rule(monitor.name, workspace) } } }
    }

    // What of the configuration is handed over to the desktop, keys and events — at the
    // start and again after each reload.
    /** Settings changed by code while running (Caulis.applySettings). */
    internal fun applySettingsNow() = applyConfig()

    // The desktop's own focus-stealing rule (focusStealing SAME_PROGRAM), kept the first time.
    // (No initializer: this is first used from init, which runs before this line would.)
    private lateinit var sameProgramRule: (dev.caulis.core.Window, dev.caulis.core.WindowRequest) -> Boolean

    /** The settings of Caulis's core parts as the configuration says (see Caulis.focusStealing, maxRegionParts, ...). */
    fun applyCoreSettings() {
        val config = running.config
        if (!::sameProgramRule.isInitialized) sameProgramRule = running.desktop.programMayTakeFocus
        running.desktop.backgroundColor = config.backgroundColor
        running.desktop.programMayTakeFocus = config.focusStealingRule?.let { rule -> { window, why -> running.safely("focusStealingRule", false) { rule(window, why) } } }
            ?: when (config.focusStealing) {
                FocusStealing.SAME_PROGRAM -> sameProgramRule
                FocusStealing.ALWAYS -> { _, _ -> true }
                FocusStealing.NEVER -> { _, _ -> false }
            }
        running.idle.inhibitorsWorkWhileLocked = config.idleInhibitorsWhileLocked
        running.idle.protocolInhibitors = config.idleInhibitors
        running.desktop.unlockFocus = config.unlockFocus
        running.display.maxForwardedStringBytes = config.maxForwardedStringBytes
        running.compositor.maxRegionParts = config.maxRegionParts
        running.xwayland?.let { x ->
            x.restartDelayMillis = config.xwaylandRestartMillis
            x.restartDelayMaxMillis = config.xwaylandRestartMaxMillis
            x.maxFailedStarts = config.xwaylandMaxFailedStarts
        }
        applyMonitorLinks?.invoke()
        running.log.trace { "Core settings: focus stealing ${config.focusStealing}, strings ${config.maxForwardedStringBytes} bytes, regions ${config.maxRegionParts} parts" }
    }

    /** Set by the screen backend: each monitor's link settings (maxBpc, adaptiveSync) as the configuration says. */
    var applyMonitorLinks: (() -> Unit)? = null

    /** Tries Xwayland again after it failed too often (the action "xwayland-retry"). */
    fun retryXwayland() {
        val x = running.xwayland ?: return running.log.info { "Xwayland is off" }
        running.log.info { "Trying Xwayland again" }
        x.retry()
    }

    internal fun applyConfig() {
        val config = running.config
        applyCoreSettings()
        dev.caulis.ffi.Processes.method = config.processStart
        running.desktop.effects = config.effects
        running.desktop.specialWorkspaces = config.specialWorkspaces
        if (running.desktop.director !== config.director) running.desktop.director = config.director
        running.display.privileged = { client ->
            // (Every program asks which features there are right as it connects: the launcher
            // learns here that one of its programs is a Wayland one.)
            running.launcher.clientConnected(client.pid)
            running.safely("privilegedClients", false) { running.config.privilegedClients(client) }
        }
        // Each privileged feature: the permission rules first, else the privileged rule.
        running.display.mayUse = { client, feature -> running.permissions.mayUse(client, feature) }
        running.desktop.screenShader = running.rplugins.screenShaders.lastOrNull() ?: config.screenShader
        running.desktop.titleBars = config.titleBars
        running.desktop.titleBarStyle = config.titleBarStyle
        running.desktop.titleBarsFor = if (config.titleBarRules.isEmpty() && config.rules.none { it.titleBars != null }) null else running.rrules::titleBarsOf
        running.desktop.noticeStyle = config.noticeStyle
        running.desktop.animationMillis = config.animationMillis
        running.desktop.animations = config.animations
        running.desktop.background = config.background
        applyLooks()
        running.desktop.backgroundChangeMillis = config.backgroundChangeMillis
        running.desktop.wallpaperShadersPaused = config.wallpaperShadersPaused
        running.desktop.wallpaperFpsLimit = config.wallpaperFpsLimit
        running.desktop.movingPaintsFps = config.movingPaintsFps
        running.desktop.overviewFill = config.overviewFill
        running.desktop.monitorEdges = config.monitorEdges
        running.desktop.resizeBorder = config.resizeBorder
        running.desktop.doubleClickMillis = config.doubleClickMillis
        running.desktop.lockColor = config.lockColor
        running.desktop.focusFollowsMouse = config.focusFollowsMouse
        running.desktop.quickCursorMoves = config.quickCursorMoves
        running.desktop.widgetThreads = config.widgetThreads
        running.desktop.waitForClientGpu = config.waitForClientGpu
        running.desktop.osdHandler = config.osdHandler
        running.activation.focusOnActivate = config.focusOnActivate
        running.activation.tokensNeedFocus = config.activationTokensNeedFocus
        running.idle.fullscreenInhibits = config.fullscreenKeepsAwake
        applyIdleWatches()
        applyKeyRepeat()
        running.rinput.applyKeymap()
        applyNumLock()
        running.rlooks.applyAppearance()
        running.inputDevices?.settings = config.input
        running.inputDevices?.ownThread = config.inputThread
        applyWorkspaces()
        running.desktop.primaryMonitorName = config.primaryMonitor
        applyScratchpads()
        rewireEvents()
        running.sessionStart.reconfigure()
    }

    /** The configuration's idle timers, made anew (they count from now). */
    fun applyIdleWatches() {
        idleWatches.forEach(AutoCloseable::close)
        idleWatches.clear()
        val config = running.config
        for (watch in config.idleWatches) {
            idleWatches += running.idle.watch(watch.seconds * 1000L, onIdle = { running.safely("onIdle") { watch.idle(config) } }, onResume = { running.safely("onIdle resume") { watch.resume(config) } })
        }
        running.log.trace { "Idle timers: ${config.idleWatches.joinToString { "${it.seconds} s" }.ifEmpty { "none" }}" }
    }

    /** Key repeat as the configuration says, for programs and for the compositor's own keys. */
    fun applyKeyRepeat() {
        running.seat.repeatRate = running.config.repeatRate
        running.seat.repeatDelay = running.config.repeatDelayMillis.toInt()
        for (bindings in running.rinput.allKeys()) {
            bindings.repeatDelay = running.config.repeatDelayMillis.toInt()
            bindings.repeatRate = running.config.repeatRate
        }
        running.keyboardLog.trace { "Key repeat: ${running.config.repeatRate} a second after ${running.config.repeatDelayMillis} ms" }
    }

    /** Num Lock as configured, when that changes (not again on each reload: the key may have switched it since). */
    fun applyNumLock() {
        val on = running.config.numLock ?: return
        if (on == appliedNumLock) return
        running.seat.setNumLock(on)
        appliedNumLock = on
        running.keyboardLog.info { "Num Lock ${if (on) "on" else "off"}" }
    }

    /** The configuration's workspaces: how many, and how each is. */
    fun applyWorkspaces() {
        running.desktop.workspaceCount = running.config.workspaceCount
        running.desktop.workspaceRules = running.config.workspaceRules
    }

    /** The configuration's scratchpads: those not there yet made, and their keys bound. */
    fun applyScratchpads() {
        for (pad in running.config.scratchpads) {
            // A scratchpad asks the configuration of the moment which windows are its own.
            if (running.desktop.scratchpad(pad.name) == null) {
                running.desktop.addScratchpad(pad.name, pad.style) { window ->
                    val now = running.config.scratchpads.firstOrNull { it.name == pad.name } ?: return@addScratchpad false
                    running.safely("scratchpad ${pad.name}", false) { now.match(window) }
                }
                running.log.info { "Scratchpad ${pad.name}" }
            }
        }
        rebindKeys()
    }

    /** Hands the configuration's event listeners to the desktop anew (after a reload, or one added or taken away). */
    fun rewireEvents() {
        running.configListeners.forEach(AutoCloseable::close)
        running.configListeners.clear()
        val config = running.config
        config.noticeListeners.forEach { action -> running.configListeners += running.desktop.onNotice { notice -> running.safely("onNotice") { action(config, notice) } } }
        config.windowOpened.forEach { action -> running.configListeners += running.desktop.onWindowOpened { window -> action(config, window) } }
        config.windowClosed.forEach { action -> running.configListeners += running.desktop.onWindowClosed { window -> action(config, window) } }
        config.windowChanged.forEach { action -> running.configListeners += running.desktop.onWindowInfoChanged { window -> running.safely("onWindowChanged") { action(config, window) } } }
        config.focusChanged.forEach { action -> running.configListeners += running.desktop.onFocusChanged { window -> action(config, window) } }
        config.workspaceChanged.forEach { action -> running.configListeners += running.desktop.onWorkspaceChanged { workspace -> action(config, workspace.number) } }
    }

    /**
     * Binds every key anew: the usual ones, yours (those switched on), scratchpads', quitting
     * and consoles — at the start, after a reload, and when one of yours is switched on or off.
     */
    fun rebindKeys() {
        running.keys.clear()
        running.lockedKeys.clear()
        running.inhibitedKeys.clear()
        bindKeys()
        for (pad in running.config.scratchpads) pad.key?.let { key -> running.keys.bind(key) { running.toggleScratchpad(pad.name) } }
        // Text consoles also work while the screen is locked (when they are on); quitting only
        // when keys.quitWhileLocked says so (else it would get round the lock).
        val usual = running.config.keys
        for (bindings in listOf(running.keys, running.lockedKeys, running.inhibitedKeys)) {
            if (bindings !== running.lockedKeys || usual.quitWhileLocked) usual.quit?.let { bindings.bind(it) { running.quit() } }
            if (running.switchConsole != null) usual.consoles?.let { modifiers -> for (n in 1..12) bindings.bind("$modifiers+F$n") { running.switchConsole.invoke(n) } }
        }
        usual.restoreShortcuts?.let { running.inhibitedKeys.bind(it) { running.shortcutsInhibit.restore() } }
        if (usual.quit == null && (usual.consoles == null || running.switchConsole == null)) {
            running.log.warn("No key quits and none switches to a text console (keys.quit and keys.consoles are off): if a program takes the whole screen, only another computer (ssh) or the power button gets you out")
        }
        // Not one key of ours at all (every usual key off, none of your own): a way out and a
        // terminal anyway, so a broken configuration never locks you in.
        if (usual.bindings(running.config.workspaceCount).isEmpty() && running.config.bindings.none { it.enabled } && running.config.scratchpads.none { it.key != null }) {
            running.keys.bind(EMERGENCY_QUIT) { running.quit() }
            running.keys.bind(EMERGENCY_TERMINAL) { running.safely("the emergency terminal") { running.config.openTerminal() } }
            running.log.warn("Your configuration has no keys at all: $EMERGENCY_QUIT quits and $EMERGENCY_TERMINAL opens a terminal until it has some")
        }
    }

    // The usual keys first, the user's own after them (later ones win on the same combination).
    private fun bindKeys() {
        // Those you changed after the others: on the same keys, yours wins over a default one.
        val usual = running.config.keys.bindings(running.config.workspaceCount).sortedBy { running.config.keys.changed(it.first) }
        val taken = HashMap<Pair<Set<KeyBindings.Modifier>, Int>, String>()
        for ((name, combination, action) in usual) {
            val parsed = KeyBindings.parse(combination)
            taken.put(parsed, name)?.takeIf { it != name }?.let { other ->
                running.log.warn("keys.$name and keys.$other are both $combination: keys.$name wins")
            }
            val options = KeyBindings.Options(repeat = running.config.keys.repeats(name))
            val run = { running.safely(combination) { action(running.config) } }
            running.keys.bind(combination, options, run)
            if (running.config.keys.whileLocked(name)) running.lockedKeys.bind(combination, options, run)
            // (A program that keeps the keys for itself never keeps the screen from locking.)
            if (name == "lock" && running.config.keys.lockWhileInhibited) running.inhibitedKeys.bind(combination, options, run)
        }
        running.modeKeys.clear()
        for (name in running.config.modeNames) running.modeKeys[name] = KeyBindings()
        for (binding in running.config.bindings) {
            if (!binding.enabled) continue
            val combination = binding.combination
            val run = { running.safely(combination) { binding.action(running.config) } }
            val mode = binding.keyMode
            if (mode != null) {
                running.modeKeys.getOrPut(mode) { KeyBindings() }.bind(combination, binding.options, run)
                continue
            }
            KeyBindings.parseAllowingTap(combination).takeIf { it.second != null }?.let { parsed ->
                taken[parsed.first to parsed.second!!]?.let { name -> running.log.info { "bind(\"$combination\") replaces keys.$name" } }
            }
            running.keys.bind(combination, binding.options, run)
            if (binding.options.whileLocked) running.lockedKeys.bind(combination, binding.options, run)
        }
        // Quitting and consoles in every key mode too.
        val always = running.config.keys
        for (bindings in running.modeKeys.values) {
            always.quit?.let { bindings.bind(it) { running.quit() } }
            if (running.switchConsole != null) always.consoles?.let { modifiers -> for (n in 1..12) bindings.bind("$modifiers+F$n") { running.switchConsole.invoke(n) } }
        }
        // A mode that is gone after a reload is left.
        if (running.keyMode != null && running.keyMode !in running.modeKeys) running.rinput.enterMode(null)
        // Combinations that repeat use the loop and the key repeat of the moment.
        // Which keys are modifiers: as the keymap says (caps:super, altwin:swap_lalt_lwin), or the physical ones.
        val modifierOf: (Int) -> KeyBindings.Modifier? =
            if (running.config.bindingModifiers == BindingModifiers.XKB) running.seat::modifierOfKey else { code -> KeyBindings.Modifier.of(code) }
        // Which key a combination's letter is: as the layout says (the first Latin one for Russian), or the physical key.
        val keyOf: (Int) -> Int = if (running.config.bindingsUseFirstLatinLayout) running.seat::bindingKeyOf else { code -> code }
        running.keyboardLog.trace { "Bindings by ${if (running.config.bindingsUseFirstLatinLayout) "the layout (the first Latin one for other scripts)" else "the physical keys"}" }
        for (bindings in running.rinput.allKeys()) {
            bindings.modifierOf = modifierOf
            bindings.keyOf = keyOf
            bindings.schedule = { millis, task -> running.loop.after(millis) { task() }.let { timer -> AutoCloseable { timer.cancel() } } }
            bindings.repeatDelay = running.config.repeatDelayMillis.toInt()
            bindings.repeatRate = running.config.repeatRate
        }
    }
}
