package dev.caulis.wm.config

import dev.caulis.log.Logger
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Follows one configuration file — one that may not be there yet, in a folder that may not
 * be there yet: the nearest folder on its way that is there is watched, and nearer ones as
 * they are made. [changed] runs (on a thread of its own, once things are quiet for a moment)
 * when the file is made, saved or taken away.
 */
internal class ConfigWatch(file: Path, private val changed: () -> Unit) : AutoCloseable {

    private val file: Path = file.toAbsolutePath().normalize()

    // The folder watched now (the file's own, or the nearest one on its way that is there).
    @Volatile
    private var key: WatchKey? = null

    @Volatile
    private var closed = false

    private var pending: ScheduledFuture<*>? = null

    init {
        watchNearest()
    }

    // Watches the nearest folder on the way to the file that is there.
    @Synchronized
    private fun watchNearest() {
        if (closed) return
        val folder = generateSequence(file.parent) { it.parent }.firstOrNull { Files.isDirectory(it) } ?: return
        if ((key?.watchable() as? Path) == folder && key?.isValid == true) return
        key?.let { old -> watchers[old]?.remove(this) }
        try {
            val now = folder.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            watchers.computeIfAbsent(now) { ConcurrentHashMap.newKeySet() } += this
            key = now
            log.trace { "Following $file in $folder" }
        } catch (e: Exception) {
            log.warn("Changes of $file cannot be followed: $e")
        }
    }

    // Something changed in the folder watched: the file itself, or a folder on its way.
    private fun seen(folder: Path, names: List<Path>) {
        for (name in names) {
            val path = folder.resolve(name)
            when {
                path == file -> later()
                file.startsWith(path) -> {
                    // A folder on the way was made (or taken away): watch the nearest again.
                    watchNearest()
                    if (Files.exists(file)) later()
                }
            }
        }
    }

    // The folder watched went away: the nearest one on the way again, and the file is gone.
    private fun lost() {
        watchNearest()
        later()
    }

    // Tells once it is quiet (editors save in several steps).
    @Synchronized
    private fun later() {
        if (closed) return
        pending?.cancel(false)
        pending = quiet.schedule({ if (!closed) runCatching(changed).onFailure { log.warn("Following $file failed: $it") } }, QUIET_MILLIS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun close() {
        closed = true
        pending?.cancel(false)
        key?.let { watchers[it]?.remove(this) }
    }

    private companion object {
        val log = Logger("config")
        const val QUIET_MILLIS = 200L

        // Each folder's key and who follows something in it.
        val watchers = ConcurrentHashMap<WatchKey, MutableSet<ConfigWatch>>()

        val quiet = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "caulis-config-quiet").apply { isDaemon = true } }

        val service by lazy {
            FileSystems.getDefault().newWatchService().also { service ->
                Thread.ofPlatform().daemon().name("caulis-config-watch").start {
                    try {
                        while (true) {
                            val key = service.take()
                            val folder = key.watchable() as Path
                            val names = key.pollEvents().mapNotNull { it.context() as? Path }
                            val valid = key.reset()
                            val following = watchers[key]?.toList().orEmpty()
                            if (!valid) {
                                watchers.remove(key)
                                following.forEach { it.lost() }
                            } else {
                                following.forEach { it.seen(folder, names) }
                            }
                        }
                    } catch (_: ClosedWatchServiceException) {
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
    }
}
