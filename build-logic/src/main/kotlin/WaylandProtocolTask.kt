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

internal class KotlinWriter(
    private val protocol: ProtocolModel,
    private val iface: InterfaceModel,
    private val classes: Map<String, String>,
) {
    private val out = StringBuilder()
    private val name = iface.className

    fun write(): String {
        line("// Generated from ${protocol.fileName}. Do not edit: run ./gradlew :caulis-protocol:generateProtocols")
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
            "dev.caulis.protocol.Client",
            "dev.caulis.protocol.MessageReader",
            "dev.caulis.protocol.ProtocolException",
            "dev.caulis.protocol.Resource",
            "dev.caulis.protocol.ResourceType",
            "dev.caulis.protocol.wayland.WlDisplay",
        )
        val all = iface.requests + iface.events
        if (all.any { message -> message.args.any { it.type == "fd" } }) names += "dev.caulis.ffi.Fd"
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

/**
 * The client side of an interface: requests become functions that send them, events
 * arrive through an `Events` interface whose functions do nothing unless overridden.
 */
internal class ClientKotlinWriter(
    private val protocol: ProtocolModel,
    private val iface: InterfaceModel,
    private val classes: Map<String, String>,
) {
    private val out = StringBuilder()
    private val name = iface.className

    fun write(): String {
        line("// Generated from ${protocol.fileName}. Do not edit: run ./gradlew :caulis-protocol:generateProtocols")
        line("@file:Suppress(\"unused\", \"UNUSED_PARAMETER\", \"RedundantVisibilityModifier\")")
        line()
        line("package ${protocol.clientPackageName}")
        line()
        imports()
        doc("", iface.summary, iface.description)
        line("public class $name private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {")
        line()
        line("    override val type: ProxyType<$name> get() = Companion")
        if (iface.events.isNotEmpty()) {
            line()
            line("    /** Receives the events the compositor sends to this object. */")
            line("    public var events: Events? = null")
            line()
            eventsInterface()
        }
        for ((opcode, request) in iface.requests.withIndex()) requestFunction(opcode, request)
        for (enum in iface.enums) enumObject(enum)
        dispatchFunction()
        line()
        line("    public companion object : ProxyType<$name>(\"${iface.name}\", ${iface.version}) {")
        line("        internal override fun create(connection: Connection, id: Int, version: Int): $name = $name(connection, id, version)")
        line("    }")
        line("}")
        return out.toString()
    }

    private fun imports() {
        val names = sortedSetOf(
            "dev.caulis.protocol.client.Connection",
            "dev.caulis.protocol.client.EventReader",
            "dev.caulis.protocol.client.Proxy",
            "dev.caulis.protocol.client.ProxyType",
        )
        val all = iface.requests + iface.events
        if (all.any { message -> message.args.any { it.type == "fd" } }) names += "dev.caulis.ffi.Fd"
        for (message in all) {
            for (arg in message.args) names += arg.interfaceName?.let { classes[it] } ?: continue
        }
        names.removeIf { it.substringBeforeLast('.') == protocol.clientPackageName }
        names.forEach { line("import $it") }
        line()
    }

    private fun eventsInterface() {
        line("    public interface Events {")
        for ((index, event) in iface.events.withIndex()) {
            if (index > 0) line()
            doc("        ", event.summary, event.description, sinceNote(event.since))
            val params = listOf("proxy: $name") + event.args.map { "${paramName(it.name)}: ${eventType(it)}" }
            line("        public fun ${camel(event.name)}(${params.joinToString(", ")}) {}")
        }
        line("    }")
    }

    private fun requestFunction(opcode: Int, request: MessageModel) {
        line()
        doc("    ", request.summary, request.description, sinceNote(request.since))
        val newIds = request.args.filter { it.type == "new_id" }
        val untyped = newIds.firstOrNull { it.interfaceName == null }
        val typed = newIds.firstOrNull { it.interfaceName != null }
        val params = request.args.filter { it.type != "new_id" }.map { "${paramName(it.name)}: ${requestType(it)}" }.toMutableList()
        val returnType = when {
            untyped != null -> {
                params += listOf("type: ProxyType<T>", "version: Int")
                "T"
            }
            typed != null -> simpleClass(typed.interfaceName!!)
            else -> "Unit"
        }
        val generic = if (untyped != null) "<T : Proxy> " else ""
        line("    public fun $generic${camel(request.name)}(${params.joinToString(", ")}): $returnType {")
        if (request.since > 1) {
            line("        check(version >= ${request.since}) { \"${iface.name}.${request.name} needs version ${request.since}, this object has \$version\" }")
        }
        if (untyped != null) line("        val created = connection.createProxy(type, version)")
        if (typed != null) line("        val created = connection.createProxy(${simpleClass(typed.interfaceName!!)}, this.version)")
        val writes = request.args.joinToString("") { arg ->
            when {
                arg.type == "new_id" && arg.interfaceName == null -> ".string(type.name).uint(version).newId(created)"
                arg.type == "new_id" -> ".newId(created)"
                else -> ".${writeCall(arg)}(${paramName(arg.name)})"
            }
        }
        line("        request($opcode)$writes.end()")
        if (request.isDestructor) line("        connection.destroyProxy(this)")
        if (newIds.isNotEmpty()) line("        return created")
        line("    }")
    }

    private fun enumObject(enum: EnumModel) {
        line()
        if (enum.summary.isNotEmpty()) line("    /** ${escape(enum.summary.trim())} */")
        line("    public object ${pascal(enum.name)} {")
        for (entry in enum.entries) {
            if (entry.summary.isNotEmpty()) line("        /** ${escape(entry.summary)} */")
            line("        public const val ${constant(entry.name)}: Int = ${entry.value.toInt()}")
        }
        line("    }")
    }

    private fun dispatchFunction() {
        line()
        line("    override fun dispatchEvent(opcode: Int, args: EventReader) {")
        if (iface.events.isEmpty()) {
            line("    }")
            return
        }
        line("        val handler = events")
        line("        when (opcode) {")
        for ((opcode, event) in iface.events.withIndex()) {
            line("            $opcode -> {")
            val callArgs = mutableListOf("this")
            for (arg in event.args) {
                val param = paramName(arg.name)
                line("                val $param = ${readCall(arg)}")
                callArgs += param
            }
            line("                args.end()")
            line("                handler?.${camel(event.name)}(${callArgs.joinToString(", ")})")
            line("            }")
        }
        line("        }")
        line("    }")
    }

    // --- types and names ---------------------------------------------------------------

    private fun baseType(arg: ArgModel): String = when (arg.type) {
        "int", "uint" -> "Int"
        "fixed" -> "Double"
        "string" -> "String"
        "array" -> "ByteArray"
        "fd" -> "Fd"
        "object", "new_id" -> arg.interfaceName?.let { simpleClass(it) } ?: "Proxy"
        else -> error("Unknown argument type ${arg.type} in ${iface.name}")
    }

    private fun requestType(arg: ArgModel): String {
        val nullable = arg.allowNull && arg.type in setOf("string", "object")
        return baseType(arg) + if (nullable) "?" else ""
    }

    // Objects without a declared type (wl_display.error) may already be gone: nullable.
    private fun eventType(arg: ArgModel): String {
        val nullable = (arg.allowNull && arg.type in setOf("string", "object")) ||
            (arg.type == "object" && arg.interfaceName == null)
        return baseType(arg) + if (nullable) "?" else ""
    }

    private fun readCall(arg: ArgModel): String = when (arg.type) {
        "int" -> "args.int()"
        "uint" -> "args.uint()"
        "fixed" -> "args.fixed()"
        "string" -> if (arg.allowNull) "args.stringOrNull()" else "args.string()"
        "array" -> "args.array()"
        "fd" -> "args.fd()"
        "object" -> {
            val type = arg.interfaceName?.let { simpleClass(it) } ?: "Proxy"
            if (arg.allowNull || arg.interfaceName == null) "args.objOrNull($type::class.java)" else "args.obj($type::class.java)"
        }
        "new_id" -> "args.newId(${simpleClass(arg.interfaceName ?: error("untyped new_id in an event"))}, version)"
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
        else -> error("Cannot write ${arg.type}")
    }

    private fun simpleClass(interfaceName: String): String {
        val full = classes[interfaceName] ?: error("${iface.name} refers to unknown interface $interfaceName")
        return full.substringAfterLast('.')
    }

    private fun camel(snake: String): String = escapeKeyword(pascal(snake).replaceFirstChar { it.lowercase() })

    private fun pascal(snake: String): String =
        snake.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

    // The first parameter of event handlers is called `proxy`; arguments named so are renamed.
    private fun paramName(snake: String): String = camel(snake).let {
        when (it) {
            "proxy" -> "proxyArg"
            "type", "version" -> "${it}Arg"
            else -> it
        }
    }

    private fun constant(name: String): String {
        val upper = name.uppercase()
        return if (upper.first().isDigit()) "_$upper" else upper
    }

    private fun escapeKeyword(name: String) = if (name in KEYWORDS) "`$name`" else name

    private fun sinceNote(since: Int) = if (since > 1) "Since version $since." else ""

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
