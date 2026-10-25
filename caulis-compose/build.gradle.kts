// Parts of a compositor's own interface written in Compose — a bar, a dock, a launcher, a
// volume pill, notifications: each a Compose scene drawn into a picture (no window, no X11),
// shown by the compositor and getting the pointer and the keyboard like any of its widgets.

plugins {
    id("caulis.published")
    id("org.jetbrains.kotlin.plugin.compose")
}

description = "Compose for Caulis compositors: bars, docks, launchers and more as Compose scenes inside the compositor."

val libs = versionCatalogs.named("libs")

dependencies {
    api(project(":caulis-core"))
    // compose { } for Caulis configurations.
    api(project(":caulis-wm"))
    api(libs.findLibrary("compose-runtime").get())
    api(libs.findLibrary("compose-ui").get())
    api(libs.findLibrary("compose-foundation").get())
    runtimeOnly(libs.findLibrary("skiko-linux").get())
    testImplementation(testFixtures(project(":caulis-core")))
}
