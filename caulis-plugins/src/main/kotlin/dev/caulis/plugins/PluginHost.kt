package dev.caulis.plugins

import java.nio.file.Path

/** What a compositor tells the [PluginManager]; [H] is the API its plugins get. */
interface PluginHost<H : Any> {
    /** The name of its API (a bare `api = 0.1` in a manifest means this one). */
    val apiName: String

    /** The APIs it offers: a plugin asking for one that is not here, or too new, is refused. */
    val apis: Map<String, ApiVersion>

    /** The API for one enabling of one plugin; what it registers goes through [PluginHandle.scope] and [PluginHandle.guard]. */
    fun apiFor(handle: PluginHandle): H

    /** Runs [task] soon on the compositor's thread; from any thread. */
    fun post(task: Runnable)

    /** Tells the user (on the compositor's thread). */
    fun notice(problem: Boolean, title: String, text: String)

    /** Where the plugin [id] keeps its things (see [PluginHandle.dataDir]). */
    fun dataDirFor(id: String): Path

    /** False while nothing may be started or stopped by itself (a locked screen): tried again later. */
    fun mayChangeNow(): Boolean = true
}

/** How a [PluginManager] behaves; each one a setting of the compositor. */
data class PluginOptions(
    /** The folders plugins are in, the first first (one id found twice: the first counts). */
    val directories: List<Path> = emptyList(),
    /** Off: no plugin runs, nothing is looked at. */
    val enabled: Boolean = true,
    /** Ids that stay off (the configuration's). */
    val disabled: Set<String> = emptySet(),
    /** Each plugin's settings, by id. */
    val settings: Map<String, Map<String, Any?>> = emptyMap(),
    /** Follows the folders: a jar replaced is hot-reloaded, one added is started, one taken away is unloaded. */
    val watch: Boolean = true,
    /** A changed file must be quiet this long before it is read (it may still be being copied). */
    val settleMillis: Long = 300,
    /** How many failures of a plugin's code before it is disabled. */
    val failuresBeforeDisable: Int = 1,
    /** One call into a plugin on the compositor's thread longer than this: reported, with the stack. 0: never. */
    val hangReportMillis: Long = 2_000,
    /** A call this long gets its thread interrupted (it ends one that waits or sleeps). 0: never. */
    val hangInterruptMillis: Long = 5_000,
    /** A call this long: written down, so that the next start leaves the plugin off (a loop that never ends takes the compositor with it). 0: never. */
    val hangQuarantineMillis: Long = 10_000,
    /** A plugin that hung is disabled once its call returns. */
    val disableAfterHang: Boolean = true,
    /** After an unload: when to look whether its class loader was collected. 0: never. */
    val leakCheckMillis: Long = 30_000,
    /** With this many class loaders left behind, hot reloads are refused until a restart (metaspace is limited). */
    val maxLeakedGenerations: Int = 8,
    /** Loads from a file or folder others can write to (normally refused: that would run their code as you). */
    val allowUnsafePermissions: Boolean = false,
    /** Packages that come from the compositor only, also when a jar brings its own copy. */
    val sharedPackages: List<String> = PluginClassLoader.DEFAULT_SHARED,
    /** Kept between sessions: what `plugin disable` and a hang said. Null: nothing is kept. */
    val stateFile: Path? = null,
    /** Where the copies of jars in use are. Null: a private temporary folder. */
    val cacheDir: Path? = null,
    /** The compositor's class loader. */
    val parentLoader: ClassLoader = PluginOptions::class.java.classLoader,
)
