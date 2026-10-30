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

// How other builds name these modules, e.g. io.github.aristarkhkenebas:caulis-core:0.1.8.
group = "io.github.aristarkhkenebas"
version = libs.findVersion("caulis").get().requiredVersion

kotlin {
    // Gradle finds and uses this JDK for compiling, testing and running.
    jvmToolchain(version("java"))
    compilerOptions {
        // Set explicitly: the toolchain is JDK 27, but Kotlin cannot write JDK 27 bytecode yet and
        // would fall back to 26 with a warning on every compile. Raise "java-bytecode" in
        // libs.versions.toml to the toolchain's version once Kotlin supports it.
        jvmTarget = JvmTarget.fromTarget(version("java-bytecode").toString())
        // Any compiler warning fails the build (to keep the log clean); -Pcaulis.strictWarnings=false to allow them.
        allWarningsAsErrors = providers.gradleProperty("caulis.strictWarnings").map { it != "false" }.orElse(true)
        // Caulis's own modules may use each other's internals (dev.caulis.log.InternalCaulisApi);
        // other code may not. (Modules below caulis-log, and tools that do not use Caulis, do
        // not know the annotation.)
        if (project.path !in setOf(":caulis-geometry", ":caulis-tiling", ":caulis-ipc", ":tools:cursors")) optIn.add("dev.caulis.log.InternalCaulisApi")
    }
}

// Java sources (if a module has any) produce the same bytecode version as Kotlin.
tasks.withType<JavaCompile>().configureEach {
    options.release = version("java-bytecode")
    // javac's warnings fail the build too (-Pcaulis.strictWarnings=false: not).
    if (providers.gradleProperty("caulis.strictWarnings").orNull != "false") options.compilerArgs.add("-Werror")
}

dependencies {
    testImplementation(platform(library("junit-bom")))
    testImplementation(library("junit-jupiter"))
    testImplementation(kotlin("test"))
    testRuntimeOnly(library("junit-launcher"))
}

// Modules whose tests run compositors on the GPU (or compile heavily beside them): their test
// runs take turns, see GpuTests.
val gpuTests = gradle.sharedServices.registerIfAbsent("gpuTests", GpuTests::class) { maxParallelUsages = 1 }
val gpuTestModules = setOf("caulis-core", "caulis-render", "caulis-xwayland", "caulis-wm", "caulis-reload")

// The tests' own D-Bus session bus (see PrivateSessionBus).
val privateBus = gradle.sharedServices.registerIfAbsent("privateSessionBus", PrivateSessionBus::class) {}

tasks.withType<Test>().configureEach {
    if (project.name in gpuTestModules) usesService(gpuTests)
    useJUnitPlatform()
    // Caulis calls C libraries through the Foreign Function & Memory API.
    // Java asks for an explicit permission to do that.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Protocol Buffers and the Kotlin compiler still use sun.misc.Unsafe: no warnings about it.
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    // Tests never reach the desktop's buses: a session bus of their own (no names taken on the
    // user's — a test compositor there took names of the live session), nothing asked of
    // logind or NetworkManager.
    usesService(privateBus)
    val bus = privateBus
    doFirst { (this as Test).environment("DBUS_SESSION_BUS_ADDRESS", bus.get().address) }
    environment("DBUS_SYSTEM_BUS_ADDRESS", "unix:path=/nonexistent/caulis-test-system-bus")
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
