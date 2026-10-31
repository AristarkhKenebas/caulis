package testplugins

import dev.caulis.plugins.Plugin
import dev.caulis.plugins.PluginContext

// Plugins for the tests: packed into jars by PluginJars, so their classes come from the plugin's own
// class loader. (Not under dev.caulis: that comes from the compositor.)

/** What the test host offers: a list of things registered, and calls that come back. */
class TestApi(val scope: dev.caulis.plugins.PluginHandle) {
    fun register(hook: () -> Unit): AutoCloseable = scope.scope.tracked(TestHost.add(hook), "hook")
}

object TestHost {
    @JvmField val hooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    @JvmField val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun add(hook: () -> Unit): AutoCloseable {
        hooks += hook
        return AutoCloseable { hooks -= hook }
    }
}

class Greeter : Plugin<TestApi> {
    // Something big enough that keeping it would show.
    private val ballast = ByteArray(1024)
    override fun enable(context: PluginContext<TestApi>) {
        TestHost.events += "enable ${context.manifest.id} ${context.manifest.version} ${context.settings.string("word", "hello")} ${ballast.size}"
        context.api.register { TestHost.events += "hook ${context.manifest.id}" }
        context.scope.onClose { TestHost.events += "closed ${context.manifest.id}" }
    }

    override fun disable() {
        TestHost.events += "disable"
    }
}

class Thrower : Plugin<TestApi> {
    override fun enable(context: PluginContext<TestApi>) {
        context.api.register { throw IllegalStateException("broken hook") }
    }
}

class ThrowsAtStart : Plugin<TestApi> {
    override fun enable(context: PluginContext<TestApi>) {
        context.api.register { TestHost.events += "must be taken away" }
        error("cannot start")
    }
}

class Sleeper : Plugin<TestApi> {
    override fun enable(context: PluginContext<TestApi>) {
        context.api.register {
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
                TestHost.events += "interrupted"
            }
        }
    }
}

class UsesDependency : Plugin<TestApi> {
    override fun enable(context: PluginContext<TestApi>) {
        TestHost.events += "shared " + testplugins.lib.Shared.word()
    }
}

class ThreadLeaver : Plugin<TestApi> {
    override fun enable(context: PluginContext<TestApi>) {
        context.scope.thread("test-worker") {
            try {
                Thread.sleep(600_000)
            } catch (_: InterruptedException) {
                TestHost.events += "worker ended"
            }
        }
    }
}
