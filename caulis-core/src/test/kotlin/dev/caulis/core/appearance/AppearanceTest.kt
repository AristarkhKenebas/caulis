package dev.caulis.core.appearance

import dev.caulis.core.dbus.DBusConnection
import dev.caulis.core.dbus.ObjectPath
import dev.caulis.core.dbus.Variant
import dev.caulis.event.EventLoop
import dev.caulis.render.Color
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Programs are told light or dark: by the portal backend, and in gsettings. */
class AppearanceTest {

    @Test
    fun `gsettings is written only when the scheme changes, the GTK theme with it`() {
        val appearance = Appearance()
        val commands = ArrayList<List<String>>()
        GSettingsAppearance(appearance, "Adwaita" to "Adwaita-dark") { commands += it }.use {
            assertTrue(commands.isEmpty(), "nothing at first: the user's own setting stays")
            appearance.colorScheme = ColorScheme.DARK
            assertEquals(
                listOf(
                    listOf("gsettings", "set", "org.gnome.desktop.interface", "color-scheme", "prefer-dark"),
                    listOf("gsettings", "set", "org.gnome.desktop.interface", "gtk-theme", "Adwaita-dark"),
                ),
                commands,
            )
            commands.clear()
            appearance.colorScheme = ColorScheme.DARK
            assertTrue(commands.isEmpty(), "the same again: nothing")
            appearance.colorScheme = ColorScheme.LIGHT
            assertEquals("prefer-light", commands[0][4])
            assertEquals("Adwaita", commands[1][4])
        }
    }

    @Test
    fun `the portal backend answers ReadAll and Read and says when the scheme changes`() {
        EventLoop().use { loop ->
            val server = DBusConnection.session(loop)
            assumeTrue(server != null, "no session bus")
            val client = DBusConnection.session(loop)!!
            server!!.use { client.use {
                fun until(done: () -> Boolean) {
                    val end = System.nanoTime() + 5_000_000_000
                    while (!done() && System.nanoTime() < end) loop.dispatch(20)
                    check(done()) { "timed out" }
                }
                until { server.uniqueName != null && client.uniqueName != null }
                val appearance = Appearance().apply {
                    colorScheme = ColorScheme.DARK
                    accentColor = Color(0.2f, 0.4f, 1f)
                }
                // (A name of its own: the desktop running now may own the real one.)
                val name = "org.freedesktop.impl.portal.desktop.caulis.test${ProcessHandle.current().pid()}"
                PortalSettings(server, appearance, name).use {
                    var all: Map<Any?, Any?>? = null
                    var scheme: Any? = null
                    var missing: String? = null
                    var nothing: Any? = null
                    val path = ObjectPath(PortalSettings.PATH)
                    @Suppress("UNCHECKED_CAST")
                    client.call(name, path, PortalSettings.INTERFACE, "ReadAll", "as", listOf(listOf("org.freedesktop.*"))) { reply ->
                        val namespaces = reply.body[0] as List<List<Any?>>
                        all = (namespaces.single { it[0] == "org.freedesktop.appearance" }[1] as List<List<Any?>>).associate { it[0] to (it[1] as Variant).value }
                    }
                    @Suppress("UNCHECKED_CAST")
                    client.call(name, path, PortalSettings.INTERFACE, "ReadAll", "as", listOf(listOf("org.gnome.*"))) { reply -> nothing = reply.body[0] }
                    client.call(name, path, PortalSettings.INTERFACE, "Read", "ss", listOf("org.freedesktop.appearance", "color-scheme")) { reply -> scheme = (reply.body[0] as Variant).value }
                    client.call(name, path, PortalSettings.INTERFACE, "Read", "ss", listOf("org.freedesktop.appearance", "no-such-key")) { reply -> missing = reply.errorName }
                    until { all != null && scheme != null && missing != null && nothing != null }
                    assertEquals(1, all!!["color-scheme"])
                    assertEquals(listOf(0.20000000298023224, 0.4000000059604645, 1.0), all["accent-color"])
                    assertEquals(1, scheme)
                    assertEquals("org.freedesktop.portal.Error.NotFound", missing)
                    assertEquals(emptyList<Any?>(), nothing, "no namespace asked for")

                    var changed: List<Any?>? = null
                    client.onSignal(PortalSettings.INTERFACE, "SettingChanged") { changed = it.body }
                    Thread.sleep(200) // (the match rule reaches the bus first)
                    loop.dispatch(50)
                    appearance.colorScheme = ColorScheme.LIGHT
                    until { changed != null }
                    assertEquals("org.freedesktop.appearance", changed!![0])
                    assertEquals("color-scheme", changed[1])
                    assertEquals(2, (changed[2] as Variant).value)
                }
            } }
        }
    }
}
