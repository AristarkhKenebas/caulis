package testplugins.lib

import dev.caulis.plugins.Plugin
import dev.caulis.plugins.PluginContext
import testplugins.TestApi

/** A plugin others depend on: they see this class through its class loader. */
class Shared : Plugin<TestApi> {
    override fun enable(context: PluginContext<TestApi>) {}

    companion object {
        @JvmStatic fun word() = "from-lib"
    }
}
