package dev.caulis.log

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reads the sources: no log line may hold a password, what is typed, the clipboard's contents or
 * a notification's words. Sizes and counts of them are fine (".length", ".size").
 */
class LogPrivacyTest {

    private val logCall = Regex("""\b\w*[lL]og(?:ger)?\.(?:trace|info|warn|error|fatal)\b.*""")
    private val forbidden = Regex(
        """\$\{?(?!reply\.)(?:[\w.?]*\.)?(?:password|passwd|passphrase|psk|secret|typed|typedText|commitString|preedit|keysym|clipboardText|clipText|pasted|summary|body|credential)\b(?!\.(?:length|size|isEmpty|isNotEmpty)\b)""",
    )

    @Test
    fun `nothing private is logged`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val bad = Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".kt") && "/src/main/" in it.toString() && "/build/" !in it.toString() }.toList()
        }.flatMap { file ->
            Files.readAllLines(file).withIndex().filter { (_, line) ->
                logCall.find(line)?.let { forbidden.containsMatchIn(it.value) } == true
            }.map { (n, line) -> "${root.relativize(file)}:${n + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun `the check itself sees such a line`() {
        val line = logCall.find("log.info { \"Sent ${'$'}{n.body} and ${'$'}password\" }")!!.value
        assertEquals(true, forbidden.containsMatchIn(line))
        assertEquals(false, forbidden.containsMatchIn(logCall.find("log.trace { \"${'$'}{n.body.length} characters\" }")!!.value))
    }
}
