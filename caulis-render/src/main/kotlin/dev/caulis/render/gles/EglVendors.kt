package dev.caulis.render.gles

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path

/**
 * Only the EGL driver ("vendor") made for the GPU in use is loaded. libglvnd's EGL loads every
 * vendor installed and asks each in turn: with NVIDIA's driver installed beside Mesa, NVIDIA's
 * EGL (~35 MB of libraries in memory, and its own allocations) was loaded on an AMD or Intel
 * GPU only to say no — and Mesa's on an NVIDIA one. The GPU's kernel driver says which:
 * nvidia-drm is NVIDIA's, the usual others are Mesa's. Done by telling libglvnd the vendor
 * files to read (__EGL_VENDOR_LIBRARY_FILENAMES) for the moment the first display is made,
 * then forgotten again (programs started later never see it). Nothing is chosen when the
 * driver is another one, when no vendor file matches, or when that variable is set already
 * (yours wins). Off: [GlesRenderer.vendorByDriver] = false.
 */
internal object EglVendors {
    private val log = dev.caulis.log.Logger("render")
    private const val VARIABLE = "__EGL_VENDOR_LIBRARY_FILENAMES"

    // Kernel drivers whose EGL is Mesa's.
    private val MESA = setOf(
        "amdgpu", "radeon", "i915", "xe", "nouveau", "virtio_gpu", "vmwgfx", "msm", "panfrost", "panthor",
        "lima", "v3d", "vc4", "etnaviv", "asahi", "freedreno", "qxl", "vkms", "imagination", "powervr",
    )

    // Where libglvnd looks for vendor files (its usual places; NixOS's; or the variable's).
    private fun directories(): List<Path> =
        (System.getenv("__EGL_VENDOR_LIBRARY_DIRS")?.split(':')?.filter { it.isNotBlank() }
            ?: listOf("/etc/glvnd/egl_vendor.d", "/usr/share/glvnd/egl_vendor.d", "/usr/local/share/glvnd/egl_vendor.d", "/run/opengl-driver/share/glvnd/egl_vendor.d"))
            .map { Path.of(it) }

    /** The kernel driver of the open DRM device [fd] ("amdgpu", "nvidia-drm"), or null. */
    fun driverOf(fd: Int): String? = try {
        val node = Files.readSymbolicLink(Path.of("/proc/self/fd/$fd")).fileName.toString()
        Files.readSymbolicLink(Path.of("/sys/class/drm/$node/device/driver")).fileName.toString()
    } catch (_: Exception) {
        null
    }

    /** The vendor files meant for [driver], or null: no choice (all are loaded, as libglvnd does by itself). */
    fun filesFor(driver: String?, files: List<Pair<Path, String>>): List<Path>? {
        val wanted: (String) -> Boolean = when {
            driver == null -> return null
            driver == "nvidia-drm" || driver == "nvidia" -> { library -> "nvidia" in library.lowercase() }
            driver in MESA -> { library -> "mesa" in library.lowercase() }
            else -> return null
        }
        val chosen = files.filter { (_, library) -> wanted(library) }.map { it.first }
        return chosen.takeIf { it.isNotEmpty() && it.size < files.size }
    }

    // Each vendor file and the library it names ("libEGL_mesa.so.0").
    private fun installed(): List<Pair<Path, String>> = directories().flatMap { dir ->
        try {
            Files.list(dir).use { list -> list.filter { it.fileName.toString().endsWith(".json") }.sorted().toList() }
        } catch (_: Exception) {
            emptyList()
        }
    }.distinctBy { it.fileName.toString() }.mapNotNull { file ->
        val text = try { Files.readString(file) } catch (_: Exception) { return@mapNotNull null }
        val library = Regex("\"library_path\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: return@mapNotNull null
        file to library
    }

    /** Runs [makeDisplay] (the first EGL display) with only the vendor for [deviceFd]'s driver loaded. */
    fun <T> withVendorFor(deviceFd: Int, makeDisplay: () -> T): T {
        if (!GlesRenderer.vendorByDriver || System.getenv(VARIABLE) != null || chosenAlready) return makeDisplay()
        chosenAlready = true
        val driver = driverOf(deviceFd)
        val files = filesFor(driver, installed())
        if (files == null) {
            log.trace { "EGL: every vendor libglvnd finds (the GPU's driver: ${driver ?: "unknown"})" }
            return makeDisplay()
        }
        val value = files.joinToString(":")
        log.info { "EGL: only the vendor for $driver is loaded ($value)" }
        if (!setenv(VARIABLE, value)) return makeDisplay()
        try {
            return makeDisplay()
        } finally {
            unsetenv(VARIABLE)
        }
    }

    // (libglvnd reads its vendors once, at the first display: later ones get the same.)
    @Volatile
    private var chosenAlready = false

    private val linker = Linker.nativeLinker()
    private val setenvHandle by lazy {
        linker.downcallHandle(linker.defaultLookup().find("setenv").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    }
    private val unsetenvHandle by lazy {
        linker.downcallHandle(linker.defaultLookup().find("unsetenv").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }

    private fun setenv(name: String, value: String): Boolean = try {
        Arena.ofConfined().use { a -> (setenvHandle.invokeExact(a.allocateFrom(name), a.allocateFrom(value), 1) as Int) == 0 }
    } catch (e: Throwable) {
        if (e is Error) throw e
        false
    }

    private fun unsetenv(name: String) {
        try {
            Arena.ofConfined().use { a -> unsetenvHandle.invokeExact(a.allocateFrom(name)) as Int }
        } catch (e: Throwable) {
            if (e is Error) throw e
        }
    }
}
