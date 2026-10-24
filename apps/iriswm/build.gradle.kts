// irisWM: a Wayland compositor built with Caulis.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "dev.iriswm"
version = "0.1.0-SNAPSHOT"

val javaVersion = libs.versions.java.asProvider().get().toInt()
val bytecodeVersion = libs.versions.java.bytecode.get().toInt()

kotlin {
    // Gradle finds (or downloads) this JDK for compiling, testing and running.
    jvmToolchain(javaVersion)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(bytecodeVersion.toString())
    }
}

// Java sources (if any) produce the same bytecode version as Kotlin.
tasks.withType<JavaCompile>().configureEach {
    options.release = bytecodeVersion
}

dependencies {
    implementation(libs.caulis.wm)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass = "dev.iriswm.MainKt"
    applicationName = "iriswm"
    applicationDefaultJvmArgs = listOf(
        // Caulis calls C libraries through the Foreign Function & Memory API.
        "--enable-native-access=ALL-UNNAMED",
        // Low-pause garbage collector, so frames are not delayed by GC.
        "-XX:+UseZGC",
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
