package dev.caulis.plugins

import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

/**
 * One plugin's classes: its jar first, so plugins cannot collide over a library, then the plugins it
 * depends on, then the compositor's. The packages in [shared] always come from the compositor (the
 * API, Kotlin, the JDK), also when the jar brings its own copy: two copies of an interface would not
 * fit each other.
 */
class PluginClassLoader(
    name: String,
    urls: Array<URL>,
    parent: ClassLoader,
    private val shared: List<String>,
    private val dependencies: List<PluginClassLoader> = emptyList(),
) : URLClassLoader(name, urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        val found = findLoadedClass(name) ?: when {
            isShared(name) -> parent.loadClass(name)
            else -> own(name) ?: fromDependencies(name) ?: parent.loadClass(name)
        }
        if (resolve) resolveClass(found)
        found
    }

    private fun isShared(name: String) = shared.any { name.startsWith(it) }

    // (A plugin depending on this one sees what is in this jar, and what this one depends on.)
    private fun own(name: String): Class<*>? = findLoadedClass(name) ?: try {
        findClass(name)
    } catch (_: ClassNotFoundException) {
        null
    }

    private fun fromDependencies(name: String): Class<*>? {
        for (dependency in dependencies) {
            synchronized(dependency.getClassLoadingLock(name)) { dependency.own(name) ?: dependency.fromDependencies(name) }?.let { return it }
        }
        return null
    }

    // Resources the same way: its own first (the default asks the compositor's first).
    override fun getResource(name: String): URL? = findResource(name) ?: dependencies.firstNotNullOfOrNull { it.findResource(name) } ?: parent.getResource(name)

    override fun getResources(name: String): Enumeration<URL> {
        val all = ArrayList<URL>()
        all += Collections.list(findResources(name))
        for (dependency in dependencies) all += Collections.list(dependency.findResources(name))
        all += Collections.list(parent.getResources(name))
        return Collections.enumeration(all)
    }

    companion object {
        init {
            registerAsParallelCapable()
        }

        /** What every plugin shares with the compositor. */
        @JvmField
        val DEFAULT_SHARED: List<String> = listOf("java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.jetbrains.annotations.", "dev.caulis.")
    }
}
