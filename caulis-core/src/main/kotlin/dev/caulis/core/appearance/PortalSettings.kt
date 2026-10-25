package dev.caulis.core.appearance

import dev.caulis.core.dbus.DBusConnection
import dev.caulis.core.dbus.DBusError
import dev.caulis.core.dbus.DBusMethod
import dev.caulis.core.dbus.Variant
import dev.caulis.log.Logger

/**
 * Tells programs the [appearance] the way xdg-desktop-portal asks its backends
 * (`org.freedesktop.impl.portal.Settings`: `org.freedesktop.appearance` `color-scheme`,
 * `accent-color`, `contrast`), on the session [bus] under [busName], and again whenever it
 * changes. The portal uses it where it is told to (a `caulis.portal` file and a
 * `<desktop>-portals.conf` line: see the guide); close it to stop.
 */
class PortalSettings(
    private val bus: DBusConnection,
    private val appearance: Appearance,
    val busName: String = BUS_NAME,
) : AutoCloseable {

    private val log = Logger("appearance")

    private val methods = mapOf(
        "ReadAll" to DBusMethod("as", "a{sa{sv}}") { call ->
            @Suppress("UNCHECKED_CAST")
            val wanted = call.body[0] as List<String>
            listOf(if (wanted.isEmpty() || wanted.any { matches(it, NAMESPACE) }) mapOf(NAMESPACE to values()) else emptyMap())
        },
        "Read" to DBusMethod("ss", "v") { call ->
            val namespace = call.body[0] as String
            val key = call.body[1] as String
            val value = values()[key]?.takeIf { namespace == NAMESPACE }
                ?: throw DBusError("org.freedesktop.portal.Error.NotFound", "there is no $namespace $key")
            listOf(value)
        },
    )

    private val properties = mapOf(
        "Get" to DBusMethod("ss", "v") { call ->
            if (call.body[0] != INTERFACE || call.body[1] != "version") throw DBusError("org.freedesktop.DBus.Error.UnknownProperty", "no ${call.body[0]}.${call.body[1]}")
            listOf(Variant("u", VERSION))
        },
        "GetAll" to DBusMethod("s", "a{sv}") { call ->
            listOf(if (call.body[0] == INTERFACE) mapOf("version" to Variant("u", VERSION)) else emptyMap())
        },
    )

    private val exports = listOf(bus.export(PATH, INTERFACE, methods), bus.export(PATH, "org.freedesktop.DBus.Properties", properties))
    private var last = values()
    private val listening = appearance.onChange { changed() }

    init {
        bus.requestName(busName) { ours -> if (ours) log.info { "Programs can ask the portal for the appearance ($busName)" } }
    }

    // What the portal is told now (only what is set: an accent colour of none is not said).
    private fun values(): Map<String, Variant> = buildMap {
        put("color-scheme", Variant("u", appearance.colorScheme.portalValue))
        appearance.accentColor?.let { put("accent-color", Variant("(ddd)", listOf(it.red.toDouble(), it.green.toDouble(), it.blue.toDouble()))) }
        put("contrast", Variant("u", 0))
    }

    private fun changed() {
        val now = values()
        for ((key, value) in now) {
            if (last[key]?.let { it.signature == value.signature && it.value == value.value } == true) continue
            log.trace { "Portal: $key changed" }
            bus.emitSignal(PATH, INTERFACE, "SettingChanged", "ssv", listOf(NAMESPACE, key, value))
        }
        last = now
    }

    override fun close() {
        listening.close()
        exports.forEach { it.close() }
    }

    companion object {
        /** The bus name the `caulis.portal` file names. */
        const val BUS_NAME = "org.freedesktop.impl.portal.desktop.caulis"
        const val PATH = "/org/freedesktop/portal/desktop"
        const val INTERFACE = "org.freedesktop.impl.portal.Settings"
        const val NAMESPACE = "org.freedesktop.appearance"
        private const val VERSION = 1

        /** What goes into `/usr/share/xdg-desktop-portal/portals/caulis.portal` for the portal to find this. */
        const val PORTAL_FILE = "[portal]\nDBusName=$BUS_NAME\nInterfaces=$INTERFACE;\n"

        // A namespace as the portal asks for it: exact, or "org.freedesktop.*".
        private fun matches(pattern: String, namespace: String) =
            pattern == namespace || (pattern.endsWith(".*") && namespace.startsWith(pattern.removeSuffix("*")))
    }
}
