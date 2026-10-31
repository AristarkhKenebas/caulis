package dev.caulis.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.Path

/** Makes plugin jars from the test classes of a package. */
object PluginJars {
    /** A jar at [file] with [manifest]'s lines and the classes under [classPrefix] ("testplugins/"), written so it appears at once. */
    fun write(file: Path, manifest: String, classPrefix: String = "testplugins/", marker: String? = null) {
        val roots = System.getProperty("java.class.path").split(':').map { Path(it) }.filter { Files.isDirectory(it) }
        val temporary = Files.createTempFile(file.parent, ".writing", ".part")
        JarOutputStream(Files.newOutputStream(temporary)).use { jar ->
            jar.putNextEntry(JarEntry(PluginManifest.FILE_NAME))
            jar.write(manifest.toByteArray())
            jar.closeEntry()
            if (marker != null) {
                jar.putNextEntry(JarEntry("marker.txt"))
                jar.write(marker.toByteArray())
                jar.closeEntry()
            }
            for (root in roots) {
                val base = root.resolve(classPrefix)
                if (!Files.isDirectory(base)) continue
                Files.walk(base).use { all ->
                    all.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.forEach { path ->
                        // (The host's side of the tests stays the parent's: both sides must see one TestHost.)
                        val name = root.relativize(path).toString()
                        if (!name.startsWith("testplugins/TestHost") && !name.startsWith("testplugins/TestApi")) {
                            jar.putNextEntry(JarEntry(name))
                            jar.write(Files.readAllBytes(path))
                            jar.closeEntry()
                        }
                    }
                }
            }
        }
        Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun manifest(id: String, main: String, version: String = "1.0", api: String = "test:0.1", depends: String = "") =
        "id=$id\nversion=$version\napi=$api\nmain=$main\ndepends=$depends\n"
}
