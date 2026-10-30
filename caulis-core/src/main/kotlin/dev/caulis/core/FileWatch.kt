package dev.caulis.core

import dev.caulis.log.Logger
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tells when files change (a shader or a picture you edit while the compositor runs).
 * One thread watches the folders of all watched files; listeners run on it, so they
 * should only hand the news over (e.g. post to the event loop).
 */
object FileWatch {

    private val log = Logger("config")
    private val listeners = ConcurrentHashMap<Path, CopyOnWriteArrayList<() -> Unit>>()
    private val folders = ConcurrentHashMap<Path, WatchKey>()

    private val service by lazy {
        FileSystems.getDefault().newWatchService().also { service ->
            Thread.ofPlatform().daemon().name("caulis-file-watch").start {
                try {
                    while (true) {
                        val key = service.take()
                        val folder = key.watchable() as Path
                        val changed = key.pollEvents().mapNotNull { (it.context() as? Path)?.let(folder::resolve) }.toSet()
                        key.reset()
                        for (file in changed) listeners[file]?.forEach { listener -> runCatching(listener) }
                    }
                } catch (_: ClosedWatchServiceException) {
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    /** Runs [listener] (on the watching thread) whenever [file] is written. Close the result to stop. */
    @JvmStatic
    fun watch(file: Path, listener: () -> Unit): AutoCloseable {
        val path = file.toAbsolutePath().normalize()
        val folder = path.parent
        try {
            if (folder != null && Files.isDirectory(folder)) folders.computeIfAbsent(folder) { it.register(service, ENTRY_CREATE, ENTRY_MODIFY) }
        } catch (e: Exception) {
            log.warn("Changes of $path cannot be followed: $e")
        }
        listeners.computeIfAbsent(path) { CopyOnWriteArrayList() } += listener
        return AutoCloseable { listeners[path]?.remove(listener) }
    }
}

/**
 * The text of a file, read again when it changes (see [FileWatch]); [onChange] runs after.
 * Until the file can be read, [text] is empty and [problem] says why.
 */
internal class WatchedText private constructor(val path: Path) {
    @Volatile
    var text: String = ""
        private set

    @Volatile
    var problem: String? = null
        private set

    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        read()
        FileWatch.watch(path) {
            read()
            changeListeners.forEach { runCatching(it) }
            anyChange.forEach { runCatching(it) }
        }
    }

    fun onChange(listener: () -> Unit) {
        changeListeners += listener
    }

    private fun read() {
        try {
            text = Files.readString(path)
            problem = null
        } catch (e: Exception) {
            problem = "$path cannot be read: ${e.message ?: e}"
        }
    }

    companion object {
        private val all = ConcurrentHashMap<Path, WatchedText>()

        /** Run after any watched text changed (on the watching thread). */
        val anyChange = CopyOnWriteArrayList<() -> Unit>()

        /** The one watched text of [path] (shared by everything that uses the file). */
        fun of(path: Path): WatchedText = all.computeIfAbsent(path.toAbsolutePath().normalize(), ::WatchedText)
    }
}
