// Fast hot reload for development: compiles the configuration inside the running compositor
// (the Kotlin compiler and the JDK's javax.tools) instead of a Gradle build. Add it to the
// `run` task only — it is big (the Kotlin compiler) and an installed compositor needs none.

plugins {
    id("caulis.published")
}

description = "Fast hot reload for Caulis compositors in development (compiles inside the running compositor)."

dependencies {
    api(project(":caulis-wm"))
    implementation(libs.kotlin.compiler)
    implementation(libs.kotlin.build.tools.api)
    runtimeOnly(libs.kotlin.build.tools.impl)
}

// Its tests run the Kotlin compiler (heavy for a few seconds): after the tests that run
// compositors and wait for them with time limits (they also take turns, see GpuTests).
tasks.test {
    mustRunAfter(":caulis-core:test", ":caulis-xwayland:test", ":caulis-wm:test")
}
