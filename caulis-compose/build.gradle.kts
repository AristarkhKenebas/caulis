// Parts of a compositor's own interface written in Compose — a bar, a dock, a launcher, a
// volume pill, notifications: each a Compose scene drawn into a picture (no window, no X11),
// shown by the compositor and getting the pointer and the keyboard like any of its widgets.

plugins {
    id("caulis.published")
    id("org.jetbrains.kotlin.plugin.compose")
}

description = "Compose for Caulis compositors: bars, docks, launchers and more as Compose scenes inside the compositor."

val libs = versionCatalogs.named("libs")
// This module uses parts of Compose made for its own modules (its scenes, its key events):
// they change between versions, so exactly this one is required — a build that would take
// another fails at once, instead of the compositor failing while it runs.
val compose: String = libs.findVersion("compose").get().requiredVersion
val skiko: String = libs.findVersion("skiko").get().requiredVersion

dependencies {
    api(project(":caulis-core"))
    // compose { } for Caulis configurations.
    api(project(":caulis-wm"))
    api(libs.findLibrary("compose-runtime").get()) { version { strictly(compose) } }
    api(libs.findLibrary("compose-ui").get()) { version { strictly(compose) } }
    api(libs.findLibrary("compose-foundation").get()) { version { strictly(compose) } }
    // Skia's native part for Linux on both kinds of processors (the right one is loaded).
    runtimeOnly(libs.findLibrary("skiko-linux").get())
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-arm64:$skiko")
    testImplementation(testFixtures(project(":caulis-core")))
}
