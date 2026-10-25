// config.kts: a compositor's configuration as a Kotlin script, compiled by the compositor
// itself (no Gradle, no project) — all of Caulis, as in Main.kt. Add it to a compositor that
// should read config.kts; it is found by itself (ServiceLoader). It is big: it contains the
// Kotlin compiler.

plugins {
    id("caulis.library")
}

dependencies {
    api(project(":caulis-wm"))
    api(libs.kotlin.scripting.common)
    api(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
}

// Its tests run the Kotlin compiler (heavy for a few seconds): after the tests that run
// compositors and wait for them with time limits.
tasks.test {
    mustRunAfter(":caulis-core:test", ":caulis-xwayland:test", ":caulis-wm:test")
}
