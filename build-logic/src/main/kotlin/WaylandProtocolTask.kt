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
        val classes = HashMap<String, String>()
        for (protocol in parsed) {
            for (iface in protocol.interfaces) classes[iface.name] = "${protocol.packageName}.${iface.className}"
        }

        val root = outputDir.get().asFile
        for (protocol in parsed) {
            val dir = root.resolve(protocol.packageName.replace('.', '/'))
            dir.deleteRecursively()
            dir.mkdirs()
            for (iface in protocol.interfaces) {
                dir.resolve("${iface.className}.kt").writeText(KotlinWriter(protocol, iface, classes).write())
            }
        }
    }
}

// --- model ---------------------------------------------------------------------------

internal class ProtocolModel(val name: String, val fileName: String, val interfaces: List<InterfaceModel>) {
    val packageName = "dev.tessera.protocol." + name.replace("_", "").replace("-", "").lowercase()
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

internal class KotlinWriter(
    private val protocol: ProtocolModel,
    private val iface: InterfaceModel,
    private val classes: Map<String, String>,
) {
    private val out = StringBuilder()
    private val name = iface.className

    fun write(): String {
        line("// Generated from ${protocol.fileName}. Do not edit: run ./gradlew :tessera-protocol:generateProtocols")
        line("@file:Suppress(\"unused\", \"UNUSED_PARAMETER\", \"RedundantVisibilityModifier\")")
        line()
        line("package ${protocol.packageName}")
        line()
        imports()
        doc("", iface.summary, iface.description)
        line("public class $name private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {")
        line()
        line("    override val type: ResourceType<$name> get() = Companion")
        if (iface.requests.isNotEmpty()) {
            line()
            line("    /** Handles the requests clients send to this object; set by the code that implements ${iface.name}. */")
            line("    public var requests: Requests? = null")
            line()
            requestsInterface()
        }
        for ((opcode, event) in iface.events.withIndex()) eventFunction(opcode, event)
        for (enum in iface.enums) enumObject(enum)
        dispatchFunction()
        line()
        line("    public companion object : ResourceType<$name>(\"${iface.name}\", ${iface.version}) {")
        line("        internal override fun create(client: Client, id: Int, version: Int): $name = $name(client, id, version)")
        line("    }")
        line("}")
        return out.toString()
    }

    private fun imports() {
        val names = sortedSetOf(
            "dev.tessera.protocol.Client",
            "dev.tessera.protocol.MessageReader",
            "dev.tessera.protocol.ProtocolException",
            "dev.tessera.protocol.Resource",
            "dev.tessera.protocol.ResourceType",
            "dev.tessera.protocol.wayland.WlDisplay",
        )
        val all = iface.requests + iface.events
        if (all.any { message -> message.args.any { it.type == "fd" } }) names += "dev.tessera.ffi.Fd"
        for (message in all) {
            for (arg in message.args) {
                val target = arg.interfaceName?.let { classes[it] } ?: continue
                names += target
            }
        }
        names.removeIf { it.substringBeforeLast('.') == protocol.packageName }
        names.forEach { line("import $it") }
        line()
    }

    // `interface Requests { fun attach(resource: WlSurface, buffer: WlBuffer?, x: Int, y: Int) ... }`
    private fun requestsInterface() {
        line("    public interface Requests {")
        for ((index, request) in iface.requests.withIndex()) {
            if (index > 0) line()
            doc("        ", request.summary, request.description, sinceNote(request.since))
            val params = listOf("resource: $name") + request.args.flatMap { requestParams(it) }
            line("        public fun ${camel(request.name)}(${params.joinToString(", ")})")
        }
        line("    }")
    }

    private fun requestParams(arg: ArgModel): List<String> {
        val param = paramName(arg.name)
        return when {
            // A new object whose type the client chooses (only wl_registry.bind).
            arg.type == "new_id" && arg.interfaceName == null ->
                listOf("interfaceName: String", "version: Int", "$param: Int")
            else -> listOf("$param: ${kotlinType(arg)}")
        }
    }

    private fun eventFunction(opcode: Int, event: MessageModel) {
        line()
        doc("    ", event.summary, event.description, sinceNote(event.since))
        val params = event.args.map { "${paramName(it.name)}: ${kotlinType(it)}" }
        line("    public fun send${pascal(event.name)}(${params.joinToString(", ")}) {")
        line("        if (!isAlive || !client.isConnected) return")
        if (event.since > 1) line("        if (version < ${event.since}) return // the client is too old for this event")
        val writes = event.args.joinToString("") { ".${writeCall(it)}(${paramName(it.name)})" }
        line("        event($opcode)$writes.end()")
        line("    }")
    }

    private fun enumObject(enum: EnumModel) {
        line()
        doc("    ", enum.summary, "", if (enum.isBitfield) "Bit flags: combine with `or`." else "")
        line("    public object ${pascal(enum.name)} {")
        for (entry in enum.entries) {
            if (entry.summary.isNotEmpty()) line("        /** ${escape(entry.summary)} */")
            line("        public const val ${constant(entry.name)}: Int = ${entry.value.toInt()}")
        }
        line("    }")
    }

    // Reads each request's arguments (checked by MessageReader) and calls the handler.
    private fun dispatchFunction() {
        line()
        line("    override fun dispatch(opcode: Int, args: MessageReader) {")
        if (iface.requests.isEmpty()) {
            line("        throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, \"${iface.name} has no requests\")")
            line("    }")
            return
        }
        line("        val handler = requests")
        line("            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, \"${iface.name} is not implemented\")")
        line("        when (opcode) {")
        for ((opcode, request) in iface.requests.withIndex()) {
            line("            $opcode -> {")
            if (request.since > 1) {
                line("                if (version < ${request.since}) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, \"${iface.name}.${request.name} needs version ${request.since}\")")
            }
            val callArgs = mutableListOf("this")
            for (arg in request.args) {
                val param = paramName(arg.name)
                when {
                    arg.type == "new_id" && arg.interfaceName == null -> {
                        line("                val interfaceName = args.string()")
                        line("                val requestedVersion = args.uint()")
                        line("                val $param = args.newId()")
                        callArgs += listOf("interfaceName", "requestedVersion", param)
                    }
                    arg.type == "new_id" -> {
                        line("                val ${param}Id = args.newId()")
                        callArgs += param
                    }
                    else -> {
                        line("                val $param = ${readCall(arg)}")
                        callArgs += param
                    }
                }
            }
            line("                args.end()")
            for (arg in request.args.filter { it.type == "new_id" && it.interfaceName != null }) {
                val param = paramName(arg.name)
                val type = simpleClass(arg.interfaceName!!)
                line("                val $param = client.register($type.create(client, ${param}Id, version))")
            }
            line("                handler.${camel(request.name)}(${callArgs.joinToString(", ")})")
            if (request.isDestructor) line("                client.destroyResource(this)")
            line("            }")
        }
        line("            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, \"unknown ${iface.name} request \$opcode\")")
        line("        }")
        line("    }")
    }

    // --- types and names ---------------------------------------------------------------

    private fun kotlinType(arg: ArgModel): String {
        val base = when (arg.type) {
            "int", "uint" -> "Int"
            "fixed" -> "Double"
            "string" -> "String"
            "array" -> "ByteArray"
            "fd" -> "Fd"
            "object", "new_id" -> arg.interfaceName?.let { simpleClass(it) } ?: "Resource"
            else -> error("Unknown argument type ${arg.type} in ${iface.name}")
        }
        val nullable = arg.allowNull && arg.type in setOf("string", "object")
        return if (nullable) "$base?" else base
    }

    private fun readCall(arg: ArgModel): String = when (arg.type) {
        "int" -> "args.int()"
        "uint" -> "args.uint()"
        "fixed" -> "args.fixed()"
        "string" -> if (arg.allowNull) "args.stringOrNull()" else "args.string()"
        "array" -> "args.array()"
        "fd" -> "args.fd()"
        "object" -> {
            val type = arg.interfaceName?.let { simpleClass(it) } ?: "Resource"
            if (arg.allowNull) "args.objOrNull($type::class.java)" else "args.obj($type::class.java)"
        }
        else -> error("Cannot read ${arg.type}")
    }

    private fun writeCall(arg: ArgModel): String = when (arg.type) {
        "int" -> "int"
        "uint" -> "uint"
        "fixed" -> "fixed"
        "string" -> "string"
        "array" -> "array"
        "fd" -> "fd"
        "object" -> "obj"
        "new_id" -> "newId"
        else -> error("Cannot write ${arg.type}")
    }

    private fun simpleClass(interfaceName: String): String {
        val full = classes[interfaceName] ?: error("${iface.name} refers to unknown interface $interfaceName")
        return full.substringAfterLast('.')
    }

    private fun camel(snake: String): String = escapeKeyword(pascal(snake).replaceFirstChar { it.lowercase() })

    private fun pascal(snake: String): String =
        snake.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

    // The handler's first parameter is called `resource`, so an argument with that name is renamed.
    private fun paramName(snake: String): String = camel(snake).let { if (it == "resource") "resourceArg" else it }

    private fun constant(name: String): String {
        val upper = name.uppercase()
        return if (upper.first().isDigit()) "_$upper" else upper
    }

    private fun escapeKeyword(name: String) = if (name in KEYWORDS) "`$name`" else name

    private fun sinceNote(since: Int) = if (since > 1) "Since version $since." else ""

    // --- text --------------------------------------------------------------------------

    private fun doc(indent: String, summary: String, description: String, note: String = "") {
        val parts = listOf(summary.trim().replaceFirstChar { it.uppercase() }, description, note).filter { it.isNotBlank() }
        if (parts.isEmpty()) return
        line("$indent/**")
        parts.forEachIndexed { index, part ->
            if (index > 0) line("$indent *")
            escape(part).lines().forEach { text -> line("$indent * ${text.trimEnd()}".trimEnd()) }
        }
        line("$indent */")
    }

    private fun escape(text: String) = text.replace("*/", "*&#47;").replace("/*", "&#47;*")

    private fun line(text: String = "") {
        out.append(text).append('\n')
    }

    private companion object {
        val KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        )
    }
}
