
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
            "dev.caulis.protocol.Wire",
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
        // An object the client destroyed meanwhile is never named in an event (its id may be another's now).
        for (arg in event.args.filter { it.type == "object" }) {
            val name = paramName(arg.name)
            // (Nullable exactly when its Kotlin type is: see kotlinType.)
            line(if (arg.allowNull) "        if ($name != null && !$name.isAlive) return" else "        if (!$name.isAlive) return")
        }
        val shown = traceArgs(event.args.map { it.name to paramName(it.name) })
        line("        if (Wire.tracing) Wire.event(this, \"${event.name}\", \"$shown\")")
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
            val shown = traceArgs(request.args.flatMap { arg ->
                val param = paramName(arg.name)
                if (arg.type == "new_id" && arg.interfaceName == null) listOf("interface" to "interfaceName", "version" to "requestedVersion", arg.name to param)
                else listOf(arg.name to param)
            })
            line("                if (Wire.tracing) Wire.request(this, \"${request.name}\", \"$shown\")")
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
