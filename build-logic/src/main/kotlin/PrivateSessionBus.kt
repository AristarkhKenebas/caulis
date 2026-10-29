import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A D-Bus session bus of the tests' own (dbus-daemon, started on first use, ended with the
 * build): tests that take names or talk over the session bus never reach the desktop's (a
 * test compositor on the user's bus once took names of the live session). Without
 * dbus-daemon, an address where there is no bus (those tests are skipped then).
 */
abstract class PrivateSessionBus : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private var daemon: Process? = null
    private var dir: File? = null

    /** The bus's address, for DBUS_SESSION_BUS_ADDRESS. */
    @get:Synchronized
    val address: String by lazy { start() }

    private fun start(): String {
        val none = "unix:path=/nonexistent/caulis-test-session-bus"
        val exe = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "dbus-daemon") }.firstOrNull { it.canExecute() }
            ?: File("/usr/bin/dbus-daemon").takeIf { it.canExecute() }
            ?: return none
        val folder = kotlin.io.path.createTempDirectory("caulis-test-bus").toFile().also { dir = it }
        val socket = File(folder, "bus")
        val config = File(folder, "session.conf")
        config.writeText(
            """
            <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
            <busconfig>
              <type>session</type>
              <listen>unix:path=${socket.absolutePath}</listen>
              <auth>EXTERNAL</auth>
              <policy context="default"><allow send_destination="*" eavesdrop="true"/><allow eavesdrop="true"/><allow own="*"/></policy>
            </busconfig>
            """.trimIndent(),
        )
        val p = ProcessBuilder(exe.absolutePath, "--config-file=${config.absolutePath}", "--nofork", "--nopidfile")
            .redirectErrorStream(true).redirectOutput(File(folder, "daemon.log")).start()
        daemon = p
        // (Ready once its socket is there.)
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!socket.exists() && p.isAlive && System.nanoTime() < until) Thread.sleep(20)
        return if (socket.exists()) "unix:path=${socket.absolutePath}" else none
    }

    override fun close() {
        daemon?.let { it.destroy(); it.waitFor(2, TimeUnit.SECONDS); if (it.isAlive) it.destroyForcibly() }
        dir?.deleteRecursively()
    }
}
