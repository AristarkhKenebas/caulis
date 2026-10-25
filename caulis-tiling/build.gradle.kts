// How windows are arranged: the layout language and the built-in layouts. Pure Kotlin —
// it knows nothing about Wayland, GPUs or input, so it is easy to read and to test.

plugins {
    id("caulis.published")
}

description = "Window layouts for Caulis: a small layout language, built-in and remembering layouts."

dependencies {
    api(project(":caulis-geometry"))
}
