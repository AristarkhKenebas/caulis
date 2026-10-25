package dev.caulis.ipc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

/** The Java code in src/generated is what caulis.proto makes (else: ./gradlew :caulis-ipc:generateProto). */
class GeneratedCodeTest {

    @Test
    fun `the generated code is up to date`() {
        val protoc = System.getenv("PATH").orEmpty().split(':').map { Path.of(it, "protoc") }.firstOrNull { Files.isExecutable(it) }
        assumeTrue(protoc != null, "no protoc to compare with")
        val fresh = Files.createTempDirectory("caulis-proto")
        try {
            val protoDir = Path.of("src/main/proto").toAbsolutePath()
            val protos = Files.walk(protoDir).use { files -> files.filter { it.toString().endsWith(".proto") }.toList() }
            val process = ProcessBuilder(listOf(protoc.toString(), "--proto_path=$protoDir", "--java_out=lite:$fresh") + protos.map { it.toString() })
                .redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            assertEquals(0, process.waitFor(), output)
            fun files(root: Path) = Files.walk(root).use { all -> all.filter(Files::isRegularFile).toList() }
                .associate { it.relativeTo(root).toString() to Files.readString(it).lines().filterNot { line -> line.startsWith("// Protobuf Java Version") } }
            val committed = files(Path.of("src/generated/java"))
            val made = files(fresh)
            assertEquals(made.keys.sorted(), committed.keys.sorted(), "the same files (see ./gradlew :caulis-ipc:generateProto)")
            for ((name, lines) in made) assertEquals(lines, committed[name], "$name is out of date (./gradlew :caulis-ipc:generateProto)")
        } finally {
            fresh.toFile().deleteRecursively()
        }
    }
}
