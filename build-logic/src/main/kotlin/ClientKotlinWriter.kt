
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
            "dev.caulis.protocol.Wire",
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
        val shown = traceArgs(request.args.map { arg ->
            if (arg.type == "new_id") arg.name to "created" else arg.name to paramName(arg.name)
        })
        line("        if (Wire.tracing) Wire.sent(this, \"${request.name}\", \"$shown\")")
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
            val shown = traceArgs(event.args.map { it.name to paramName(it.name) })
            line("                if (Wire.tracing) Wire.received(this, \"${event.name}\", \"$shown\")")
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
