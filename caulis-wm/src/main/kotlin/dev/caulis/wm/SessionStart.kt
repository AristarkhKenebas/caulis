package dev.caulis.wm

import dev.caulis.log.Logger
import java.nio.file.Path

/**
 * The start of a session, once per compositor: the dark screen until it is warm and the
 * desktop fading in ([Reveal]), the startup sound ([StartupSound]), then the programs of
 * [Autostart] (and those of [Caulis.startLater]) one after another. Made with the running
 * compositor (so its first frame is dark already), begun right before the configuration's
 * onStart; all on the compositor's thread.
 */
internal class SessionStart(
    private val running: Running,
    startProgram: (command: List<String>, directory: java.io.File?, env: Map<String, String>) -> Process?,
) : AutoCloseable {

    private val log = Logger("autostart")
    private val config get() = running.config

    private fun later(millis: Long, action: () -> Unit): AutoCloseable {
        val timer = running.loop.after(millis) { running.safely("the start") { action() } }
        return AutoCloseable { timer.cancel() }
    }

    private val runner = AutostartRunner(startProgram, ::later, { action -> running.loop.post { running.safely("autostart") { action() } } })

    /** True once the desktop is shown (it began to fade in). */
    var revealed = false
        private set

    // Started headless: the startup sound only when it says whenHeadless.
    private val headless = config.backendInUse == Caulis.Backend.HEADLESS

    private var begun = false
    private var autostartBegun = false

    // The desktop entries started (read once, again only when they are turned on later).
    private var desktopExtra: List<Autostart.Entry> = emptyList()
    private var desktopEntriesRead = false

    private val curtain = RevealCurtain(
        config.reveal, config.reveal.enabled, running.desktop, ::later, { running.loop.post(it) },
        revealing = ::revealing, revealed = ::shown, restoreCursor = { running.applyCursor() },
    )

    /** The start begins (right before onStart): waiting until warm, the sound, maybe the programs. */
    fun begin() {
        if (begun) return
        begun = true
        for ((command, directory, gap) in config.pendingStarts) runner.startLater(command, directory, gap)
        config.pendingStarts.clear()
        val sound = config.startupSound
        if (sound.enabled && (!headless || sound.whenHeadless)) {
            if (sound.at == SoundMoment.START) playSound(null) else SoundPlayer.prepare(sound, soundFolder())
        }
        curtain.begin()
        if (!config.autostart.afterReveal) startAutostart()
    }

    // The desktop begins to fade in.
    private fun revealing() {
        if (revealed) return
        revealed = true
        val sound = config.startupSound
        if (sound.enabled && sound.at == SoundMoment.REVEAL && (!headless || sound.whenHeadless)) playSound(null)
        for (action in config.revealActions.toList()) running.safely("onReveal") { action(config) }
    }

    // The fade is over: the programs start (not while it fades: it would stutter).
    private fun shown() {
        startAutostart()
    }

    private fun startAutostart() {
        if (autostartBegun) return
        autostartBegun = true
        runner.begin(config.autostart, desktopEntries())
    }

    /** The configuration changed (a reload): new entries start, those taken away are no longer watched. */
    fun reconfigure() {
        runner.reconfigure(config.autostart, desktopEntries())
    }

    // The XDG autostart entries, when asked for (read once).
    private fun desktopEntries(): List<Autostart.Entry> {
        val autostart = config.autostart
        if (!autostart.desktopEntries) return emptyList()
        if (!desktopEntriesRead) {
            desktopEntriesRead = true
            desktopExtra = running.safely("reading the autostart folders", emptyList()) {
                DesktopEntries.read(autostart.desktopEntryFolders ?: DesktopEntries.folders(), config.desktopName)
            }
            log.info { "${desktopExtra.size} programs of the autostart folders" }
        }
        return desktopExtra
    }

    /** [command] in the queue (see [Caulis.startLater]). */
    fun startLater(command: List<String>, directory: java.io.File?, gapMillis: Long?) = runner.startLater(command, directory, gapMillis)

    /** Each program of the start and how it is. */
    fun status(): List<String> = runner.status()

    /** Work to wait for before the desktop shows (see [Caulis.whenWarm]). */
    fun whenWarm(name: String, work: (done: () -> Unit) -> Unit) {
        if (curtain.shown || !config.reveal.enabled) return running.safely("whenWarm $name") { work {} }
        val hold = curtain.hold(name)
        try {
            work { hold.close() }
        } catch (e: Exception) {
            log.warn("Your work before the reveal ($name) failed: $e")
            hold.close()
        }
    }

    /** Something to wait for before the desktop shows (see [Caulis.holdReveal]). */
    fun hold(name: String): AutoCloseable = curtain.hold(name)

    /** Plays the startup sound ([preset]: another preset than the configured one). */
    fun playSound(preset: String?) {
        val sound = config.startupSound
        SoundPlayer.playLater(sound, soundFolder(), resolve = { name -> config.file(AutostartRunner.expandHome(name)) }, env = running.programEnvironment(), presetOverride = preset)
    }

    // Where the presets are kept: ~/.cache/<configName>/sounds.
    private fun soundFolder(): Path = config.startupSound.cacheFolder ?: run {
        val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.startsWith("/") } ?: (System.getProperty("user.home") + "/.cache")
        Path.of(cache, config.configName, "sounds")
    }

    override fun close() {
        runner.close()
        curtain.close()
    }
}
