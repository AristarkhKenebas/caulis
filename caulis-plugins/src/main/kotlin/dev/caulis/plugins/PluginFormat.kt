package dev.caulis.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.jar.JarFile

/** A kind of plugin file: jars ([JarPluginFormat]), Lua scripts (caulis-lua), or one of your own. */
interface PluginFormat<H : Any> {
    /** What it is called in `plugin list` ("jar", "lua"). */
    val name: String

    /**
     * What [path] (a file or folder directly in a plugin folder) holds, or null when it is not of this
     * format. Throws [PluginManifestException] when it is but its manifest is wrong. Off the compositor's thread.
     */
    fun recognize(path: Path, defaultApi: String): PluginSource<H>?
}

/** One plugin as found on disk. */
interface PluginSource<H : Any> {
    val path: Path
    val manifest: PluginManifest

    /** Changes when its files change (size and time). */
    fun fingerprint(): String

    /** Readies what can be readied away from the compositor's thread (copies a jar). Called once before [load]. */
    fun prepare(cacheDir: Path, generation: Int) {}

    /** The code, on the compositor's thread. */
    fun load(request: PluginLoadRequest): PluginCode<H>
}

/** What loading a plugin's code may need. */
class PluginLoadRequest(
    /** The class loader of the compositor. */
    val parent: ClassLoader,
    /** Which packages come from the compositor only. */
    val shared: List<String>,
    /** The class loaders of the plugins it depends on that have any. */
    val dependencies: List<PluginClassLoader>,
    /** Which time this path is loaded (a name for its loader). */
    val generation: Int,
)

/** A plugin's loaded code. */
interface PluginCode<H : Any> : AutoCloseable {
    /** Its class loader, if it has one of its own: must be collected after [close]. */
    val classLoader: ClassLoader?

    /** A new instance, for one enabling. */
    fun newPlugin(): Plugin<H>
}

/** Jar files with a manifest at the top. */
class JarPluginFormat<H : Any> : PluginFormat<H> {
    override val name get() = "jar"

    override fun recognize(path: Path, defaultApi: String): PluginSource<H>? {
        if (!path.fileName.toString().endsWith(".jar") || !Files.isRegularFile(path)) return null
        val where = path.fileName.toString()
        val manifest = try {
            JarFile(path.toFile()).use { jar ->
                val entry = jar.getJarEntry(PluginManifest.FILE_NAME)
                    ?: throw PluginManifestException("$where: no ${PluginManifest.FILE_NAME} in the jar")
                jar.getInputStream(entry).bufferedReader().use { PluginManifest.read(it, defaultApi, where) }
            }
        } catch (e: java.io.IOException) {
            throw PluginManifestException("$where: not a jar that can be read: ${e.message}")
        }
        if (manifest.main == null) throw PluginManifestException("$where: no \"main\" in the manifest (the class that implements Plugin)")
        return JarSource(path, manifest)
    }

    private class JarSource<H : Any>(override val path: Path, override val manifest: PluginManifest) : PluginSource<H> {
        private var copy: Path? = null

        override fun fingerprint(): String = PluginFiles.fingerprint(path)

        // A copy: the file may be replaced any time, and a loader must not read half a new jar.
        override fun prepare(cacheDir: Path, generation: Int) {
            Files.createDirectories(cacheDir)
            copy = Files.createTempFile(cacheDir, "${manifest.id}-$generation-", ".jar").also {
                Files.copy(path, it, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }

        override fun load(request: PluginLoadRequest): PluginCode<H> {
            val file = checkNotNull(copy) { "not prepared" }
            val loader = PluginClassLoader("plugin:${manifest.id}#${request.generation}", arrayOf(file.toUri().toURL()), request.parent, request.shared, request.dependencies)
            return JarCode(loader, checkNotNull(manifest.main), file)
        }
    }

    private class JarCode<H : Any>(private var loader: PluginClassLoader?, private val main: String, private val file: Path) : PluginCode<H> {
        override val classLoader: ClassLoader? get() = loader

        @Suppress("UNCHECKED_CAST")
        override fun newPlugin(): Plugin<H> {
            val type = Class.forName(main, true, checkNotNull(loader))
            // (A Kotlin object is its own one instance.)
            val instance = type.declaredFields.firstOrNull { it.name == "INSTANCE" && java.lang.reflect.Modifier.isStatic(it.modifiers) }?.get(null)
                ?: type.getDeclaredConstructor().newInstance()
            return instance as? Plugin<H> ?: throw PluginManifestException("$main does not implement ${Plugin::class.java.name}")
        }

        override fun close() {
            loader?.close()
            loader = null
            Files.deleteIfExists(file)
        }
    }
}

/** What the formats and the manager ask of files. */
object PluginFiles {
    /** Changes when [path] changes: its size and time; for a folder, of everything in it. */
    @JvmStatic
    fun fingerprint(path: Path): String {
        if (!Files.isDirectory(path)) return "${Files.size(path)}:${Files.getLastModifiedTime(path).toMillis()}"
        return Files.walk(path).use { all ->
            all.filter { Files.isRegularFile(it) }.map { "${path.relativize(it)}=${Files.size(it)}:${Files.getLastModifiedTime(it).toMillis()}" }.sorted().toList().joinToString(";")
        }
    }

    /** Why [path] must not be run because others can write to it, or null. (Not asked where the system has no such things.) */
    @JvmStatic
    fun unsafeBecause(path: Path): String? {
        val real = path.toRealPath()
        return listOf(real, real.parent).firstNotNullOfOrNull(::unsafeOne)
    }

    private fun unsafeOne(path: Path): String? = try {
        val attributes = Files.readAttributes(path, PosixFileAttributes::class.java)
        val owner = attributes.owner()
        val me = Files.getOwner(Path.of("/proc/self"))
        val permissions = attributes.permissions()
        when {
            owner != me && owner.name != "root" -> "$path belongs to ${owner.name}: another user's code is not run"
            PosixFilePermission.OTHERS_WRITE in permissions -> "anyone can change $path: not run"
            // (Many systems give each user a group of their own: then a group's write is the user's.)
            PosixFilePermission.GROUP_WRITE in permissions && attributes.group().name != owner.name -> "the group ${attributes.group().name} can change $path: not run"
            else -> null
        }
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }
}

/** A [PluginFormat] found by itself on the class path (ServiceLoader): how caulis-lua's comes. */
fun interface PluginFormatProvider {
    fun format(): PluginFormat<*>
}
