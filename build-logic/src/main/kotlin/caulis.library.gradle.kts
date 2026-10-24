// Settings shared by every module of Caulis.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}

// The version list from gradle/libs.versions.toml.
val libs = versionCatalogs.named("libs")
fun library(name: String) = libs.findLibrary(name).get()

fun version(name: String) = libs.findVersion(name).get().requiredVersion.toInt()

// How other builds name these modules, e.g. dev.caulis:caulis-core:0.1.0-SNAPSHOT.
group = "dev.caulis"
version = libs.findVersion("caulis").get().requiredVersion

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
    // Caulis calls C libraries through the Foreign Function & Memory API.
    // Java asks for an explicit permission to do that.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// API documentation (./gradlew dokkaGenerate at the top level builds the whole website).
dokka {
    dokkaSourceSets.configureEach {
        // A module's own introduction, if it has one.
        includes.from(fileTree(projectDir) { include("Module.md") })
        // The generated C bindings are an implementation detail, not something to read.
        perPackageOption {
            matchingRegex = "dev\\.caulis\\.ffi\\.c(\\..*)?"
            suppress = true
        }
    }
}
