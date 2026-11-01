package dev.caulis.wm

import dev.caulis.core.NoticeLevel
import dev.caulis.log.CrashReports
import dev.caulis.log.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * What happens when something stops: a report file (see [CrashReports]) with versions, the
 * last lines of the log and the state, for an exception nothing caught on any thread, a start
 * that failed, and the watchdog giving up. The next start shows a card with its path.
 */
internal object CrashHandling {

    private val log = Logger("crash")

    // The configuration running or starting now: it says where reports go.
    @Volatile
    private var current: Caulis? = null

    // The thread that runs the compositor: when it stops, all has stopped.
    @Volatile
    private var mainThread: Thread? = null
    private var installed = false

    // What was reported (an error goes through several hands on its way up), and the same
    // failure on another thread: a report is enough.
    private val reported = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Throwable, Boolean>())
    private val gists = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Before the start: reports are written from now on, as [config] says. */
    @Synchronized
    fun install(config: Caulis) {
        current = config
        mainThread = Thread.currentThread()
        apply(config)
        if (installed) return
        installed = true
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            uncaught(thread, error)
            before?.uncaughtException(thread, error)
            if (thread === mainThread) endProgram()
        }
    }

    /** The settings of [config] taken on (also after a reload). */
    fun apply(config: Caulis) {
        current = config
        CrashReports.folder = if (config.crashReports) config.crashReportFolder ?: defaultFolder(config) else null
        CrashReports.lastLines = config.crashReportLines
        CrashReports.keep = config.crashReportsKept
        CrashReports.fact("Caulis", IpcServer.CAULIS_VERSION)
        CrashReports.fact("Configuration folder", config.configFolder.toString())
        CrashReports.privateWords = {
            // (From another thread, while all may be falling apart: a failure here is only less taken out.)
            current?.running?.desktop?.allWindows.orEmpty().toList().mapNotNull { it.title }
        }
        CrashReports.state = { current?.running?.desktop?.describe() }
    }

    private fun uncaught(thread: Thread, error: Throwable) {
        log.error("Nothing caught this on the thread \"${thread.name}\": $error", error)
        val file = report("The thread \"${thread.name}\" stopped: $error", error, thread) ?: return
        // The compositor goes on when it was a helper: said on screen too.
        if (thread !== mainThread) current?.let { config -> config.running?.loop?.post { showCard(config, file) } }
    }

    /**
     * The compositor stopped with [error] (its start, or its loop). Says where the report went
     * on the console too (seen when started from a text console). The file, or null.
     */
    fun stopped(what: String, error: Throwable?): Path? {
        val file = report(what, error, Thread.currentThread())
        if (file != null) System.err.println("\nA report for a bug report: ${shown(file)}")
        return file
    }

    /** The watchdog gives up: with every thread's stack (where it hangs). Waits a moment at most. */
    fun stuck(millis: Long) {
        val writing = Thread.ofPlatform().daemon().name("caulis-crash-report").start {
            CrashReports.write("The main loop did not respond for $millis ms, so the compositor ended itself", null, mainThread, stacks = true)
        }
        writing.join(STUCK_REPORT_MILLIS)
    }

    private fun report(what: String, error: Throwable?, thread: Thread): Path? {
        if (error != null) {
            synchronized(reported) { if (!reported.add(error)) return null }
            val first = error.stackTrace.firstOrNull()
            if (!gists.add("${error.javaClass.name}|$first") || gists.size > MAX_REPORTS) return null
        }
        val file = CrashReports.write(what, error, thread) ?: return null
        val hook = current?.onCrashReport
        if (hook != null) {
            try {
                hook(file)
            } catch (e: Exception) {
                log.warn("Your onCrashReport failed: $e", e)
            }
        }
        return file
    }

    /** At the start: a report no card has told of yet (an earlier run that ended badly). */
    fun tellOfLast(config: Caulis, desktop: dev.caulis.core.Desktop) {
        val folder = CrashReports.folder ?: return
        val last = try {
            CrashReports.list(folder).lastOrNull()
        } catch (e: java.io.IOException) {
            return log.warn("The crash reports in $folder cannot be read: $e")
        } ?: return
        val mark = folder.resolve(".told")
        try {
            if (Files.exists(mark) && Files.readString(mark).trim() >= last.fileName.toString()) return
            Files.writeString(mark, last.fileName.toString())
        } catch (e: java.io.IOException) {
            log.warn("Which crash report was told of cannot be kept in $mark: $e")
        }
        log.info { "The last run left a report: $last" }
        card(config, desktop, last)
    }

    private fun showCard(config: Caulis, file: Path) {
        val desktop = config.running?.desktop ?: return
        card(config, desktop, file)
    }

    private fun card(config: Caulis, desktop: dev.caulis.core.Desktop, file: Path) {
        val words = config.crashNotice ?: return
        desktop.notify(words.replace("{file}", shown(file)), NoticeLevel.WARNING, config.title)
    }

    // The path as you would type it.
    private fun shown(file: Path): String {
        val home = System.getProperty("user.home")
        return file.toString().let { if (home != null && it.startsWith("$home/")) "~" + it.removePrefix(home) else it }
    }

    private fun defaultFolder(config: Caulis): Path {
        val state = System.getenv("XDG_STATE_HOME")?.takeIf { it.startsWith("/") }
            ?: "${System.getProperty("user.home")}/.local/state"
        return Path.of(state, config.configName, "crashes")
    }

    // The compositor's thread is gone, whatever other threads may keep the program alive: ended,
    // with the program's shutdown hooks if they are quick.
    private fun endProgram() {
        Thread.ofPlatform().daemon().name("caulis-crash-exit").start { Runtime.getRuntime().exit(1) }
        Thread.sleep(EXIT_WAIT_MILLIS)
        Runtime.getRuntime().halt(1)
    }

    private const val STUCK_REPORT_MILLIS = 2000L
    private const val EXIT_WAIT_MILLIS = 3000L
    private const val MAX_REPORTS = 8
}
