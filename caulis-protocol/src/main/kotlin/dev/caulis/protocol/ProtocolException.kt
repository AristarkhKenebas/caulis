package dev.caulis.protocol

/**
 * The client broke the protocol (a wrong argument, an unknown object, ...).
 * The client gets [code] and [message] as a wl_display.error and is disconnected.
 */
class ProtocolException(
    /** The object the error is about (wl_display itself for low-level errors). */
    val resource: Resource,
    val code: Int,
    message: String,
) : RuntimeException(message)
