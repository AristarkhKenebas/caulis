package dev.caulis.examples.minimal

import dev.caulis.ffi.Platform

fun main() {
    println("Hello from Caulis (pid ${Platform.processId()}, Java ${Runtime.version()})")
}
