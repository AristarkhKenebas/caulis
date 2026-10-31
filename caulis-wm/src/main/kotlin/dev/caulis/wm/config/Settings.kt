package dev.caulis.wm.config

import dev.caulis.log.InternalCaulisApi
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

    val root = SettingsRoot.root

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
