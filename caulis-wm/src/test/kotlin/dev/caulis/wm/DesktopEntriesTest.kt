package dev.caulis.wm

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The XDG autostart folders' .desktop files, as other desktops read them. */
class DesktopEntriesTest {

    private val desktops = setOf("newm", "wlroots")

    private fun entry(text: String, installed: Set<String> = emptySet()) =
        DesktopEntries.entryOf("test", "[Desktop Entry]\nType=Application\n$text", desktops) { it in installed }

    @Test
    fun `Exec becomes the command, field codes left out, quotes kept whole`() {
        assertEquals(listOf("nm-applet", "--indicator"), entry("Exec=nm-applet --indicator %U")!!.command)
        assertEquals(listOf("sh", "-c", "echo \"hi there\""), entry("Exec=sh -c \"echo \\\\\"hi there\\\\\"\"")!!.command)
        assertEquals(listOf("app", "100%"), entry("Exec=app 100%%")!!.command)
        assertEquals("/tmp", entry("Exec=app\nPath=/tmp")!!.dir)
        assertEquals("test", entry("Exec=app")!!.name, "named after its file")
        // By the spec: %% is a % (also before a letter), "" an empty argument kept, %i the icon, %c the name.
        assertEquals(listOf("foo", "--x=%f", "a b", ""), DesktopEntries.execWords("foo --x=%%f %u \"a b\" \"\""))
        assertEquals(listOf("app", "--icon", "app-icon", "--title=App"), DesktopEntries.execWords("app %i --title=%c %F", icon = "app-icon", name = "App"))
        assertEquals(listOf("app", "%u"), DesktopEntries.execWords("app \"%u\""), "nothing is a field code inside quotes")
        assertNull(entry("Exec=htop\nTerminal=true"), "a terminal program is not started without its terminal")
    }

    @Test
    fun `hidden, other desktops' and missing programs are left out`() {
        assertNull(entry("Exec=a\nHidden=true"))
        assertNull(entry("Exec=a\nX-GNOME-Autostart-enabled=false"))
        assertNull(entry("Exec=a\nOnlyShowIn=GNOME;KDE;"))
        assertEquals(listOf("a"), entry("Exec=a\nOnlyShowIn=GNOME;newm;")!!.command)
        assertNull(entry("Exec=a\nNotShowIn=wlroots;"))
        assertNull(entry("Exec=a\nTryExec=missing-program"))
        assertEquals(listOf("a"), entry("Exec=a\nTryExec=there", installed = setOf("there"))!!.command)
        assertNull(entry("NoExec=1"))
        assertNull(DesktopEntries.entryOf("x", "[Desktop Entry]\nType=Link\nExec=a", desktops) { true })
    }

    @Test
    fun `the user's folder hides the system's file of the same name`() {
        val root = Files.createTempDirectory("caulis-xdg")
        try {
            val user = Files.createDirectories(root.resolve("user/autostart"))
            val system = Files.createDirectories(root.resolve("system/autostart"))
            Files.writeString(system.resolve("tray.desktop"), "[Desktop Entry]\nType=Application\nExec=tray --system\n")
            Files.writeString(system.resolve("off.desktop"), "[Desktop Entry]\nType=Application\nExec=off\n")
            Files.writeString(user.resolve("off.desktop"), "[Desktop Entry]\nHidden=true\n")
            Files.writeString(user.resolve("mine.desktop"), "[Desktop Entry]\nType=Application\nName=Mine\nName[de]=Meins\nExec=mine\n")
            Files.writeString(user.resolve("notes.txt"), "not a desktop entry")
            val entries = DesktopEntries.read(listOf(user, system), "newm:wlroots") { true }
            assertEquals(listOf("mine" to listOf("mine"), "tray" to listOf("tray", "--system")), entries.map { it.name to it.command })

            val env = mapOf("HOME" to "/home/u", "XDG_CONFIG_DIRS" to "/etc/a:/etc/b")
            assertEquals(listOf("/home/u/.config/autostart", "/etc/a/autostart", "/etc/b/autostart"), DesktopEntries.folders { env[it] }.map { it.toString() })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
