package dev.caulis.log

/**
 * Takes out of log lines and reports what must not leave the computer when they are shared:
 * secrets, your home folder, user and computer names, mail and network addresses, and the
 * words of windows, Wi-Fi networks and the like. What is left says what happened, not whose it
 * was. A filter, not a promise: read a report before you send it.
 *
 * ```
 * val clean = Redactor(words = windowTitles).clean(text)
 * ```
 */
class Redactor @JvmOverloads constructor(
    /** Exact words to take out wherever they stand (the titles of the open windows, ...). */
    words: Collection<String> = emptyList(),
    /** Your home folder: becomes "~". */
    home: String? = System.getProperty("user.home"),
    /** Your user name: becomes "<user>". */
    user: String? = System.getProperty("user.name"),
    /** The computer's name: becomes "<host>". */
    host: String? = hostName(),
    /** Log areas whose quoted text is private (a title, a name): becomes "<text>". */
    private val privateAreas: Set<String> = PRIVATE_AREAS,
) {
    // Longest first: a title that holds another title goes whole.
    private val words = words.filter { it.length >= MIN_WORD }.distinct().sortedByDescending { it.length }
    private val home = home?.trimEnd('/')?.takeIf { it.length > 1 }
    private val user = user?.takeIf { it.length >= MIN_NAME && it != "root" }?.let { Regex("(?<![\\w-])" + Regex.escape(it) + "(?![\\w-])") }
    private val host = host?.takeIf { it.length >= MIN_NAME && it != "localhost" }?.let { Regex("(?<![\\w-])" + Regex.escape(it) + "(?![\\w-])") }

    /** [text] (any number of lines) with all that is private taken out. */
    fun clean(text: String): String = text.lineSequence().joinToString("\n", transform = ::cleanLine)

    private fun cleanLine(line: String): String {
        var out = line
        // The area's rule first, as long as the line's words still stand as they were written.
        LOG_LINE.find(out)?.let { head ->
            val area = head.groupValues[1]
            if (area in privateAreas || area.startsWith("plugin-")) out = out.replace(QUOTED, "\"<text>\"")
        }
        for (word in words) out = out.replace(word, "<text>")
        for ((pattern, replacement) in RULES) out = pattern.replace(out, replacement)
        home?.let { out = out.replace(it, "~") }
        out = HOMES.replace(out, "~")
        user?.let { out = it.replace(out, "<user>") }
        host?.let { out = it.replace(out, "<host>") }
        return out
    }

    companion object {
        private const val MIN_WORD = 3
        private const val MIN_NAME = 2

        /** The areas that speak of what is yours: quoted text there is taken out. */
        @JvmField
        val PRIVATE_AREAS: Set<String> = setOf(
            "windows", "focus", "layout", "view", "state", "docks", "capture", "clipboard", "notifications", "notices",
            "bar", "tray", "network", "bluetooth", "media", "launcher", "panels", "taskmanager", "quick", "ipc", "screenshot",
            "session", "walls", "special", "permissions", "clients", "wayland", "xwayland",
        )

        // "12:00:00.000     1.234 I area     the message"
        private val LOG_LINE = Regex("^\\S+\\s+\\S+ [FEWIT] (\\S+)\\s")
        private val QUOTED = Regex("\"[^\"]*\"")
        private val HOMES = Regex("/(?:home|Users)/[^/\\s\"':]+")

        private const val SECRET_NAMES = "pass(?:word|wd|phrase|key)?|pwd|psk|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credentials?|cookie|authorization|auth"
        private val RULES: List<Pair<Regex, String>> = listOf(
            Regex("(?i)\\b(bearer|basic)\\s+[\\w.~+/=-]{8,}") to "$1 <secret>",
            // password=abc, "token": "abc", SECRET_TOKEN=abc, --password abc
            Regex("(?i)([\\w-]*(?:$SECRET_NAMES)[\\w-]*\"?\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)") to "$1<secret>",
            Regex("(?i)(--[\\w-]*(?:$SECRET_NAMES)[\\w-]*\\s+)(\\S+)") to "$1<secret>",
            // scheme://user:password@host
            Regex("(\\w+://)[^/\\s:@]+:[^/\\s@]+@") to "$1<secret>@",
            // What only a key looks like: a long run of letters and digits together.
            Regex("\\b(?=[\\w+/-]*\\d)(?=[\\w+/-]*[a-zA-Z])[\\w+/-]{40,}={0,2}") to "<secret>",
            // Wi-Fi and Bluetooth names, in whatever area they were said.
            Regex("(?i)(wi-?fi|ssid|bluetooth|device)( network)?( name)?(\\s*[=:]?\\s*)\"[^\"]*\"") to "$1$2$3$4\"<text>\"",
            // A window as it shows itself: Window("title", app.id).
            Regex("Window\\(\"(?:[^\"\\\\]|\\\\.)*\"") to "Window(\"<title>\"",
            Regex("(<View \\d+ \\([^)]*\\): )\"[^\"]*\"") to "$1\"<title>\"",
            Regex("(?i)\\btitle\\s*[=:]\\s*(\"[^\"]*\"|'[^']*'|\\S+)") to "title=<title>",
            Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+") to "<mail>",
            Regex("\\b(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}\\b") to "<mac>",
            Regex("\\b(?!127\\.)(?:\\d{1,3}\\.){3}\\d{1,3}\\b") to "<ip>",
        )

        // /etc/hostname, else what the system says; null when neither can be read.
        private fun hostName(): String? = System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() } ?: try {
            java.nio.file.Files.readString(java.nio.file.Path.of("/etc/hostname")).trim().takeIf { it.isNotEmpty() }
                ?: java.nio.file.Files.readString(java.nio.file.Path.of("/proc/sys/kernel/hostname")).trim()
        } catch (_: java.io.IOException) {
            null // (No name to take out.)
        }
    }
}
