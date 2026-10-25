package dev.caulis.core

/**
 * Passes on what nothing can recover from (running out of memory); everything else — also an
 * Error such as a stack overflow in your code, or a missing native library — may be caught,
 * logged, and the compositor goes on.
 */
fun Throwable.rethrowIfFatal() {
    if (this is OutOfMemoryError) throw this
}
