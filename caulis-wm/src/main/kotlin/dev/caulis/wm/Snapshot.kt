package dev.caulis.wm

import dev.caulis.log.Logger
import java.lang.reflect.Modifier

// A copy of a configuration as it is (not running): the settings, lists and maps copied, so
// changing the copy leaves this one as it was. Used to apply config.json again onto what the
// code said alone, when main() cannot be run again for a reload: settings taken out of the
// file then go back to what the code says, instead of staying as the file had them.
internal fun Caulis.snapshot(): Caulis {
    val copy = Caulis()
    for (field in Caulis::class.java.declaredFields) {
        if (Modifier.isStatic(field.modifiers) || field.name in NOT_COPIED) continue
        field.isAccessible = true
        val value = field.get(this)
        if (value == null) {
            field.set(copy, null)
            continue
        }
        val copied: Any? = when {
            field.name == "bindings" -> @Suppress("UNCHECKED_CAST") (value as List<Caulis.Binding>).mapTo(ArrayList()) { b ->
                Caulis.Binding(b.combination, b.options, b.keyMode, b.action).also { it.owner = copy; it.enabled = b.enabled }
            }
            value is SystemKeys -> value.copy()
            value is dev.caulis.core.input.InputSettings -> copyBean(value)
            field.name == "monitorSettings" -> @Suppress("UNCHECKED_CAST") (value as Map<String, Caulis.MonitorSettings>)
                .mapValuesTo(LinkedHashMap()) { (_, m) -> m.copy() }
            value is LinkedHashSet<*> -> LinkedHashSet(value)
            value is HashSet<*> -> HashSet(value)
            value is LinkedHashMap<*, *> -> LinkedHashMap(value)
            value is HashMap<*, *> -> HashMap(value)
            value is ArrayList<*> -> ArrayList(value)
            value is MutableCollection<*> || value is MutableMap<*, *> -> {
                log.warn("A copy of the configuration shares ${field.name} (a ${value.javaClass.simpleName})")
                value
            }
            else -> value
        }
        assign(field, copy, copied)
    }
    keys.copyInto(copy.keys) // (the usual keys: their settings, into the copy's own)
    autostart.copyInto(copy.autostart)
    reveal.copyInto(copy.reveal)
    startupSound.copyInto(copy.startupSound)
    return copy
}

// What a copy starts without: what the running compositor gave this configuration.
private val NOT_COPIED = setOf(
    "running", "session", "codeOnly", "busScopes", "timers", "added", "waitingTimers",
    "configAdded", "configProblems", "startWarnings", "keys", "autostart", "reveal", "startupSound", "pendingStarts", "backendInUse",
)

private val log = Logger("config")

// A copy of settings kept in plain fields (InputSettings, DeviceSettings): the same class
// made anew, its fields copied — those of Caulis's own mutable kinds and maps of them copied too.
private fun <T : Any> copyBean(value: T): T {
    val type = value.javaClass
    @Suppress("UNCHECKED_CAST")
    val copy = type.getDeclaredConstructor().also { it.isAccessible = true }.newInstance() as T
    var at: Class<*>? = type
    while (at != null && at != Any::class.java) {
        for (field in at.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            assign(field, copy, copyValue(field.get(value)))
        }
        at = at.superclass
    }
    return copy
}

// Puts [value] into [field] of [target]. A final field (a `val` list, map or settings object) is
// not set — Java will forbid that — but what it holds is filled with [value]'s contents instead.
@Suppress("UNCHECKED_CAST")
private fun assign(field: java.lang.reflect.Field, target: Any, value: Any?) {
    if (!Modifier.isFinal(field.modifiers)) return field.set(target, value)
    when (val own = field.get(target)) {
        value -> {}
        is MutableCollection<*> -> try {
            (own as MutableCollection<Any?>).run { clear(); addAll(value as Collection<Any?>) }
        } catch (_: UnsupportedOperationException) {
            log.warn("A copy of the configuration shares ${field.name} (it cannot be changed)")
        }
        is MutableMap<*, *> -> try {
            (own as MutableMap<Any?, Any?>).run { clear(); putAll(value as Map<Any?, Any?>) }
        } catch (_: UnsupportedOperationException) {
            log.warn("A copy of the configuration shares ${field.name} (it cannot be changed)")
        }
        null -> log.warn("A copy of the configuration has no ${field.name}")
        // (Anything else final — a logger, a lock — stays the copy's own.)
        else -> if (own is dev.caulis.core.input.InputSettings || own is dev.caulis.core.input.DeviceSettings || own is SystemKeys) {
            // A settings object: its fields, one by one.
            var at: Class<*>? = own.javaClass
            while (at != null && at != Any::class.java) {
                for (f in at.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    assign(f, own, f.get(value))
                }
                at = at.superclass
            }
        }
    }
}

private fun copyValue(value: Any?): Any? = when {
    value == null -> null
    value is LinkedHashMap<*, *> -> value.entries.associateTo(LinkedHashMap<Any?, Any?>()) { (k, v) -> k to copyValue(v) }
    value is HashMap<*, *> -> value.entries.associateTo(HashMap<Any?, Any?>()) { (k, v) -> k to copyValue(v) }
    value is ArrayList<*> -> value.mapTo(ArrayList()) { copyValue(it) }
    value is dev.caulis.core.input.DeviceSettings -> copyBean(value)
    else -> value
}

// A copy of one monitor's settings.
private fun Caulis.MonitorSettings.copy(): Caulis.MonitorSettings = Caulis.MonitorSettings(name).also { c ->
    c.scale = scale
    c.x = x
    c.y = y
    c.width = width
    c.height = height
    c.refreshHz = refreshHz
    c.enabled = enabled
    c.transform = transform
    c.maxBpc = maxBpc
    c.adaptiveSync = adaptiveSync
}
