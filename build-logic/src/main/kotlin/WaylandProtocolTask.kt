import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Generates Kotlin classes from Wayland protocol XML files (our own wayland-scanner).
 *
 * For every interface, e.g. `wl_surface`, one file `WlSurface.kt` is written with:
 *  - `interface Requests` — what the client can ask; the compositor implements it;
 *  - `sendXxx(...)` functions — the events the compositor can send;
 *  - constants for the enums (`WlSurface.Error.INVALID_SCALE`);
 *  - the code that reads and checks each request (`dispatch`).
 */
@CacheableTask
abstract class WaylandProtocolTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val protocols: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val parsed = protocols.files.sortedBy { it.name }.map { ProtocolParser.parse(it) }

        // Interfaces may refer to interfaces of other files (xdg_surface uses wl_surface).
        val serverClasses = HashMap<String, String>()
        val clientClasses = HashMap<String, String>()
        for (protocol in parsed) {
            for (iface in protocol.interfaces) {
                serverClasses[iface.name] = "${protocol.packageName}.${iface.className}"
                clientClasses[iface.name] = "${protocol.clientPackageName}.${iface.className}"
            }
        }

        val root = outputDir.get().asFile
        for (protocol in parsed) {
            // Server side: objects of our clients.
            val serverDir = root.resolve(protocol.packageName.replace('.', '/'))
            serverDir.deleteRecursively()
            serverDir.mkdirs()
            for (iface in protocol.interfaces) {
                serverDir.resolve("${iface.className}.kt").writeText(KotlinWriter(protocol, iface, serverClasses).write())
            }
            // Client side: objects in another compositor we connect to.
            val clientDir = root.resolve(protocol.clientPackageName.replace('.', '/'))
            clientDir.deleteRecursively()
            clientDir.mkdirs()
            for (iface in protocol.interfaces) {
                clientDir.resolve("${iface.className}.kt").writeText(ClientKotlinWriter(protocol, iface, clientClasses).write())
            }
        }
    }
}

// --- model ---------------------------------------------------------------------------

internal class ProtocolModel(val name: String, val fileName: String, val interfaces: List<InterfaceModel>) {
    private val shortName = name.replace("_", "").replace("-", "").lowercase()
    val packageName = "dev.caulis.protocol.$shortName"
    val clientPackageName = "dev.caulis.protocol.client.$shortName"
}

internal class InterfaceModel(
    val name: String,
    val version: Int,
    val summary: String,
    val description: String,
    val requests: List<MessageModel>,
    val events: List<MessageModel>,
    val enums: List<EnumModel>,
) {
    val className = name.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
}

internal class MessageModel(
    val name: String,
    val since: Int,
    val isDestructor: Boolean,
    val summary: String,
    val description: String,
    val args: List<ArgModel>,
)

internal class ArgModel(
    val name: String,
    val type: String,
    val interfaceName: String?,
    val allowNull: Boolean,
    val summary: String,
)

internal class EnumModel(val name: String, val isBitfield: Boolean, val summary: String, val entries: List<EntryModel>)

internal class EntryModel(val name: String, val value: Long, val summary: String)

// --- parsing -------------------------------------------------------------------------

internal object ProtocolParser {

    fun parse(file: File): ProtocolModel {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = document.documentElement
        val interfaces = root.children("interface").map { element ->
            InterfaceModel(
                name = element.getAttribute("name"),
                version = element.getAttribute("version").toInt(),
                summary = element.summary(),
                description = element.descriptionText(),
                requests = element.children("request").map(::message),
                events = element.children("event").map(::message),
                enums = element.children("enum").map(::enum),
            )
        }
        return ProtocolModel(root.getAttribute("name"), file.name, interfaces)
    }

    private fun message(element: Element) = MessageModel(
        name = element.getAttribute("name"),
        since = element.getAttribute("since").ifEmpty { "1" }.toInt(),
        isDestructor = element.getAttribute("type") == "destructor",
        summary = element.summary(),
        description = element.descriptionText(),
        args = element.children("arg").map { arg ->
            ArgModel(
                name = arg.getAttribute("name"),
                type = arg.getAttribute("type"),
                interfaceName = arg.getAttribute("interface").ifEmpty { null },
                allowNull = arg.getAttribute("allow-null") == "true",
                summary = arg.getAttribute("summary"),
            )
        },
    )

    private fun enum(element: Element) = EnumModel(
        name = element.getAttribute("name"),
        isBitfield = element.getAttribute("bitfield") == "true",
        summary = element.summary(),
        entries = element.children("entry").map { entry ->
            val raw = entry.getAttribute("value")
            val value = if (raw.startsWith("0x")) raw.substring(2).toLong(16) else raw.toLong()
            EntryModel(entry.getAttribute("name"), value, entry.getAttribute("summary"))
        },
    )

    private fun Element.children(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
    }

    private fun Element.summary(): String =
        children("description").firstOrNull()?.getAttribute("summary") ?: getAttribute("summary")

    private fun Element.descriptionText(): String =
        children("description").firstOrNull()?.textContent?.trimIndent()?.trim() ?: ""
}

// --- Kotlin output -------------------------------------------------------------------

// The arguments as they appear in a "wayland" trace line: name=${Wire.show(value)}, ...
internal fun traceArgs(pairs: List<Pair<String, String>>): String =
    pairs.joinToString(", ") { (label, value) -> label + "=" + "$" + "{Wire.show(" + value + ")}" }
