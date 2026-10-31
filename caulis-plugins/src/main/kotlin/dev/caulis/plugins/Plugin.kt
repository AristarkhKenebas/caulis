package dev.caulis.plugins

import dev.caulis.log.Logger
import java.nio.file.Path

/**
 * What a plugin implements; [H] is what the compositor offers it (Caulis: `PluginApi`). The
 * manifest's `main` names the class, which has a constructor without arguments (or is a Kotlin
 * `object`). Each enabling gets a new instance, so a field cannot outlive a disable.
 *
 * Everything runs on the compositor's thread: keep it quick (work of minutes on a thread of
 * [PluginScope.thread]). Whatever a call throws is caught: the plugin is disabled and the user told.
 */
interface Plugin<H : Any> {
    /** Starts it. What it registers through [PluginContext.api] or [PluginContext.scope] is taken away by itself at the end. */
    fun enable(context: PluginContext<H>)

    /** It is about to be disabled: save what should be kept (in [PluginContext.dataDir]). Nothing registered needs taking away. */
    fun disable() {}

    /** The configuration's settings for it changed: true if it took them on; false (the default) reloads it. */
    fun settingsChanged(settings: PluginSettings): Boolean = false
}

/** The parts of one enabling of a plugin that depend on no compositor (see [PluginContext]). */
class PluginHandle internal constructor(
    /** What it says of itself. */
    val manifest: PluginManifest,
    /** Takes away what it registered, when it is disabled. */
    val scope: PluginScope,
    /** Runs its code so that what it throws, or a hang, stays the plugin's problem. */
    val guard: PluginGuard,
    /** Its log (area `plugin-<id>`, level with `log plugin-<id> 3`). */
    val log: Logger,
    /** A folder of its own, kept through reloads (made when first asked for). */
    private val dataFolder: Path,
    /** The configuration's settings for it. */
    settings: PluginSettings,
) {
    @Volatile
    var settings: PluginSettings = settings
        internal set

    /** Its own folder for what it keeps, made when first asked for. */
    val dataDir: Path get() = java.nio.file.Files.createDirectories(dataFolder)
}

/** What a plugin gets when it is enabled. */
class PluginContext<H : Any>(
    /** What does not depend on the compositor. */
    val handle: PluginHandle,
    /** What the compositor offers it. */
    val api: H,
) {
    val manifest: PluginManifest get() = handle.manifest
    val scope: PluginScope get() = handle.scope
    val log: Logger get() = handle.log
    val dataDir: Path get() = handle.dataDir
    val settings: PluginSettings get() = handle.settings
}

/** The configuration's settings for one plugin: read with a default, so a plugin works without any. */
class PluginSettings(
    /** All of them as given (numbers, text, booleans, lists, nested maps). */
    val values: Map<String, Any?> = emptyMap(),
) {
    fun string(name: String, default: String): String = values[name] as? String ?: default
    fun boolean(name: String, default: Boolean): Boolean = values[name] as? Boolean ?: default
    fun int(name: String, default: Int): Int = (values[name] as? Number)?.toInt() ?: default
    fun long(name: String, default: Long): Long = (values[name] as? Number)?.toLong() ?: default
    fun double(name: String, default: Double): Double = (values[name] as? Number)?.toDouble() ?: default

    /** The items that are text. */
    fun strings(name: String, default: List<String> = emptyList()): List<String> =
        (values[name] as? List<*>)?.filterIsInstance<String>() ?: default

    override fun equals(other: Any?) = other is PluginSettings && other.values == values
    override fun hashCode() = values.hashCode()
    override fun toString() = "PluginSettings(${values.keys})"
}
