// Settings shared by every module of Tessera.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// The version list from gradle/libs.versions.toml.
val libs = versionCatalogs.named("libs")
fun library(name: String) = libs.findLibrary(name).get()

fun version(name: String) = libs.findVersion(name).get().requiredVersion.toInt()

kotlin {
    // Gradle finds and uses this JDK for compiling, testing and running.
    jvmToolchain(version("java"))
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(version("java-bytecode").toString())
    }
}

// Java sources (if a module has any) produce the same bytecode version as Kotlin.
tasks.withType<JavaCompile>().configureEach {
    options.release = version("java-bytecode")
}

dependencies {
    testImplementation(platform(library("junit-bom")))
    testImplementation(library("junit-jupiter"))
    testImplementation(kotlin("test"))
    testRuntimeOnly(library("junit-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Tessera calls C libraries through the Foreign Function & Memory API.
    // Java asks for an explicit permission to do that.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
