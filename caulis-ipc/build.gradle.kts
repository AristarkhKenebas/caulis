// The IPC socket's messages (caulis.proto) and a client for JVM programs. The compositor's
// side is in caulis-wm; programs in other languages make their code from caulis.proto.

plugins {
    id("caulis.published")
}

description = "The IPC socket of Caulis compositors: Protocol Buffers messages and a JVM client."

val libs = versionCatalogs.named("libs")

dependencies {
    api(libs.findLibrary("protobuf-javalite").get())
}

// The Java code made from caulis.proto is kept in the repository, so a normal build needs no
// protoc. Make it again after changing the .proto: ./gradlew :caulis-ipc:generateProto
val protoDir = layout.projectDirectory.dir("src/main/proto")
val generatedDir = layout.projectDirectory.dir("src/generated/java")

sourceSets.main {
    java.srcDir(generatedDir)
}

tasks.register<Exec>("generateProto") {
    group = "build"
    description = "Makes the Java code of caulis.proto again (needs protoc on the PATH)."
    val protos = protoDir.asFileTree.matching { include("**/*.proto") }
    inputs.files(protos)
    outputs.dir(generatedDir)
    // (A plain File: the configuration cache keeps no script objects.)
    val output = generatedDir.asFile
    doFirst {
        output.deleteRecursively()
        output.mkdirs()
    }
    commandLine(listOf("protoc", "--proto_path=${protoDir.asFile}", "--java_out=lite:${generatedDir.asFile}") + protos.files.map { it.path })
}
