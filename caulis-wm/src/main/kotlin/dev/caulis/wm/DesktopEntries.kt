package dev.caulis.wm

import dev.caulis.log.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * The XDG autostart folders (`~/.config/autostart`, `/etc/xdg/autostart`): their `.desktop`
 * files as [Autostart] entries, by the Desktop Application Autostart Specification — a file
 * in the user's folder hides one of the same name in the system's; Hidden, OnlyShowIn,
 * NotShowIn, TryExec and GNOME's X-GNOME-Autostart-enabled are honoured.
 */
internal object DesktopEntries {

    private val log = Logger("autostart")

    /** The autostart folders, the user's first. */
    fun folders(env: (String) -> String? = System::getenv): List<Path> {
        val home = env("HOME") ?: System.getProperty("user.home")
        val config = env("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.config"
        val dirs = env("XDG_CONFIG_DIRS")?.split(':')?.filter { it.startsWith("/") }?.takeIf { it.isNotEmpty() } ?: listOf("/etc/xdg")
        return (listOf(config) + dirs).map { Path.of(it, "autostart") }
    }

    /**
     * The entries to start from [folders], for the desktop [desktopName] (a list as in
     * XDG_CURRENT_DESKTOP: "newm:wlroots"); [found] tells whether a program is there (TryExec).
     */
    fun read(folders: List<Path>, desktopName: String, found: (String) -> Boolean = ::onPath): List<Autostart.Entry> {
        val files = LinkedHashMap<String, Path>()
        for (folder in folders) {
            val list = try {
                Files.list(folder).use { it.filter { f -> f.fileName.toString().endsWith(".desktop") }.sorted().toList() }
            } catch (_: Exception) {
                continue // (not there)
            }
            for (file in list) files.putIfAbsent(file.fileName.toString(), file) // the first folder's wins
        }
        val desktops = desktopName.split(':').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        return files.toSortedMap().mapNotNull { (name, file) ->
            val id = name.removeSuffix(".desktop")
            val text = try {
                Files.readString(file)
            } catch (e: Exception) {
                log.warn("$file cannot be read: ${e.message}")
                return@mapNotNull null
            }
            entryOf(id, text, desktops, file.toString(), found).also { entry ->
                if (entry != null) log.trace { "$file: ${entry.command.joinToString(" ")}" }
            }
        }
    }

    /** The entry [text] (a .desktop file's) asks to start, or null when it is not to start here. */
    fun entryOf(id: String, text: String, desktops: Set<String>, location: String? = null, found: (String) -> Boolean): Autostart.Entry? {
        val keys = parse(text)
        fun skip(why: String): Autostart.Entry? = null.also { log.info { "$id.desktop not started: $why" } }
        fun list(key: String) = keys[key]?.split(';')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
        if ((keys["Type"] ?: "Application") != "Application") return skip("it is not an application")
        if (keys["Hidden"].equals("true", ignoreCase = true)) return skip("it is hidden (turned off)")
        if (keys["X-GNOME-Autostart-enabled"].equals("false", ignoreCase = true)) return skip("it is turned off (X-GNOME-Autostart-enabled)")
        val only = list("OnlyShowIn")
        if (only.isNotEmpty() && only.none { it in desktops }) return skip("only for ${only.joinToString()}")
        val not = list("NotShowIn")
        if (not.any { it in desktops }) return skip("not for ${not.filter { it in desktops }.joinToString()}")
        keys["TryExec"]?.takeIf { it.isNotBlank() }?.let { tryExec -> if (!found(tryExec.trim())) return skip("$tryExec is not installed") }
        val exec = keys["Exec"]?.takeIf { it.isNotBlank() } ?: return skip("it has no Exec")
        // (Which terminal it would want is not known: started in one of your own, with autostart.)
        if (keys["Terminal"].equals("true", ignoreCase = true)) return skip("it runs in a terminal (Terminal=true): start it with your terminal in autostart instead")
        val words = execWords(exec, icon = keys["Icon"], name = keys["Name"], location = location)
        if (words.isEmpty()) return skip("its Exec is empty")
        return Autostart.Entry(Autostart.Kind.APP, words).also { entry ->
            entry.name = id
            keys["Path"]?.takeIf { it.isNotBlank() }?.let { entry.dir = it }
        }
    }

    /** The keys of the [Desktop Entry] group (not the translated ones), their values unescaped. */
    fun parse(text: String): Map<String, String> {
        val keys = LinkedHashMap<String, String>()
        var inEntry = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                inEntry = line == "[Desktop Entry]"
                continue
            }
            if (!inEntry) continue
            val key = line.substringBefore('=', "").trim()
            if (key.isEmpty() || '[' in key) continue // (Name[de]=...: not needed)
            keys.putIfAbsent(key, unescape(line.substringAfter('=').trim()))
        }
        return keys
    }

    // \s \n \t \r \\ in a value.
    private fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    's' -> out.append(' ')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append('\\').append(next) // (Exec's own escapes: for execWords)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Exec's words, by the Desktop Entry Specification: split at spaces, "quoted" words kept
     * whole (with \" \` \$ \\ inside; "" is an empty word), %% is a %, and the field codes
     * left out — nothing is opened at login: a word that is only %f, %F, %u or %U goes, %i
     * becomes `--icon` [icon] (if there is one), %c the [name], %k the file's [location], the
     * others nothing.
     */
    fun execWords(exec: String, icon: String? = null, name: String? = null, location: String? = null): List<String> {
        // First the words (and whether each was quoted: field codes are not looked for in those).
        val split = ArrayList<Pair<String, Boolean>>()
        val word = StringBuilder()
        var quoted = false
        var inWord = false
        var wasQuoted = false
        var i = 0
        while (i < exec.length) {
            val c = exec[i]
            when {
                quoted && c == '\\' && i + 1 < exec.length -> {
                    word.append(exec[i + 1])
                    i++
                }
                c == '"' -> {
                    quoted = !quoted
                    inWord = true
                    wasQuoted = true
                }
                !quoted && (c == ' ' || c == '\t') -> {
                    if (inWord) split += word.toString() to wasQuoted
                    word.clear()
                    inWord = false
                    wasQuoted = false
                }
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) split += word.toString() to wasQuoted
        // Then the field codes, in one pass over each word.
        val words = ArrayList<String>()
        for ((w, wasQuotedWord) in split) {
            if (wasQuotedWord) {
                words += w
                continue
            }
            if (w.length == 2 && w[0] == '%' && w[1] in "fFuU") continue // a file or URL alone: none at login
            if (w == "%i") {
                if (!icon.isNullOrBlank()) words += listOf("--icon", icon)
                continue
            }
            val out = StringBuilder()
            var j = 0
            while (j < w.length) {
                val c = w[j]
                if (c == '%' && j + 1 < w.length) {
                    when (w[j + 1]) {
                        '%' -> out.append('%')
                        'c' -> out.append(name.orEmpty())
                        'k' -> out.append(location.orEmpty())
                        else -> {} // %f %u ... inside a word, %d %n %v %m (deprecated): nothing
                    }
                    j += 2
                } else {
                    out.append(c)
                    j++
                }
            }
            // (A word that was only field codes is gone, not an empty argument.)
            if (out.isNotEmpty() || w.isEmpty()) words += out.toString()
        }
        return words
    }

    /** True if [program] can be run: a file that is executable, or a program on PATH. */
    fun onPath(program: String): Boolean {
        if (program.startsWith("/")) return Files.isExecutable(Path.of(program))
        return System.getenv("PATH").orEmpty().split(':').filter { it.isNotEmpty() }.any { Files.isExecutable(Path.of(it, program)) }
    }
}
