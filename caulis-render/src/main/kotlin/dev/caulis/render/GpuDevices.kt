package dev.caulis.render

import java.nio.file.Files
import java.nio.file.Path

/**
 * GPUs as the kernel numbers them. A device number (dev_t) is what Wayland programs send
 * each other to say "this GPU" (e.g. in linux-dmabuf feedback).
 */
object GpuDevices {

    /** The device number of a device file such as /dev/dri/renderD128. */
    fun number(device: Path): Long = Files.getAttribute(device, "unix:rdev") as Long

    /**
     * True if both device numbers belong to the same physical GPU. One GPU has several
     * device files (card0 for the display, renderD128 for drawing); they all point to the
     * same device in /sys.
     */
    fun sameGpu(a: Long, b: Long): Boolean {
        if (a == b) return true
        val first = physicalDevice(a) ?: return false
        return first == physicalDevice(b)
    }

    // /sys/dev/char/<major>:<minor>/device leads to the GPU itself (e.g. its PCI address).
    private fun physicalDevice(number: Long): Path? = try {
        Path.of("/sys/dev/char/${major(number)}:${minor(number)}/device").toRealPath()
    } catch (e: java.io.IOException) {
        null
    }

    // Linux packs major and minor numbers into dev_t like this (see <sys/sysmacros.h>).
    private fun major(number: Long): Long = ((number ushr 8) and 0xfff) or ((number ushr 32) and 0xfffff000L)
    private fun minor(number: Long): Long = (number and 0xff) or ((number ushr 12) and 0xffffff00L)
}
