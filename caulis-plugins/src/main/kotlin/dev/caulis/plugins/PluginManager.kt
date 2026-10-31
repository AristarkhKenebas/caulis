package dev.caulis.plugins

import dev.caulis.core.rethrowIfFatal
import dev.caulis.log.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Runs a compositor's plugins: finds them in folders, orders them by what they depend on, loads,
 * enables, disables, unloads and hot-reloads them. Everything but reading files happens on the
 * compositor's thread, where all its functions must be called.
 *
 * Whatever a plugin does wrong stays its own problem: a throw disables it with a notice, a hang is
 * detected and reported, a new file that does not work leaves the old one running. With no plugin
 * in the folders, this is one object and one thread waiting in the kernel.
 */
class PluginManager<H : Any>(
    private val host: PluginHost<H>,
    options: PluginOptions,
    formats: List<PluginFormat<H>> = emptyList(),
) : AutoCloseable {
    private val log = Logger("plugins")

    /** The settings at work (see [configure]). */
    var options: PluginOptions = options
        private set

    private val formats: List<PluginFormat<H>> = formats + JarPluginFormat()

    private val keeper = PluginKeeper(options.stateFile, log)
    private val entries = LinkedHashMap<String, Entry<H>>()
    private val broken = LinkedHashMap<Path, String>()
    private var leakedGenerations = 0
    private var closed = false

    // Where the copies of jars in use are: a private temporary folder, made when first needed.
    private var temporaryCache: Path? = null
    private val cacheDir: Path
        get() = options.cacheDir ?: temporaryCache ?: Files.createTempDirectory("caulis-plugins-").also { temporaryCache = it }

    private val watchdog = PluginWatchdog(
        options,
        log,
        reportHang = { id, what, millis, stack ->
            log.error("Plugin $id holds up the compositor: its call ($what) has run for $millis ms. Where it is:\n$stack")
            host.post { host.notice(true, "Plugin $id froze the desktop", "$what took more than $millis ms") }
        },
        quarantine = { id ->
            log.error("Plugin $id still has not returned: it stays off at the next start")
            keeper.quarantine(id)
        },
        reportLeak = { id, name ->
            leakedGenerations++
            log.warn("Plugin $id: its class loader $name was not collected after it was unloaded (something still refers to it: a thread, a static field of a library, a listener not registered through its API). $leakedGenerations left behind.")
        },
    )

    // Reading files: a thread that goes away when idle, so none is there with nothing to do.
    private val reader = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "caulis-plugin-reader").apply { isDaemon = true }
    }

    private val scanner = PluginScanner(this.formats, host.apiName)
    private var watch: DirectoryWatch? = null
    private var scanWaiting = false

    private class Entry<H : Any>(var source: PluginSource<H>, var formatName: String, var fingerprint: String) {
        val id get() = source.manifest.id
        var state = PluginState.LOADED
        var problem: String? = null
        var code: PluginCode<H>? = null
        var live: Live<H>? = null
        var generation = 0
        var failures = 0
        var nextSource: PluginSource<H>? = null

        // Written down by a person: don't start it by itself.
        var offByHand = false
        var quarantineTold = false
    }

    private class Live<H : Any>(val plugin: Plugin<H>, val handle: PluginHandle)

    /** Starts: reads the folders away from the compositor's thread, and follows them. Call on the compositor's thread. */
    fun start() {
        watchdog.watch(Thread.currentThread())
        watchdog.onHungCallDone = { guard, what, millis -> hungCallDone(guard, what, millis) }
        if (!options.enabled) {
            log.info { "Plugins are off" }
            return
        }
        log.info { "Plugins are looked for in ${options.directories.joinToString()}" }
        if (options.watch && options.directories.isNotEmpty()) {
            watch = try {
                DirectoryWatch(options.directories, log) { rescan() }
            } catch (e: java.io.IOException) {
                log.warn("Changes in the plugin folders cannot be followed: $e")
                null
            }
        }
        rescan()
    }

    /** Takes on new settings ([PluginOptions.settings], [PluginOptions.disabled]): plugins they concern change. */
    fun configure(next: PluginOptions) {
        val before = options
        options = next
        for (id in next.disabled - before.disabled) entries[id]?.let { off(it, PluginState.DISABLED, null) }
        if (before.disabled != next.disabled) rescan()
        for ((id, entry) in entries) {
            val live = entry.live ?: continue
            val settings = PluginSettings(next.settings[id].orEmpty())
            if (settings == live.handle.settings) continue
            live.handle.settings = settings
            val taken = live.handle.guard.call("taking on new settings", true) { live.plugin.settingsChanged(settings) }
            log.info { "Plugin $id: new settings${if (taken) "" else ", reloading it"}" }
            if (!taken) restart(entry)
        }
    }

    /** Reads the folders again soon; from any thread. Called by itself when they change. */
    fun rescan() {
        synchronized(this) {
            if (closed || scanWaiting) return
            scanWaiting = true
        }
        reader.execute {
            synchronized(this) { scanWaiting = false }
            try {
                val found = scanner.settled(options)
                host.post { if (!closed) takeOn(found) }
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                log.warn("Reading the plugin folders failed: $e")
            }
        }
    }

    // --- taking on what was found ----------------------------------------------------------------

    // On the compositor's thread: starts what is new, reloads what changed, unloads what went.
    private fun takeOn(found: List<PluginScanner.Found<H>>) {
        if (!host.mayChangeNow()) {
            log.info { "The plugin folders changed: waiting (nothing starts or stops now)" }
            reader.execute { runCatching { Thread.sleep(RETRY_MILLIS) }; rescan() }
            return
        }
        val problems = found.filter { it.problem != null }
        for (item in problems) {
            val said = broken.put(item.path, item.problem.orEmpty())
            if (said != item.problem) {
                log.warn("Plugin file not used: ${item.problem}")
                host.notice(true, "A plugin was not loaded", item.problem.orEmpty())
            }
        }
        broken.keys.retainAll(problems.mapTo(HashSet()) { it.path })

        val usable = LinkedHashMap<String, PluginScanner.Found<H>>()
        for (item in found) {
            val source = item.source ?: continue
            val first = usable.putIfAbsent(source.manifest.id, item)
            if (first != null && first.path != item.path) log.warn("Two plugins are called ${source.manifest.id}: ${first.path} counts, ${item.path} does not")
        }
        // A broken new file of a plugin that runs: the old one goes on (its path is not in usable, but in problems).
        val brokenPaths = problems.mapTo(HashSet()) { it.path }
        for (entry in entries.values.toList()) {
            if (entry.id !in usable && entry.source.path !in brokenPaths) remove(entry)
        }
        for ((id, item) in usable) {
            val source = checkNotNull(item.source)
            val entry = entries[id]
            when {
                entry == null -> entries[id] = Entry(source, item.format, item.fingerprint).also {
                    log.info { "Found plugin $id ${source.manifest.version} (${item.format}): ${item.path}" }
                }
                entry.fingerprint != item.fingerprint || entry.source.path != item.path -> {
                    entry.fingerprint = item.fingerprint
                    entry.nextSource = source
                    entry.formatName = item.format
                }
            }
        }
        startWhatCan()
        for (entry in entries.values.toList()) if (entry.nextSource != null) hotReload(entry)
    }

    // Everything that should run and does not, dependencies first.
    private fun startWhatCan() {
        for (entry in ordered()) {
            if (entry.live != null || entry.state == PluginState.FAILED) continue
            if (entry.nextSource != null && entry.state != PluginState.ENABLED) {
                entry.source = checkNotNull(entry.nextSource)
                entry.nextSource = null
                closeCode(entry)
            }
            val off = entry.offByHand || entry.id in options.disabled || keeper.isOff(entry.id)
            if (off) {
                entry.state = PluginState.DISABLED
                entry.problem = if (keeper.isQuarantined(entry.id)) QUARANTINE_NOTE else null
                if (keeper.isQuarantined(entry.id) && !entry.quarantineTold) {
                    entry.quarantineTold = true
                    host.notice(true, "Plugin ${entry.id} is off", "It froze the desktop last time. To try again: plugin enable ${entry.id}")
                }
                continue
            }
            refusal(entry)?.let { why ->
                if (entry.problem != why) {
                    log.warn("Plugin ${entry.id} not started: $why")
                    host.notice(true, "Plugin ${entry.id} not started", why)
                }
                entry.state = PluginState.REFUSED
                entry.problem = why
                continue
            }
            enable(entry)
        }
    }

    // Why [entry] cannot run now: another API, a plugin it needs, or null.
    private fun refusal(entry: Entry<H>): String? {
        val manifest = entry.source.manifest
        for ((name, wanted) in manifest.apis) {
            val offered = host.apis[name] ?: return "it is written for the API \"$name\", which this compositor does not have"
            if (!offered.runs(wanted)) return "it is written for $name $wanted; this compositor offers $name $offered"
        }
        if (manifest.apis.isEmpty()) return "its manifest names no API"
        for (dependency in manifest.dependencies) {
            val other = entries[dependency.id]
            if (other == null) {
                if (dependency.optional) continue
                return "it needs the plugin ${dependency.id}, which is not there"
            }
            val least = dependency.atLeast
            if (least != null && other.source.manifest.version < least) {
                if (dependency.optional) continue
                return "it needs ${dependency.id} $least or newer; ${other.source.manifest.version} is there"
            }
            if (other.live == null && !dependency.optional) return "it needs the plugin ${dependency.id}, which is not running"
        }
        return null
    }

    // The entries, each after what it depends on; those in a circle are refused by their dependency not running.
    private fun ordered(): List<Entry<H>> {
        val done = LinkedHashSet<Entry<H>>()
        val visiting = HashSet<Entry<H>>()
        fun visit(entry: Entry<H>) {
            if (entry in done || !visiting.add(entry)) return
            for (dependency in entry.source.manifest.dependencies) entries[dependency.id]?.let(::visit)
            visiting -= entry
            done += entry
        }
        entries.values.forEach(::visit)
        return done.toList()
    }

    // Those that need [entry] (directly or not), the ones farthest from it first.
    private fun dependents(entry: Entry<H>): List<Entry<H>> {
        val found = LinkedHashSet<Entry<H>>()
        fun visit(of: Entry<H>) {
            for (other in entries.values) {
                if (other !in found && other !== entry && other.source.manifest.dependencies.any { it.id == of.id }) {
                    found += other
                    visit(other)
                }
            }
        }
        visit(entry)
        val order = ordered()
        return found.sortedByDescending { order.indexOf(it) }
    }

    // --- the steps of a life -----------------------------------------------------------------------

    // Loads its code (if it is not) and starts it. True if it runs. A failure is told and it stays off.
    private fun enable(entry: Entry<H>, tell: Boolean = true): Boolean {
        val manifest = entry.source.manifest
        val logger = Logger("plugin-${manifest.id}")
        val scope = PluginScope(logger)
        val guard = PluginGuard(manifest.id, watchdog, ::failed)
        val handle = PluginHandle(manifest, scope, guard, logger, host.dataDirFor(manifest.id), PluginSettings(options.settings[manifest.id].orEmpty()))
        var watching = false
        val startedAt = System.nanoTime()
        try {
            val code = entry.code ?: load(entry)
            watchdog.startWatching()
            watching = true
            val timed = watchdog.enter(guard, "starting")
            val thread = Thread.currentThread()
            val before = thread.contextClassLoader
            // (Threads it starts inherit it: one left over is found by this, see lookForThreads.)
            code.classLoader?.let { thread.contextClassLoader = it }
            try {
                val plugin = code.newPlugin()
                val api = host.apiFor(handle)
                plugin.enable(PluginContext(handle, api))
                entry.live = Live(plugin, handle)
            } finally {
                thread.contextClassLoader = before
                if (timed) watchdog.leave()
            }
            entry.state = PluginState.ENABLED
            entry.problem = null
            entry.failures = 0
            log.info { "Plugin ${manifest.id} ${manifest.version} enabled in ${(System.nanoTime() - startedAt) / 1_000_000} ms: ${scope.held}" }
            return true
        } catch (e: Throwable) {
            guard.active = false
            scope.close()
            if (watching) watchdog.stopWatching()
            closeCode(entry)
            entry.state = PluginState.FAILED
            entry.problem = "it could not start: ${describe(e)}"
            log.error("Plugin ${manifest.id} could not start", e)
            if (tell) host.notice(true, "Plugin ${manifest.id} could not start", describe(e))
            return false
        }
    }

    private fun load(entry: Entry<H>): PluginCode<H> {
        entry.generation++
        val source = entry.source
        source.prepare(cacheDir, entry.generation)
        val loaders = source.manifest.dependencies.mapNotNull { dependency -> entries[dependency.id]?.code?.classLoader as? PluginClassLoader }
        val code = source.load(PluginLoadRequest(options.parentLoader, options.sharedPackages, loaders, entry.generation))
        entry.code = code
        log.trace { "Plugin ${entry.id}: code loaded (#${entry.generation})" }
        return code
    }

    // Stops it: it hears, everything it registered goes. Its code stays until closeCode.
    private fun stop(entry: Entry<H>) {
        val live = entry.live ?: return
        entry.live = null
        val handle = live.handle
        // (Also one that failed may save what it has; what it throws now changes nothing.)
        handle.guard.active = false
        val timed = watchdog.enter(handle.guard, "stopping")
        try {
            live.plugin.disable()
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            log.warn("Plugin ${entry.id}: its disable() failed: $e")
        } finally {
            if (timed) watchdog.leave()
        }
        val held = handle.scope.held
        handle.scope.close()
        watchdog.stopWatching()
        log.info { "Plugin ${entry.id} disabled; taken away: $held" }
    }

    // Its class loader goes: watched until it is collected.
    private fun closeCode(entry: Entry<H>) {
        val code = entry.code ?: return
        entry.code = null
        val loader = code.classLoader
        if (loader != null) lookForThreads(entry.id, loader)
        try {
            code.close()
        } catch (e: Exception) {
            log.warn("Plugin ${entry.id}: closing its code failed: $e")
        }
        if (loader != null) watchdog.checkCollected(entry.id, loader)
    }

    // A thread still running with the plugin's loader would keep it in memory, and run its code.
    private fun lookForThreads(id: String, loader: ClassLoader) {
        for (thread in Thread.getAllStackTraces().keys) {
            if (thread.contextClassLoader === loader && thread !== Thread.currentThread()) {
                log.warn("Plugin $id left the thread \"${thread.name}\" running (not started through its scope): interrupting it")
                thread.interrupt()
            }
        }
    }

    private fun remove(entry: Entry<H>) {
        for (other in dependents(entry)) off(other, PluginState.REFUSED, "it needs the plugin ${entry.id}, which is not there")
        off(entry, PluginState.DISABLED, null)
        entries.remove(entry.id)
        log.info { "Plugin ${entry.id} is gone" }
    }

    // Stopped and unloaded, in [state].
    private fun off(entry: Entry<H>, state: PluginState, problem: String?) {
        stop(entry)
        closeCode(entry)
        entry.state = state
        entry.problem = problem
    }

    // A new file: the old one stops, the new one starts. If it cannot, the old one starts again.
    private fun hotReload(entry: Entry<H>) {
        val next = entry.nextSource ?: return
        entry.nextSource = null
        if (leakedGenerations >= options.maxLeakedGenerations) {
            log.warn("Plugin ${entry.id}: not reloaded: $leakedGenerations class loaders were left behind")
            host.notice(true, "Plugin ${entry.id} not reloaded", "Too many plugins were left in memory: restart the compositor")
            return
        }
        if (entry.live == null) {
            closeCode(entry)
            entry.source = next
            entry.failures = 0
            if (entry.state == PluginState.FAILED) entry.state = PluginState.LOADED
            startWhatCan()
            return
        }
        val waiting = stopDependents(entry)
        stop(entry)
        val oldCode = entry.code
        val oldSource = entry.source
        val oldGeneration = entry.generation
        entry.code = null
        entry.source = next
        val startedAt = System.nanoTime()
        val problem = refusal(entry)
        if (problem == null && enable(entry, tell = false)) {
            closeOldCode(entry.id, oldCode)
            log.info { "Plugin ${entry.id} reloaded in ${(System.nanoTime() - startedAt) / 1_000_000} ms" }
            host.notice(false, "Plugin ${entry.id} reloaded", "${oldSource.manifest.version} → ${next.manifest.version}")
        } else {
            val why = problem ?: entry.problem.orEmpty()
            log.warn("Plugin ${entry.id}: the new file does not work ($why): the one before goes on")
            host.notice(true, "Plugin ${entry.id}: the new file does not work", "$why. The one before goes on.")
            closeCode(entry)
            entry.code = oldCode
            entry.source = oldSource
            entry.generation = oldGeneration
            entry.state = PluginState.LOADED
            enable(entry)
        }
        restartAll(waiting)
    }

    // Those that need it stop, and lose their code: their class loaders refer to its.
    private fun stopDependents(entry: Entry<H>): List<Entry<H>> {
        val running = dependents(entry).filter { it.live != null }
        for (other in running) off(other, PluginState.LOADED, null)
        return running
    }

    private fun restartAll(entries: List<Entry<H>>) {
        for (entry in entries.asReversed()) {
            refusal(entry)?.let { why ->
                entry.state = PluginState.REFUSED
                entry.problem = why
            } ?: enable(entry)
        }
    }

    private fun closeOldCode(id: String, code: PluginCode<H>?) {
        val old = code ?: return
        val loader = old.classLoader
        if (loader != null) lookForThreads(id, loader)
        runCatching { old.close() }
        if (loader != null) watchdog.checkCollected(id, loader)
    }

    private fun restart(entry: Entry<H>) {
        val waiting = stopDependents(entry)
        off(entry, PluginState.LOADED, null)
        enable(entry)
        restartAll(waiting)
    }

    // --- what goes wrong -------------------------------------------------------------------------

    // Its code threw (on any thread).
    private fun failed(guard: PluginGuard, what: String, e: Throwable) {
        log.error("Plugin ${guard.pluginId} failed in $what", e)
        val text = describe(e)
        host.post {
            val entry = entries[guard.pluginId]
            // (Of an enabling that is over: nothing to do any more.)
            if (entry?.live?.handle?.guard === guard) {
                entry.failures++
                if (entry.failures >= options.failuresBeforeDisable) {
                    guard.active = false
                    fail(entry, "it failed in $what: $text")
                    host.notice(true, "Plugin ${guard.pluginId} failed and was disabled", "$what: $text")
                } else {
                    host.notice(true, "Plugin ${guard.pluginId} failed", "$what: $text")
                }
            }
        }
        // (No more of its code until the post above ran, once it is sure to go.)
        if (options.failuresBeforeDisable <= 1) guard.active = false
    }

    // A hung call came back at last.
    private fun hungCallDone(guard: PluginGuard, what: String, millis: Long) {
        log.warn("Plugin ${guard.pluginId}: its call ($what) came back after $millis ms")
        if (!options.disableAfterHang) return
        guard.active = false
        host.post {
            val entry = entries[guard.pluginId]
            if (entry?.live?.handle?.guard === guard) {
                fail(entry, "it held up the desktop for $millis ms in $what")
                host.notice(true, "Plugin ${guard.pluginId} was disabled", "It held up the desktop for $millis ms ($what)")
            }
        }
    }

    private fun fail(entry: Entry<H>, why: String) {
        for (other in dependents(entry)) off(other, PluginState.REFUSED, "it needs the plugin ${entry.id}, which failed")
        off(entry, PluginState.FAILED, why)
    }

    private fun describe(e: Throwable) = "${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"

    // --- what people ask for ------------------------------------------------------------------------

    /** Everything found, in the order they start. */
    fun list(): List<PluginInfo> {
        val known = ordered().map { entry ->
            val manifest = entry.source.manifest
            PluginInfo(
                manifest.id, manifest.name, manifest.version.toString(), entry.formatName, entry.state, entry.problem,
                entry.source.path, manifest.description, entry.generation, entry.live?.handle?.scope?.held.orEmpty(),
            )
        }
        return known
    }

    /** The class loader of a plugin's code now, or null. */
    fun classLoaderOf(id: String): ClassLoader? = entries[id]?.code?.classLoader

    /** The plugin running as [id], or null. */
    fun instanceOf(id: String): Plugin<H>? = entries[id]?.live?.plugin

    /** What the plugin running as [id] was given, or null. */
    fun handleOf(id: String): PluginHandle? = entries[id]?.live?.handle

    /** How many class loaders of unloaded plugins were found left behind. */
    val leaks: Int get() = leakedGenerations

    /** Turns it on, also for later sessions; what it needs must run. Null, or why not. */
    fun enable(id: String): String? {
        val entry = entries[id] ?: return "there is no plugin $id"
        keeper.setOff(id, false)
        entry.offByHand = false
        if (entry.live != null) return null
        entry.failures = 0
        entry.state = PluginState.LOADED
        entry.problem = null
        refusal(entry)?.let { why ->
            entry.state = PluginState.REFUSED
            entry.problem = why
            return why
        }
        if (!enable(entry)) return entry.problem
        startWhatCan() // (those that waited for it)
        return if (id in options.disabled) "enabled for now; your configuration's list of disabled plugins has it" else null
    }

    /** Turns it off, also for later sessions, and unloads it (and those that need it). Null, or why not. */
    fun disable(id: String): String? {
        val entry = entries[id] ?: return "there is no plugin $id"
        keeper.setOff(id, true)
        entry.offByHand = true
        for (other in dependents(entry)) off(other, PluginState.REFUSED, "it needs the plugin $id, which is off")
        off(entry, PluginState.DISABLED, null)
        return null
    }

    /** Stops it and starts it from its file as it is now, and all if [id] is null. Null, or why not. */
    fun reload(id: String?): String? {
        if (id == null) {
            rescan()
            for (entry in ordered()) if (entry.live != null && entry.nextSource == null) reloadFromFile(entry)
            return null
        }
        val entry = entries[id] ?: return "there is no plugin $id"
        if (entry.live == null) return enable(id)
        reloadFromFile(entry)
        return if (entry.live != null) null else entry.problem ?: "it did not start"
    }

    private fun reloadFromFile(entry: Entry<H>) {
        try {
            val format = formats.firstOrNull { it.name == entry.formatName } ?: return
            val fresh = format.recognize(entry.source.path, host.apiName) ?: return
            entry.fingerprint = PluginFiles.fingerprint(entry.source.path)
            entry.nextSource = fresh
            hotReload(entry)
        } catch (e: Exception) {
            log.warn("Plugin ${entry.id}: its file cannot be read: $e")
            host.notice(true, "Plugin ${entry.id} not reloaded", describe(e))
        }
    }

    /** Ends everything: each hears, everything it registered goes. Nothing more starts. */
    override fun close() {
        synchronized(this) { closed = true }
        watch?.close()
        for (entry in ordered().asReversed()) off(entry, PluginState.DISABLED, null)
        reader.shutdownNow()
        temporaryCache?.let { folder -> runCatching { Files.walk(folder).use { all -> all.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } } }
    }

    private companion object {
        const val RETRY_MILLIS = 5_000L
        const val QUARANTINE_NOTE = "it froze the desktop last time"
    }
}
