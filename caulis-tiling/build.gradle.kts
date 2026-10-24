// How windows are arranged: the layout language and the built-in layouts. Pure Kotlin —
// it knows nothing about Wayland, GPUs or input, so it is easy to read and to test.

plugins {
    id("caulis.library")
}

dependencies {
    api(project(":caulis-geometry"))
}
