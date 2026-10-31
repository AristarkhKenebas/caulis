package dev.caulis.plugins

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testplugins.TestApi
import testplugins.TestHost
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The manager on its own, with a host whose "compositor's thread" is the test's. */
class PluginManagerTest {
    private lateinit var folder: Path
    private lateinit var plugins: Path
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val notices = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var mayChange = true
    private var manager: PluginManager<TestApi>? = null

    private val host = object : PluginHost<TestApi> {
        override val apiName = "test"
        override val apis = mapOf("test" to ApiVersion(0, 1))
        override fun apiFor(handle: PluginHandle) = TestApi(handle)
        override fun post(task: Runnable) { tasks += task }
        override fun notice(problem: Boolean, title: String, text: String) { notices += "$title: $text" }
        override fun dataDirFor(id: String): Path = folder.resolve("data").resolve(id)
        override fun mayChangeNow() = mayChange
    }

    @BeforeEach
    fun setUp() {
        folder = Files.createTempDirectory("plugins-test")
        plugins = Files.createDirectory(folder.resolve("plugins"))
        TestHost.hooks.clear()
        TestHost.events.clear()
    }

    @AfterEach
    fun tearDown() {
        manager?.close()
        folder.toFile().deleteRecursively()
    }

    private fun options(
        watch: Boolean = false,
        settings: Map<String, Map<String, Any?>> = emptyMap(),
        stateFile: Path? = folder.resolve("state/plugins.properties"),
        hangReport: Long = 0,
        hangInterrupt: Long = 0,
        quarantine: Long = 0,
    ) = PluginOptions(
        directories = listOf(plugins), watch = watch, settings = settings, settleMillis = 20, stateFile = stateFile,
        hangReportMillis = hangReport, hangInterruptMillis = hangInterrupt, hangQuarantineMillis = quarantine,
        leakCheckMillis = 0, cacheDir = folder.resolve("cache"), parentLoader = TestHost::class.java.classLoader,
        sharedPackages = PluginClassLoader.DEFAULT_SHARED + listOf("testplugins.TestHost", "testplugins.TestApi"),
    )

    private fun pump(until: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!until()) {
            check(System.nanoTime() < end) { "it did not happen; list=${manager?.list()} notices=$notices events=${TestHost.events}" }
            while (true) (tasks.poll() ?: break).run()
            Thread.sleep(5)
        }
        while (true) (tasks.poll() ?: break).run()
    }

    private fun started(options: PluginOptions = options()): PluginManager<TestApi> =
        PluginManager(host, options).also { manager = it; it.start() }

    private fun state(id: String) = manager!!.list().firstOrNull { it.id == id }?.state

    private fun jar(name: String, id: String = name, main: String = "testplugins.Greeter", version: String = "1.0", api: String = "test:0.1", depends: String = "") =
        PluginJars.write(plugins.resolve("$name.jar"), PluginJars.manifest(id, main, version, api, depends))

    private fun fireHooks() = TestHost.hooks.toList().forEach { it() }

    @Test
    fun `a plugin is found, runs in its own loader, and everything goes when it is disabled`() {
        jar("greeter")
        val m = started(options(settings = mapOf("greeter" to mapOf("word" to "hi"))))
        pump { state("greeter") == PluginState.ENABLED }
        assertTrue("enable greeter 1.0 hi 1024" in TestHost.events, "${TestHost.events}")
        assertEquals(mapOf("cleanup" to 1, "hook" to 1), m.list().single().held)
        assertEquals("plugin:greeter#1", m.classLoaderOf("greeter")?.name)
        assertEquals(m.classLoaderOf("greeter"), m.instanceOf("greeter")?.javaClass?.classLoader)
        assertEquals(1, TestHost.hooks.size)
        assertNull(m.disable("greeter"))
        assertEquals(PluginState.DISABLED, state("greeter"))
        assertEquals(0, TestHost.hooks.size)
        assertTrue("disable" in TestHost.events && "closed greeter" in TestHost.events)
        assertNull(m.classLoaderOf("greeter"))
    }

    @Test
    fun `no plugins at all leaves nothing running but the watch`() {
        val m = started(options(watch = true))
        Thread.sleep(100)
        pump { true }
        assertEquals(emptyList(), m.list())
        val names = Thread.getAllStackTraces().keys.filter { it.name.startsWith("caulis-plugin") }.map { it.name }
        assertFalse("caulis-plugin-watchdog" in names, "$names")
    }

    @Test
    fun `a throwing plugin is disabled with a notice, the rest goes on`() {
        jar("thrower", main = "testplugins.Thrower")
        jar("greeter")
        val m = started()
        pump { state("thrower") == PluginState.ENABLED && state("greeter") == PluginState.ENABLED }
        assertEquals(2, TestHost.hooks.size)
        // (What an API does with each hook: runs it in the plugin's guard.)
        val failing = PluginGuardProbe.guardOf(m, "thrower")
        failing.call("a hook", Unit) { error("broken hook") }
        assertFalse(failing.active, "no more of its code runs")
        var ran = false
        failing.call("another hook", Unit) { ran = true }
        assertFalse(ran)
        pump { state("thrower") == PluginState.FAILED }
        assertTrue(notices.any { "thrower" in it && "broken hook" in it }, "$notices")
        assertEquals(PluginState.ENABLED, state("greeter"))
        assertTrue(m.list().first { it.id == "thrower" }.problem!!.contains("broken hook"))
        // It may be tried again.
        assertNull(m.enable("thrower"))
        assertEquals(PluginState.ENABLED, state("thrower"))
    }

    @Test
    fun `one that throws while starting leaves nothing behind`() {
        jar("bad", main = "testplugins.ThrowsAtStart")
        started()
        pump { state("bad") == PluginState.FAILED }
        assertEquals(0, TestHost.hooks.size)
        assertTrue(notices.any { "cannot start" in it }, "$notices")
    }

    @Test
    fun `a main class that is not there is reported`() {
        jar("nomain", main = "testplugins.NoSuchClass")
        started()
        pump { state("nomain") == PluginState.FAILED }
        assertTrue(notices.any { "NoSuchClass" in it }, "$notices")
    }

    @Test
    fun `a hang on the compositor's thread is detected, interrupted, written down and disabled`() {
        jar("sleeper", main = "testplugins.Sleeper")
        val state = folder.resolve("state/plugins.properties")
        val m = started(options(hangReport = 100, hangInterrupt = 300, quarantine = 200, stateFile = state))
        pump { state("sleeper") == PluginState.ENABLED }
        val guard = PluginGuardProbe.guardOf(m, "sleeper")
        val hook = TestHost.hooks.single()
        val begin = System.nanoTime()
        guard.call("a hook", Unit) { hook() }
        assertTrue((System.nanoTime() - begin) / 1_000_000 < 10_000, "the interrupt ended the sleep")
        assertFalse(Thread.currentThread().isInterrupted, "the interrupt does not reach the compositor's own code")
        assertTrue("interrupted" in TestHost.events)
        pump { state("sleeper") == PluginState.FAILED }
        assertTrue(notices.any { "froze" in it }, "$notices")
        assertTrue(notices.any { "disabled" in it }, "$notices")
        assertTrue("quarantined=sleeper" in Files.readString(state), Files.readString(state))
        m.close()
        // The next start leaves it off, and says so; `enable` tries it again.
        notices.clear()
        val next = started(options(stateFile = state))
        pump { state("sleeper") == PluginState.DISABLED }
        assertTrue(notices.any { "froze" in it }, "$notices")
        assertNull(next.enable("sleeper"))
        assertFalse("quarantined=sleeper" in Files.readString(state))
    }

    @Test
    fun `a jar replaced is hot-reloaded, a broken one leaves the old running`() {
        jar("greeter", version = "1.0")
        val m = started(options(watch = true))
        pump { state("greeter") == PluginState.ENABLED }
        val first = WeakReference(m.classLoaderOf("greeter"))
        jar("greeter", version = "2.0")
        pump { m.list().firstOrNull()?.version == "2.0" && state("greeter") == PluginState.ENABLED }
        assertTrue(first.get() !== m.classLoaderOf("greeter"))
        assertEquals(2, m.list().single().generation)
        assertEquals(1, TestHost.hooks.size, "the old one's hook went")
        // A jar whose class is missing: 2.0 goes on.
        jar("greeter", main = "testplugins.NoSuchClass", version = "3.0")
        pump { notices.any { "does not work" in it } }
        assertEquals(PluginState.ENABLED, state("greeter"))
        assertEquals("2.0", m.list().single().version)
        assertEquals(1, TestHost.hooks.size)
        // Not a jar at all: the same, and said.
        Files.writeString(plugins.resolve("greeter.jar"), "not a jar")
        pump { notices.any { "not a jar" in it } }
        assertEquals(PluginState.ENABLED, state("greeter"))
        // Taken away: unloaded.
        Files.delete(plugins.resolve("greeter.jar"))
        pump { m.list().isEmpty() }
        assertEquals(0, TestHost.hooks.size)
    }

    @Test
    fun `waits while nothing may change`() {
        mayChange = false
        jar("greeter")
        val m = started()
        repeat(40) { pump { true }; Thread.sleep(10) }
        assertEquals(null, state("greeter"))
        mayChange = true
        m.rescan()
        pump { state("greeter") == PluginState.ENABLED }
    }

    @Test
    fun `api versions and dependencies are checked softly`() {
        jar("newer", api = "test:0.2")
        jar("older-major", api = "test:1.0")
        jar("foreign", api = "other:0.1")
        jar("alone", depends = "not-there")
        jar("fine", depends = "not-there?")
        val m = started()
        pump { state("fine") == PluginState.ENABLED }
        pump { m.list().size == 5 }
        for (id in listOf("newer", "older-major", "foreign", "alone")) assertEquals(PluginState.REFUSED, state(id), id)
        assertTrue(m.list().first { it.id == "newer" }.problem!!.contains("0.2"))
    }

    @Test
    fun `dependencies start first, see each other's classes, and stop with what they need`() {
        // (By name order "a-user" would come first: the dependency must go before it.)
        PluginJars.write(plugins.resolve("a-user.jar"), PluginJars.manifest("a-user", "testplugins.UsesDependency", depends = "z-lib>=1.0"), "testplugins/")
        PluginJars.write(plugins.resolve("z-lib.jar"), PluginJars.manifest("z-lib", "testplugins.lib.Shared", version = "1.5"), "testplugins/lib/")
        val m = started()
        pump { state("a-user") == PluginState.ENABLED }
        assertTrue("shared from-lib" in TestHost.events)
        assertEquals(listOf("z-lib", "a-user"), m.list().map { it.id })
        m.disable("z-lib")
        assertEquals(PluginState.REFUSED, state("a-user"))
        m.enable("z-lib")
        assertEquals(PluginState.ENABLED, state("z-lib"))
    }

    @Test
    fun `a circle of dependencies is refused`() {
        jar("one", depends = "two")
        jar("two", depends = "one")
        val m = started()
        pump { m.list().size == 2 && m.list().all { it.state == PluginState.REFUSED } }
    }

    @Test
    fun `disable stays through a restart`() {
        jar("greeter")
        val m = started()
        pump { state("greeter") == PluginState.ENABLED }
        assertEquals("disabled", m.command(listOf("disable", "greeter")))
        m.close()
        val next = started()
        pump { state("greeter") == PluginState.DISABLED }
        assertTrue("greeter" in next.command(listOf("list")))
        assertEquals("enabled", next.command(listOf("enable", "greeter")))
        next.close()
        started()
        pump { state("greeter") == PluginState.ENABLED }
    }

    @Test
    fun `new settings reload a plugin that does not take them on`() {
        jar("greeter")
        val m = started()
        pump { state("greeter") == PluginState.ENABLED }
        m.configure(options(settings = mapOf("greeter" to mapOf("word" to "changed"))))
        assertTrue(TestHost.events.any { it.endsWith("changed 1024") }, "${TestHost.events}")
        m.configure(options(settings = mapOf("greeter" to mapOf("word" to "changed"))).copy(disabled = setOf("greeter")))
        assertEquals(PluginState.DISABLED, state("greeter"))
    }

    @Test
    fun `a jar others can write to is not run`() {
        jar("open")
        Files.setPosixFilePermissions(plugins.resolve("open.jar"), java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"))
        val m = started()
        pump { notices.any { "anyone can change" in it } }
        assertEquals(emptyList(), m.list())
    }

    @Test
    fun `threads of its scope end with it`() {
        jar("worker", main = "testplugins.ThreadLeaver")
        val m = started()
        pump { state("worker") == PluginState.ENABLED }
        m.disable("worker")
        pump { "worker ended" in TestHost.events }
    }

    @Test
    fun `loading and unloading again and again leaves no class loader behind`() {
        jar("greeter")
        val m = started()
        pump { state("greeter") == PluginState.ENABLED }
        val loaders = ArrayList<WeakReference<ClassLoader>>()
        val metaspace = { java.lang.management.ManagementFactory.getMemoryPoolMXBeans().first { it.name == "Metaspace" }.usage.used }
        repeat(ROUNDS) { round ->
            loaders += WeakReference(assertNotNull(m.classLoaderOf("greeter")))
            fireHooks()
            if (round % 2 == 0) {
                assertNull(m.disable("greeter"))
                assertNull(m.enable("greeter"))
            } else {
                assertNull(m.reload("greeter"))
            }
            assertEquals(PluginState.ENABLED, state("greeter"))
            TestHost.events.clear()
            assertEquals(1, TestHost.hooks.size)
        }
        m.disable("greeter")
        tasks.clear()
        forceCollection(loaders)
        assertEquals(0, loaders.count { it.get() != null }, "class loaders still in memory")
        println("Metaspace after $ROUNDS loads: ${metaspace() / 1024} KB")
    }

    private fun forceCollection(loaders: List<WeakReference<ClassLoader>>) {
        repeat(30) {
            System.gc()
            if (loaders.all { it.get() == null }) return
            Thread.sleep(100)
        }
    }

    private companion object {
        const val ROUNDS = 40
    }
}

/** The tests fire hooks the way an API would: through the plugin's guard. */
internal object PluginGuardProbe {
    fun guardOf(manager: PluginManager<*>, id: String): PluginGuard = manager.handleOf(id)!!.guard
}
