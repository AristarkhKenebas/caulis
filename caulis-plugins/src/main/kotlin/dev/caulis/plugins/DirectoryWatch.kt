package dev.caulis.plugins

import dev.caulis.log.Logger
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * Tells when anything may have changed in [directories] or in the folders directly inside them; a
 * folder that is not there yet is waited for by watching the nearest one on its way. One thread
 * waiting in the kernel: nothing is polled.
 */
internal class DirectoryWatch(private val directories: List<Path>, private val log: Logger, private val changed: () -> Unit) : AutoCloseable {
    private val service: WatchService = FileSystems.getDefault().newWatchService()
    private val keys = HashMap<Path, WatchKey>()

    private val thread = Thread.ofPlatform().daemon().name("caulis-plugin-watch").unstarted(::run)

    init {
        registerAll()
        thread.start()
    }

    // The folders themselves, those inside (a Lua plugin's), or the nearest one there is.
    private fun registerAll() {
        for (directory in directories) {
            if (Files.isDirectory(directory)) {
                register(directory)
                runCatching { Files.list(directory).use { all -> all.filter { Files.isDirectory(it) }.toList() } }.getOrDefault(emptyList()).forEach(::register)
            } else {
                generateSequence(directory.parent) { it.parent }.firstOrNull { Files.isDirectory(it) }?.let(::register)
            }
        }
        keys.entries.removeIf { !it.value.isValid }
    }

    private fun register(folder: Path) {
        if (keys[folder]?.isValid == true) return
        try {
            keys[folder] = folder.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            log.trace { "Watching $folder" }
        } catch (e: java.io.IOException) {
            log.warn("Changes in $folder cannot be followed: $e")
        }
    }

    private fun run() {
        try {
            while (true) {
                val key = service.take()
                val folder = key.watchable() as Path
                val names = key.pollEvents().mapNotNull { it.context() as? Path }
                key.reset()
                // (A folder on the way: only when it is about ours. Saving other things there says nothing.)
                val about = names.any { name ->
                    val path = folder.resolve(name)
                    directories.any { path.startsWith(it) || it.startsWith(path) }
                }
                if (!about) continue
                registerAll()
                changed()
            }
        } catch (_: ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
        }
    }

    override fun close() {
        service.close()
    }
}
