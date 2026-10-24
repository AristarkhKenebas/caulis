package dev.caulis.protocol

import dev.caulis.ffi.Fd
import dev.caulis.log.Logger

/**
 * Every Wayland message, written at level 3 of the "wayland" log area — like
 * `WAYLAND_DEBUG=1` of libwayland. `CAULIS_LOG=wayland=3` turns it on:
 *
 * ```
 * T wayland  foot[4123] → wl_surface@15.attach(buffer=wl_buffer@22, x=0, y=0)
 * T wayland  foot[4123] ← xdg_toplevel@18.configure(width=960, height=1080, states=array[8])
 * ```
 *
 * The generated protocol code calls this; when the level is lower, nothing is made.
 */
object Wire {
    @JvmField
    val log = Logger("wayland")

    val tracing: Boolean get() = log.tracing

    /** A request a client sent to us (server side). */
    fun request(resource: Resource, name: String, args: String) = log.trace("${resource.client.name} → $resource.$name($args)")

    /** An event we send to a client (server side). */
    fun event(resource: Resource, name: String, args: String) =
        log.trace("${resource.client.name} ← $resource.$name(${private(resource.type.name, name, args)})")

    /** A request we send as a client (nested mode, tests). */
    fun sent(proxy: Any, name: String, args: String) = log.trace("→ server $proxy.$name($args)")

    /** An event we got as a client. */
    fun received(proxy: Any, name: String, args: String) =
        log.trace("← server $proxy.$name(${private(proxy.toString().substringBefore('@'), name, args)})")

    // Which keys are pressed never goes into the log (it could be a password).
    private fun private(type: String, name: String, args: String) =
        if (type == "wl_keyboard" && name == "key") args.replace(Regex("key=\\d+"), "key=•") else args

    /** How an argument looks in the log. */
    fun show(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + (if (value.length > 200) value.take(200) + "…" else value) + "\""
        is ByteArray -> "array[${value.size}]"
        is Fd -> "fd"
        is Double -> String.format("%.2f", value)
        else -> value.toString()
    }
}
