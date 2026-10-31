package dev.caulis.core.dbus

import dev.caulis.ffi.Fd

/** A message from the bus: a method's reply or error, or a signal. */
class DBusMessage internal constructor(
    val type: Int,
    val serial: Int,
    val replySerial: Int,
    val path: String?,
    val iface: String?,
    val member: String?,
    val errorName: String?,
    val sender: String?,
    val signature: String,
    /**
     * The values, in [signature]'s order: String, ObjectPath, Boolean, Int, Long, Double, List, Fd (h), ...
     * The unsigned kinds come as their signed ones: `u` as Int and `t` as Long (2^31 and more
     * as negative numbers: `toUInt()`/`toULong()` gives them back).
     */
    val body: List<Any?>,
    internal val flags: Int = 0,
) {
    val isError: Boolean get() = type == ERROR

    override fun toString() = "D-Bus ${listOf("?", "call", "reply", "error", "signal")[type.coerceIn(0, 4)]} " +
        "${iface.orEmpty()}.${member.orEmpty()}${errorName?.let { " $it" }.orEmpty()} $body"

    internal companion object {
        const val CALL = 1
        const val REPLY = 2
        const val ERROR = 3
        const val SIGNAL = 4
    }
}
