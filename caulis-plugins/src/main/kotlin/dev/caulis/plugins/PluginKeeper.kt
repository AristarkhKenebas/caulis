package dev.caulis.plugins

import dev.caulis.log.Logger
import java.nio.file.Files
import java.nio.file.Path

/** What is kept between sessions: which plugins are off by the user's word, and which froze the desktop. */
internal class PluginKeeper(private val file: Path?, private val log: Logger) {
    private val off = LinkedHashSet<String>()
    private val quarantined = LinkedHashSet<String>()

    init {
        if (file != null && Files.isRegularFile(file)) {
            try {
                val properties = java.util.Properties()
                Files.newBufferedReader(file).use(properties::load)
                fun read(name: String) = properties.getProperty(name).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                off += read("disabled")
                quarantined += read("quarantined")
            } catch (e: Exception) {
                log.warn("$file cannot be read: $e")
            }
        }
    }

    @Synchronized
    fun isOff(id: String) = id in off || id in quarantined

    @Synchronized
    fun isQuarantined(id: String) = id in quarantined

    @Synchronized
    fun setOff(id: String, value: Boolean) {
        val changed = if (value) off.add(id) else off.remove(id) or quarantined.remove(id)
        if (changed) save()
    }

    /** From the watchdog's thread, while the compositor's is stuck. */
    @Synchronized
    fun quarantine(id: String) {
        if (quarantined.add(id)) save()
    }

    private fun save() {
        val target = file ?: return
        try {
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, ".plugins-", ".tmp")
            Files.writeString(temporary, "# Written by the compositor: which plugins stay off.\ndisabled=${off.joinToString(",")}\nquarantined=${quarantined.joinToString(",")}\n")
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.io.IOException) {
            log.warn("$target cannot be written: $e")
        }
    }
}
