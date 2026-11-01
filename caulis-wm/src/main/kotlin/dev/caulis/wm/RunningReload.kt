package dev.caulis.wm

import dev.caulis.core.rethrowIfFatal
import dev.caulis.core.NoticeLevel
import dev.caulis.tiling.RememberingLayout

/**
 * Taking on a new configuration: following its file, reloading, what is kept through reloads.
 */
internal class RunningReload(private val running: Running) {

    // Keys and listeners added in onStart (or by a replaced configuration), by the index of
    // their list in Caulis.addableLists: each new configuration gets them too.
    internal val keptItems = ArrayList<Pair<Int, Any?>>()

    /** Keeps [item] (in list [index] of Caulis.addableLists) in every configuration from now on. */
    fun keepThroughReloads(index: Int, item: Any?) {
        keptItems += index to item
        running.log.trace { "Kept through reloads: ${item?.javaClass?.simpleName}" }
    }

    /** No longer keeps [item] (it was taken away). */
    fun forget(item: Any?) {
        keptItems.removeIf { it.second === item }
    }

    // config.json saved again: main() runs once more (in this program, as it is) and hands over
    // its configuration with the file on top; that one is taken on. Each file is followed where
    // the user's own would be and in the system's folders ($XDG_CONFIG_DIRS, /etc/xdg: where
    // the NixOS module puts it) — made, saved or taken away.
    internal fun followConfigFile() {
        for (name in listOf("config.json") + dev.caulis.wm.config.ConfigFile.languageFileNames()) {
            for (path in dev.caulis.wm.config.ConfigFile.places(running.config, name)) followConfigFile(path)
        }
        running.log.trace { "Following the configuration files in ${dev.caulis.wm.config.ConfigFile.places(running.config, "config.json").joinToString { it.parent.toString() }}" }
    }

    // Takes the configuration on again when [path] changes (made, saved, taken away).
    internal fun followConfigFile(path: java.nio.file.Path) {
        configWatches += dev.caulis.wm.config.ConfigWatch(path) {
            // main() (and with it config.lua, config.kts: compiling takes seconds) runs on a
            // thread of its own: the desktop goes on meanwhile. One reload at a time.
            configReloads.execute {
                val next = try {
                    Reloader.rerunMain()
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError) throw e
                    running.log.warn("The configuration could not be read again: ${e.message ?: e}")
                    null
                }
                running.loop.post { takeOnConfig(next, path) }
            }
        }
    }

    // On the compositor's thread: [next] (or the files once more onto the configuration as it
    // is, when main() cannot be run again) replaces the configuration — or, if that fails, the
    // one before keeps running.
    private fun takeOnConfig(next: Caulis?, path: java.nio.file.Path) {
        val before = running.config
        try {
            if (next == null) {
                // The files once more onto a copy of what the code said alone: a setting taken
                // out of them goes back to the code's (without the copy: onto this one as it is).
                val fresh = running.config.codeOnly?.let { code -> code.snapshot().also { it.codeOnly = code } } ?: running.config
                running.log.info { "main() cannot be run again: ${path.fileName} applied onto ${if (fresh === running.config) "the configuration as it is" else "what the code says"}" }
                fresh.configProblems = dev.caulis.wm.config.ConfigFile.applyTo(fresh)
                reconfigure(fresh)
            } else {
                reconfigure(next)
            }
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            running.log.warn("Taking on ${path.fileName} failed, back to the one before: $e")
            runCatching { reconfigure(before) }.onFailure { running.log.error("Going back to the configuration before failed too: $it", it) }
            running.desktop.notify("$e", NoticeLevel.WARNING, "Your configuration failed — the one before keeps running")
            return
        }
        running.log.info { "${path.fileName} taken on" }
        running.desktop.notify(path.fileName.toString(), NoticeLevel.INFO, "Configuration reloaded")
    }

    // Config files followed, and the thread that reads them again.
    internal val configWatches = ArrayList<AutoCloseable>()

    internal val configReloads = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "caulis-config-reload").apply { isDaemon = true }
    }

    /**
     * Runs with [next] from now on (the configuration was reloaded): its keys, rules, events,
     * looks and scale; windows and programs stay. Returns the settings that only change
     * after a restart, and notes on what was kept.
     */
    fun reconfigure(next: Caulis): Pair<List<String>, List<String>> {
        val before = running.config
        val notes = ArrayList<String>()
        // A layout that remembers the arrangement (trees, columns, a canvas) of the same kind
        // stays as it is: a new one would forget where your windows are.
        val shown = running.desktop.layout
        if (shown !== next.layout && shown is RememberingLayout && shown.javaClass == next.layout.javaClass && shown.name == next.layout.name) {
            next.layout = shown // (not running yet: only the setting)
            notes += "the ${shown.name} layout keeps your arrangement; its new settings apply when you switch layouts or restart"
        }
        val restartOnly = buildList {
            fun check(name: String, old: Any?, new: Any?) { if (old != new) add(name) }
            check("backend", before.backend, next.backend)
            check("xwayland", before.xwayland, next.xwayland)
            check("hardwareCursor", before.hardwareCursor, next.hardwareCursor)
            check("directScanout", before.directScanout, next.directScanout)
            check("ipc", before.ipc, next.ipc)
            check("hotReload", before.hotReload, next.hotReload)
            check("useConfigFile", before.useConfigFile, next.useConfigFile)
            check("configName", before.configName, next.configName)
            check("configDir", before.configDir, next.configDir)
            check("headlessMonitors", before.headlessMonitors, next.headlessMonitors)
            check("exportEnvironment", before.exportEnvironment, next.exportEnvironment)
            check("exitAfterSeconds", before.exitAfterSeconds, next.exitAfterSeconds)
            check("nestedIn", before.nestedIn, next.nestedIn)
            check("sharpJavaScaling", before.sharpJavaScaling, next.sharpJavaScaling)
            check("workspaceMode", before.workspaceMode, next.workspaceMode)
            check("nestedMonitors", before.nestedMonitors, next.nestedMonitors)
            check("title", before.title, next.title)
            check("appId", before.appId, next.appId)
            check("socketDirectory", before.socketDirectory, next.socketDirectory)
            check("stats", before.stats, next.stats)
            for (pad in next.scratchpads) {
                val old = before.scratchpads.firstOrNull { it.name == pad.name } ?: continue
                check("the style of scratchpad ${pad.name}", old.style, pad.style)
            }
        }
        running.configNotices.forEach(running.desktop::dismiss)
        running.configNotices.clear()
        // New rules are for windows that open from now on (or fit them later), not for those
        // already open where they fit: those stay where you have them.
        for (window in running.desktop.allWindows) {
            val facts = running.rrules.factsOf(window, next.rules)
            running.rrules.rulesApplied.getOrPut(window) { HashSet() } += next.rules.filter { it.hasPlacement && running.safely("$it", false) { it.fits(window, facts) } }.map { it.signature }
        }
        // (The same configuration when config.json was applied onto it: nothing to compare.)
        val same = before === next
        // Keys and listeners from onStart (and from replaced configurations) are the new one's too.
        if (!same) {
            val lists = next.addableLists()
            for ((index, item) in keptItems) {
                @Suppress("UNCHECKED_CAST")
                val list = lists[index] as MutableList<Any?>
                if (list.none { it === item }) list += item
                if (item is Caulis.Binding) item.owner = next
            }
        }
        before.running = null
        next.running = running
        next.session = running
        running.config = next
        next.applyLogSettings(before)
        running.rapply.applyConfig()
        if (running.desktop.layout != next.layout) running.desktop.layout = next.layout
        if (same || before.scale != next.scale || before.monitorSettings.mapValues { it.value.scale } != next.monitorSettings.mapValues { it.value.scale }) running.rmon.applyScale()
        // A turn taken out of the configuration: the monitor stands as it is again.
        for (monitor in running.desktop.monitors) {
            if (before.monitorSettings[monitor.name]?.transform != null && next.monitorSettings[monitor.name]?.transform == null) {
                running.desktop.setTransform(monitor, dev.caulis.core.Transform.NORMAL)
            }
        }
        running.rmon.applyMonitorSettings()
        running.rmon.applyCursor()
        // Margins of the new rules: windows take them at once.
        for (window in running.desktop.allWindows) {
            val margins = running.rrules.marginsOf(window)
            if (running.rrules.shownMargins.put(window, margins) != margins) running.desktop.marginsChanged(window)
        }
        // Only now that the new one runs, the one that goes hears it (to stop what it started)
        // and its timers end: a new one that fails on the way leaves the old one whole.
        if (!same) {
            before.unloadActions.forEach { action -> running.safely("onUnload") { action(before) } }
            before.timers.forEach { it.cancel() }
            before.timers.clear()
            before.added.toList().forEach { it.close() }
            before.added.clear()
            before.waitingTimers.forEach { it.close() }
        }
        next.startWaitingTimers()
        next.reloadActions.forEach { action -> running.safely("onReload") { action(next) } }
        running.rplugins.configurationChanged()
        running.ipc?.configReloaded(next.configProblems)
        return restartOnly to notes
    }
}
