package dev.tessera.examples.minimal

import dev.tessera.ffi.Platform

fun main() {
    println("Hello from Tessera (pid ${Platform.processId()}, Java ${Runtime.version()})")
}
