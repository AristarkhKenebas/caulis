package dev.caulis.core

// (Here in caulis-log, which every other module uses, so all of them can call it; the package
// stays the one it had in caulis-core.)

/**
 * Passes on what nothing can recover from (running out of memory); everything else — also an
 * Error such as a stack overflow in your code, or a missing native library — may be caught,
 * logged, and the compositor goes on.
 */
fun Throwable.rethrowIfFatal() {
    if (this is OutOfMemoryError) throw this
}
