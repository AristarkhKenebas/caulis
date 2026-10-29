package dev.caulis.wm

/** What a permission rule says: the program may use the feature, is asked about it, or may not. */
enum class PermissionMode {
    ALLOW,
    ASK,
    DENY,
    ;

    companion object {
        /** "allow", "ask" or "deny" (case aside). */
        fun of(text: String): PermissionMode = entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("a permission is \"allow\", \"ask\" or \"deny\", not \"$text\"")
    }
}

/** The person's answer to a [PermissionQuestion]. */
enum class PermissionAnswer {
    /** No, until the program ends. */
    DENY,

    /** Yes, until the program ends. */
    ALLOW_WHILE_RUNNING,

    /** Yes, for this program until the compositor quits. */
    ALLOW_UNTIL_QUIT,
}

/**
 * A program wants a feature a permission rule says to ask about: show it to the person and
 * [answer] once (later answers are ignored). [program] is its executable's path
 * ("/usr/bin/grim"), [name] its short name ("grim").
 */
class PermissionQuestion internal constructor(
    val program: String,
    val name: String,
    val pid: Int,
    val feature: String,
    private val onAnswer: (PermissionAnswer) -> Unit,
) {
    private var answered = false

    fun answer(answer: PermissionAnswer) {
        if (answered) return
        answered = true
        onAnswer(answer)
    }

    override fun toString() = "$name ($program) wants \"$feature\""
}

/** One rule: programs whose path (or name) matches [program] get [mode] for [feature]. */
class PermissionRule(val program: Regex, val feature: String, val mode: PermissionMode) {
    /** Whether the program at [path] (its executable), called [name], is meant. */
    fun matches(path: String, name: String): Boolean = program.matches(path) || program.matches(name)

    override fun toString() = "${program.pattern} $feature ${mode.name.lowercase()}"
}

/**
 * Which programs may use the features that see or steer more than their own windows — as
 * Hyprland's permissions:
 *
 * ```kotlin
 * permissions {
 *     rule("/usr/bin/grim", "screencopy", "allow")       // a path (a regular expression)...
 *     rule("wl-screenrec", "screencopy", "ask")          // ...or a program's name
 *     rule(".*", "clipboard", "deny")                     // everything else: no clipboard managers
 *     default("screencopy", "ask")                        // no rule for a program: ask it
 * }
 * ```
 *
 * The first rule that matches decides; without one, the feature's [default]; without that,
 * the [privileged][Caulis.privilegedClients] rule, as before (everything allowed). "ask" is
 * for "screencopy" (its pictures wait for the answer, see [Caulis.askPermission]); the other
 * features are decided as a program connects, so they are "allow" or "deny". Asked for each
 * connection, so a change counts for programs started after it.
 */
class Permissions {
    /** The rules, first match first. */
    val rules: MutableList<PermissionRule> = ArrayList()

    /** What a feature gets when no rule matches. */
    val defaults: MutableMap<String, PermissionMode> = LinkedHashMap()

    /** Programs matching [program] (a regular expression for the path or the name) get [mode] for [feature]. */
    fun rule(program: String, feature: String, mode: PermissionMode) {
        check(feature, mode)
        rules += PermissionRule(Regex(program), feature, mode)
    }

    /** [rule] with "allow", "ask" or "deny". */
    fun rule(program: String, feature: String, mode: String) = rule(program, feature, PermissionMode.of(mode))

    /** What [feature] gets when no rule matches. */
    fun default(feature: String, mode: PermissionMode) {
        check(feature, mode)
        defaults[feature] = mode
    }

    /** [default] with "allow", "ask" or "deny". */
    fun default(feature: String, mode: String) = default(feature, PermissionMode.of(mode))

    /** No rules and no defaults: as before (the privileged rule). */
    fun clear() {
        rules.clear()
        defaults.clear()
    }

    /** What the rules say for the program at [path] called [name], or null: nothing (the privileged rule). */
    fun modeOf(path: String, name: String, feature: String): PermissionMode? =
        rules.firstOrNull { it.feature == feature && it.matches(path, name) }?.mode ?: defaults[feature]

    private fun check(feature: String, mode: PermissionMode) {
        require(feature in FEATURES) { "there is no permission \"$feature\" (there are: ${FEATURES.joinToString()})" }
        require(mode != PermissionMode.ASK || feature in ASKABLE) { "\"$feature\" cannot be asked about (only ${ASKABLE.joinToString()}): allow or deny" }
    }

    companion object {
        /**
         * The features: screen pictures and recordings, the list of windows (docks, taskbars),
         * the clipboard of others (clipboard managers), panels (bars, docks, wallpapers,
         * launchers), locking the screen, arranging and switching monitors, the colour
         * (gamma) of monitors (night light programs).
         */
        val FEATURES: List<String> = listOf("screencopy", "window-list", "clipboard", "panels", "lock", "monitors", "gamma")

        /** The features "ask" fits (asked when used, not when the program connects). */
        val ASKABLE: Set<String> = setOf("screencopy")
    }
}

/**
 * The [Permissions] at work: decides for each program and feature, asks the person where a
 * rule says "ask" (through [Caulis.askPermission]) and keeps the answers — "while running" for
 * that process (its id and start time, so a new process with the same id is asked again),
 * "until quit" for its program's path.
 */
internal class PermissionKeeper(
    private val config: () -> Caulis,
    private val answered: () -> Unit,
    private val privileged: (dev.caulis.protocol.Client) -> Boolean,
) {
    private val log = dev.caulis.log.Logger("permissions")

    // A running process: its id and when it started (ids are used again).
    private data class Process(val pid: Int, val started: String)

    private val whileRunning = HashMap<Pair<Process, String>, Boolean>()
    private val untilQuit = HashMap<Pair<String, String>, Boolean>()
    private val asked = HashSet<Pair<Process, String>>()

    /** Whether [client] may use [feature] (asked as it connects; "ask": yes, its use waits for the answer). */
    fun mayUse(client: dev.caulis.protocol.Client, feature: String): Boolean {
        // (No rules at all, the usual: nothing to read about the program.)
        if (config().permissions.let { it.rules.isEmpty() && it.defaults.isEmpty() }) return privileged(client)
        val path = pathOf(client.pid) ?: client.program
        return when (config().permissions.modeOf(path, client.program, feature)) {
            PermissionMode.ALLOW -> true
            PermissionMode.DENY -> false.also { log.info { "${client.name} may not use \"$feature\" (permissions)" } }
            PermissionMode.ASK -> true
            null -> privileged(client)
        }
    }

    /** Whether [client] may take a picture now: an "ask" without an answer yet starts the question (and says yes: [asking] holds it). */
    fun captureAllowed(client: dev.caulis.protocol.Client): Boolean {
        val feature = "screencopy"
        if (config().permissions.let { it.rules.isEmpty() && it.defaults.isEmpty() }) return true
        val path = pathOf(client.pid) ?: client.program
        return when (config().permissions.modeOf(path, client.program, feature)) {
            PermissionMode.DENY -> false
            // (A process that cannot be told apart from a later one is not asked: no.)
            PermissionMode.ASK -> if (processOf(client.pid) == null) false else answerOf(client, path, feature) ?: true.also { ask(client, path, feature) }
            else -> true
        }
    }

    /** Whether the person is being asked about [client] and [feature] right now. */
    fun asking(client: dev.caulis.protocol.Client, feature: String): Boolean =
        asked.isNotEmpty() && processOf(client.pid)?.let { (it to feature) in asked } == true

    private fun answerOf(client: dev.caulis.protocol.Client, path: String, feature: String): Boolean? =
        processOf(client.pid)?.let { whileRunning[it to feature] } ?: untilQuit[path to feature]

    private fun ask(client: dev.caulis.protocol.Client, path: String, feature: String) {
        val process = processOf(client.pid) ?: return
        if (!asked.add(process to feature)) return
        fun done(answer: PermissionAnswer) {
            asked -= process to feature
            when (answer) {
                PermissionAnswer.DENY -> whileRunning[process to feature] = false
                PermissionAnswer.ALLOW_WHILE_RUNNING -> whileRunning[process to feature] = true
                PermissionAnswer.ALLOW_UNTIL_QUIT -> untilQuit[path to feature] = true
            }
            log.info { "${client.program}: \"$feature\" ${answer.name.lowercase().replace('_', ' ')} (the person's answer)" }
            answered()
        }
        val asker = config().askPermission
        if (asker == null) {
            log.warn("${client.program} wants \"$feature\" and the rule says ask, but there is no one to ask: no (permissions)")
            done(PermissionAnswer.DENY)
            return
        }
        log.info { "Asking whether ${client.program} ($path) may use \"$feature\"" }
        try {
            asker(PermissionQuestion(path, client.program, client.pid, feature, ::done))
        } catch (e: Exception) {
            log.warn("Asking about a permission failed: $e", e)
            done(PermissionAnswer.DENY)
        }
    }

    // The process' executable ("/usr/bin/grim"), or null if it cannot be read.
    private fun pathOf(pid: Int): String? = try {
        java.nio.file.Files.readSymbolicLink(java.nio.file.Path.of("/proc/$pid/exe")).toString()
    } catch (_: Exception) {
        null
    }

    private fun processOf(pid: Int): Process? = try {
        // (Field 22 of /proc/<pid>/stat is its start time; the name before it may hold spaces.)
        val stat = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/$pid/stat"))
        Process(pid, stat.substringAfterLast(')').trim().split(' ').getOrElse(19) { "" })
    } catch (_: Exception) {
        null
    }
}
