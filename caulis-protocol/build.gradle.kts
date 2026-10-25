// The Wayland protocol: the wire format, clients, objects, and Kotlin code generated
// from the protocol XML files in src/main/protocols.

plugins {
    id("caulis.published")
}

description = "The Wayland protocol for Caulis: wire format, server and client objects, generated protocol classes."

dependencies {
    api(project(":caulis-native"))
}

// Generated code is kept in the repository, like the C bindings.
// Regenerate with: ./gradlew :caulis-protocol:generateProtocols
val generatedDir = layout.projectDirectory.dir("src/generated/kotlin")

kotlin.sourceSets.main {
    kotlin.srcDir(generatedDir)
}

val generateProtocols = tasks.register<WaylandProtocolTask>("generateProtocols") {
    group = "wayland"
    description = "Generates Kotlin classes from the Wayland protocol XML files."
    protocols.from(fileTree("src/main/protocols") { include("*.xml") })
    outputDir = generatedDir
}

tasks.matching { it.name.startsWith("compile") }.configureEach {
    mustRunAfter(generateProtocols)
}
