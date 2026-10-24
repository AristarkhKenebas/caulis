package dev.caulis.render

import dev.caulis.ffi.Fd

/**
 * A GPU buffer described by file descriptors (Linux "dma-buf"): how programs, the GPU and
 * the display share pictures without copying them.
 *
 * [format] is a DRM fourcc code (e.g. XRGB8888 = 'XR24'), [modifier] describes the memory
 * layout (0 = plain rows, others = GPU-specific tiling).
 */
class Dmabuf(
    val width: Int,
    val height: Int,
    val format: Int,
    val modifier: Long,
    val planes: List<Plane>,
) : AutoCloseable {

    /** One plane of the picture: its memory ([fd]), where it starts and how long a row is. */
    class Plane(val fd: Fd, val offset: Int, val stride: Int)

    init {
        require(planes.size in 1..4) { "a dma-buf has 1 to 4 planes, got ${planes.size}" }
        require(width > 0 && height > 0) { "invalid size ${width}x$height" }
    }

    /** Closes the descriptors of all planes. */
    override fun close() = planes.forEach { it.fd.close() }

    companion object {
        /** DRM fourcc codes are four characters packed into an Int. */
        fun fourcc(code: String): Int {
            require(code.length == 4)
            return code[0].code or (code[1].code shl 8) or (code[2].code shl 16) or (code[3].code shl 24)
        }

        val XRGB8888: Int = fourcc("XR24")
        val ARGB8888: Int = fourcc("AR24")

        /** Plain rows, no GPU tiling: every device understands it. */
        const val MODIFIER_LINEAR = 0L

        /** "Unknown, let the driver decide" (old clients). */
        const val MODIFIER_INVALID = 0x00ffffffffffffffL
    }
}

/** A format and memory layout the GPU can read pictures in. */
data class DmabufFormat(val format: Int, val modifier: Long)
