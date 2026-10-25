// Fast hot reload for development: compiles the configuration inside the running compositor
// (the Kotlin compiler and the JDK's javax.tools) instead of a Gradle build. Add it to the
// `run` task only — it is big (the Kotlin compiler) and an installed compositor needs none.

plugins {
    id("caulis.library")
}

dependencies {
    api(project(":caulis-wm"))
    implementation(libs.kotlin.compiler)
}

// Its tests run the Kotlin compiler (heavy for a few seconds): after the tests that run
// compositors and wait for them with time limits (they also take turns, see GpuTests).
tasks.test {
    mustRunAfter(":caulis-core:test", ":caulis-xwayland:test", ":caulis-wm:test")
}
