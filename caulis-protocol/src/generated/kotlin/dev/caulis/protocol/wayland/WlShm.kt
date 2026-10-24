// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire

/**
 * Shared memory support
 *
 * A singleton global object that provides support for shared
 * memory.
 *
 * Clients can create wl_shm_pool objects using the create_pool
 * request.
 *
 * On binding the wl_shm object one or more format events
 * are emitted to inform clients about the valid pixel formats
 * that can be used for buffers.
 */
public class WlShm private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlShm> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_shm. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Create a shm pool
         *
         * Create a new wl_shm_pool object.
         *
         * The pool can be used to create shared memory based buffer
         * objects.  The server will mmap size bytes of the passed file
         * descriptor, to use as backing memory for the pool.
         */
        public fun createPool(resource: WlShm, id: WlShmPool, fd: Fd, size: Int)

        /**
         * Release the shm object
         *
         * Using this request a client can tell the server that it is not going to
         * use the shm object anymore.
         *
         * Objects created via this interface remain unaffected.
         *
         * Since version 2.
         */
        public fun release(resource: WlShm)
    }

    /**
     * Pixel format description
     *
     * Informs the client about a valid pixel format that
     * can be used for buffers. Known formats include
     * argb8888 and xrgb8888.
     *
     * Extensions to drm_fourcc.h (or the format enum) do not require
     * increasing the wl_shm version; as a result, clients may receive format
     * codes which were not in the list at the time the client was made.
     */
    public fun sendFormat(format: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "format", "format=${Wire.show(format)}")
        event(0).uint(format).end()
    }

    /**
     * Wl_shm error values
     */
    public object Error {
        /** buffer format is not known */
        public const val INVALID_FORMAT: Int = 0
        /** invalid size or stride during pool creation */
        public const val INVALID_STRIDE: Int = 1
        /** mmapping the file descriptor failed */
        public const val INVALID_FD: Int = 2
    }

    /**
     * Pixel formats
     */
    public object Format {
        /** 32-bit ARGB format, [31:0] A:R:G:B 8:8:8:8 little endian */
        public const val ARGB8888: Int = 0
        /** 32-bit RGB format, [31:0] x:R:G:B 8:8:8:8 little endian */
        public const val XRGB8888: Int = 1
        /** 8-bit color index format, [7:0] C */
        public const val C8: Int = 538982467
        /** 8-bit RGB format, [7:0] R:G:B 3:3:2 */
        public const val RGB332: Int = 943867730
        /** 8-bit BGR format, [7:0] B:G:R 2:3:3 */
        public const val BGR233: Int = 944916290
        /** 16-bit xRGB format, [15:0] x:R:G:B 4:4:4:4 little endian */
        public const val XRGB4444: Int = 842093144
        /** 16-bit xBGR format, [15:0] x:B:G:R 4:4:4:4 little endian */
        public const val XBGR4444: Int = 842089048
        /** 16-bit RGBx format, [15:0] R:G:B:x 4:4:4:4 little endian */
        public const val RGBX4444: Int = 842094674
        /** 16-bit BGRx format, [15:0] B:G:R:x 4:4:4:4 little endian */
        public const val BGRX4444: Int = 842094658
        /** 16-bit ARGB format, [15:0] A:R:G:B 4:4:4:4 little endian */
        public const val ARGB4444: Int = 842093121
        /** 16-bit ABGR format, [15:0] A:B:G:R 4:4:4:4 little endian */
        public const val ABGR4444: Int = 842089025
        /** 16-bit RGBA format, [15:0] R:G:B:A 4:4:4:4 little endian */
        public const val RGBA4444: Int = 842088786
        /** 16-bit BGRA format, [15:0] B:G:R:A 4:4:4:4 little endian */
        public const val BGRA4444: Int = 842088770
        /** 16-bit xRGB format, [15:0] x:R:G:B 1:5:5:5 little endian */
        public const val XRGB1555: Int = 892424792
        /** 16-bit xBGR 1555 format, [15:0] x:B:G:R 1:5:5:5 little endian */
        public const val XBGR1555: Int = 892420696
        /** 16-bit RGBx 5551 format, [15:0] R:G:B:x 5:5:5:1 little endian */
        public const val RGBX5551: Int = 892426322
        /** 16-bit BGRx 5551 format, [15:0] B:G:R:x 5:5:5:1 little endian */
        public const val BGRX5551: Int = 892426306
        /** 16-bit ARGB 1555 format, [15:0] A:R:G:B 1:5:5:5 little endian */
        public const val ARGB1555: Int = 892424769
        /** 16-bit ABGR 1555 format, [15:0] A:B:G:R 1:5:5:5 little endian */
        public const val ABGR1555: Int = 892420673
        /** 16-bit RGBA 5551 format, [15:0] R:G:B:A 5:5:5:1 little endian */
        public const val RGBA5551: Int = 892420434
        /** 16-bit BGRA 5551 format, [15:0] B:G:R:A 5:5:5:1 little endian */
        public const val BGRA5551: Int = 892420418
        /** 16-bit RGB 565 format, [15:0] R:G:B 5:6:5 little endian */
        public const val RGB565: Int = 909199186
        /** 16-bit BGR 565 format, [15:0] B:G:R 5:6:5 little endian */
        public const val BGR565: Int = 909199170
        /** 24-bit RGB format, [23:0] R:G:B little endian */
        public const val RGB888: Int = 875710290
        /** 24-bit BGR format, [23:0] B:G:R little endian */
        public const val BGR888: Int = 875710274
        /** 32-bit xBGR format, [31:0] x:B:G:R 8:8:8:8 little endian */
        public const val XBGR8888: Int = 875709016
        /** 32-bit RGBx format, [31:0] R:G:B:x 8:8:8:8 little endian */
        public const val RGBX8888: Int = 875714642
        /** 32-bit BGRx format, [31:0] B:G:R:x 8:8:8:8 little endian */
        public const val BGRX8888: Int = 875714626
        /** 32-bit ABGR format, [31:0] A:B:G:R 8:8:8:8 little endian */
        public const val ABGR8888: Int = 875708993
        /** 32-bit RGBA format, [31:0] R:G:B:A 8:8:8:8 little endian */
        public const val RGBA8888: Int = 875708754
        /** 32-bit BGRA format, [31:0] B:G:R:A 8:8:8:8 little endian */
        public const val BGRA8888: Int = 875708738
        /** 32-bit xRGB format, [31:0] x:R:G:B 2:10:10:10 little endian */
        public const val XRGB2101010: Int = 808669784
        /** 32-bit xBGR format, [31:0] x:B:G:R 2:10:10:10 little endian */
        public const val XBGR2101010: Int = 808665688
        /** 32-bit RGBx format, [31:0] R:G:B:x 10:10:10:2 little endian */
        public const val RGBX1010102: Int = 808671314
        /** 32-bit BGRx format, [31:0] B:G:R:x 10:10:10:2 little endian */
        public const val BGRX1010102: Int = 808671298
        /** 32-bit ARGB format, [31:0] A:R:G:B 2:10:10:10 little endian */
        public const val ARGB2101010: Int = 808669761
        /** 32-bit ABGR format, [31:0] A:B:G:R 2:10:10:10 little endian */
        public const val ABGR2101010: Int = 808665665
        /** 32-bit RGBA format, [31:0] R:G:B:A 10:10:10:2 little endian */
        public const val RGBA1010102: Int = 808665426
        /** 32-bit BGRA format, [31:0] B:G:R:A 10:10:10:2 little endian */
        public const val BGRA1010102: Int = 808665410
        /** packed YCbCr format, [31:0] Cr0:Y1:Cb0:Y0 8:8:8:8 little endian */
        public const val YUYV: Int = 1448695129
        /** packed YCbCr format, [31:0] Cb0:Y1:Cr0:Y0 8:8:8:8 little endian */
        public const val YVYU: Int = 1431918169
        /** packed YCbCr format, [31:0] Y1:Cr0:Y0:Cb0 8:8:8:8 little endian */
        public const val UYVY: Int = 1498831189
        /** packed YCbCr format, [31:0] Y1:Cb0:Y0:Cr0 8:8:8:8 little endian */
        public const val VYUY: Int = 1498765654
        /** packed AYCbCr format, [31:0] A:Y:Cb:Cr 8:8:8:8 little endian */
        public const val AYUV: Int = 1448433985
        /** 2 plane YCbCr Cr:Cb format, 2x2 subsampled Cr:Cb plane */
        public const val NV12: Int = 842094158
        /** 2 plane YCbCr Cb:Cr format, 2x2 subsampled Cb:Cr plane */
        public const val NV21: Int = 825382478
        /** 2 plane YCbCr Cr:Cb format, 2x1 subsampled Cr:Cb plane */
        public const val NV16: Int = 909203022
        /** 2 plane YCbCr Cb:Cr format, 2x1 subsampled Cb:Cr plane */
        public const val NV61: Int = 825644622
        /** 3 plane YCbCr format, 4x4 subsampled Cb (1) and Cr (2) planes */
        public const val YUV410: Int = 961959257
        /** 3 plane YCbCr format, 4x4 subsampled Cr (1) and Cb (2) planes */
        public const val YVU410: Int = 961893977
        /** 3 plane YCbCr format, 4x1 subsampled Cb (1) and Cr (2) planes */
        public const val YUV411: Int = 825316697
        /** 3 plane YCbCr format, 4x1 subsampled Cr (1) and Cb (2) planes */
        public const val YVU411: Int = 825316953
        /** 3 plane YCbCr format, 2x2 subsampled Cb (1) and Cr (2) planes */
        public const val YUV420: Int = 842093913
        /** 3 plane YCbCr format, 2x2 subsampled Cr (1) and Cb (2) planes */
        public const val YVU420: Int = 842094169
        /** 3 plane YCbCr format, 2x1 subsampled Cb (1) and Cr (2) planes */
        public const val YUV422: Int = 909202777
        /** 3 plane YCbCr format, 2x1 subsampled Cr (1) and Cb (2) planes */
        public const val YVU422: Int = 909203033
        /** 3 plane YCbCr format, non-subsampled Cb (1) and Cr (2) planes */
        public const val YUV444: Int = 875713881
        /** 3 plane YCbCr format, non-subsampled Cr (1) and Cb (2) planes */
        public const val YVU444: Int = 875714137
        /** [7:0] R */
        public const val R8: Int = 538982482
        /** [15:0] R little endian */
        public const val R16: Int = 540422482
        /** [15:0] R:G 8:8 little endian */
        public const val RG88: Int = 943212370
        /** [15:0] G:R 8:8 little endian */
        public const val GR88: Int = 943215175
        /** [31:0] R:G 16:16 little endian */
        public const val RG1616: Int = 842221394
        /** [31:0] G:R 16:16 little endian */
        public const val GR1616: Int = 842224199
        /** [63:0] x:R:G:B 16:16:16:16 little endian */
        public const val XRGB16161616F: Int = 1211388504
        /** [63:0] x:B:G:R 16:16:16:16 little endian */
        public const val XBGR16161616F: Int = 1211384408
        /** [63:0] A:R:G:B 16:16:16:16 little endian */
        public const val ARGB16161616F: Int = 1211388481
        /** [63:0] A:B:G:R 16:16:16:16 little endian */
        public const val ABGR16161616F: Int = 1211384385
        /** [31:0] X:Y:Cb:Cr 8:8:8:8 little endian */
        public const val XYUV8888: Int = 1448434008
        /** [23:0] Cr:Cb:Y 8:8:8 little endian */
        public const val VUY888: Int = 875713878
        /** Y followed by U then V, 10:10:10. Non-linear modifier only */
        public const val VUY101010: Int = 808670550
        /** [63:0] Cr0:0:Y1:0:Cb0:0:Y0:0 10:6:10:6:10:6:10:6 little endian per 2 Y pixels */
        public const val Y210: Int = 808530521
        /** [63:0] Cr0:0:Y1:0:Cb0:0:Y0:0 12:4:12:4:12:4:12:4 little endian per 2 Y pixels */
        public const val Y212: Int = 842084953
        /** [63:0] Cr0:Y1:Cb0:Y0 16:16:16:16 little endian per 2 Y pixels */
        public const val Y216: Int = 909193817
        /** [31:0] A:Cr:Y:Cb 2:10:10:10 little endian */
        public const val Y410: Int = 808531033
        /** [63:0] A:0:Cr:0:Y:0:Cb:0 12:4:12:4:12:4:12:4 little endian */
        public const val Y412: Int = 842085465
        /** [63:0] A:Cr:Y:Cb 16:16:16:16 little endian */
        public const val Y416: Int = 909194329
        /** [31:0] X:Cr:Y:Cb 2:10:10:10 little endian */
        public const val XVYU2101010: Int = 808670808
        /** [63:0] X:0:Cr:0:Y:0:Cb:0 12:4:12:4:12:4:12:4 little endian */
        public const val XVYU12_16161616: Int = 909334104
        /** [63:0] X:Cr:Y:Cb 16:16:16:16 little endian */
        public const val XVYU16161616: Int = 942954072
        /** [63:0]   A3:A2:Y3:0:Cr0:0:Y2:0:A1:A0:Y1:0:Cb0:0:Y0:0  1:1:8:2:8:2:8:2:1:1:8:2:8:2:8:2 little endian */
        public const val Y0L0: Int = 810299481
        /** [63:0]   X3:X2:Y3:0:Cr0:0:Y2:0:X1:X0:Y1:0:Cb0:0:Y0:0  1:1:8:2:8:2:8:2:1:1:8:2:8:2:8:2 little endian */
        public const val X0L0: Int = 810299480
        /** [63:0]   A3:A2:Y3:Cr0:Y2:A1:A0:Y1:Cb0:Y0  1:1:10:10:10:1:1:10:10:10 little endian */
        public const val Y0L2: Int = 843853913
        /** [63:0]   X3:X2:Y3:Cr0:Y2:X1:X0:Y1:Cb0:Y0  1:1:10:10:10:1:1:10:10:10 little endian */
        public const val X0L2: Int = 843853912
        public const val YUV420_8BIT: Int = 942691673
        public const val YUV420_10BIT: Int = 808539481
        public const val XRGB8888_A8: Int = 943805016
        public const val XBGR8888_A8: Int = 943800920
        public const val RGBX8888_A8: Int = 943806546
        public const val BGRX8888_A8: Int = 943806530
        public const val RGB888_A8: Int = 943798354
        public const val BGR888_A8: Int = 943798338
        public const val RGB565_A8: Int = 943797586
        public const val BGR565_A8: Int = 943797570
        /** non-subsampled Cr:Cb plane */
        public const val NV24: Int = 875714126
        /** non-subsampled Cb:Cr plane */
        public const val NV42: Int = 842290766
        /** 2x1 subsampled Cr:Cb plane, 10 bit per channel */
        public const val P210: Int = 808530512
        /** 2x2 subsampled Cr:Cb plane 10 bits per channel */
        public const val P010: Int = 808530000
        /** 2x2 subsampled Cr:Cb plane 12 bits per channel */
        public const val P012: Int = 842084432
        /** 2x2 subsampled Cr:Cb plane 16 bits per channel */
        public const val P016: Int = 909193296
        /** [63:0] A:x:B:x:G:x:R:x 10:6:10:6:10:6:10:6 little endian */
        public const val AXBXGXRX106106106106: Int = 808534593
        /** 2x2 subsampled Cr:Cb plane */
        public const val NV15: Int = 892425806
        public const val Q410: Int = 808531025
        public const val Q401: Int = 825242705
        /** [63:0] x:R:G:B 16:16:16:16 little endian */
        public const val XRGB16161616: Int = 942953048
        /** [63:0] x:B:G:R 16:16:16:16 little endian */
        public const val XBGR16161616: Int = 942948952
        /** [63:0] A:R:G:B 16:16:16:16 little endian */
        public const val ARGB16161616: Int = 942953025
        /** [63:0] A:B:G:R 16:16:16:16 little endian */
        public const val ABGR16161616: Int = 942948929
        /** [7:0] C0:C1:C2:C3:C4:C5:C6:C7 1:1:1:1:1:1:1:1 eight pixels/byte */
        public const val C1: Int = 538980675
        /** [7:0] C0:C1:C2:C3 2:2:2:2 four pixels/byte */
        public const val C2: Int = 538980931
        /** [7:0] C0:C1 4:4 two pixels/byte */
        public const val C4: Int = 538981443
        /** [7:0] D0:D1:D2:D3:D4:D5:D6:D7 1:1:1:1:1:1:1:1 eight pixels/byte */
        public const val D1: Int = 538980676
        /** [7:0] D0:D1:D2:D3 2:2:2:2 four pixels/byte */
        public const val D2: Int = 538980932
        /** [7:0] D0:D1 4:4 two pixels/byte */
        public const val D4: Int = 538981444
        /** [7:0] D */
        public const val D8: Int = 538982468
        /** [7:0] R0:R1:R2:R3:R4:R5:R6:R7 1:1:1:1:1:1:1:1 eight pixels/byte */
        public const val R1: Int = 538980690
        /** [7:0] R0:R1:R2:R3 2:2:2:2 four pixels/byte */
        public const val R2: Int = 538980946
        /** [7:0] R0:R1 4:4 two pixels/byte */
        public const val R4: Int = 538981458
        /** [15:0] x:R 6:10 little endian */
        public const val R10: Int = 540029266
        /** [15:0] x:R 4:12 little endian */
        public const val R12: Int = 540160338
        /** [31:0] A:Cr:Cb:Y 8:8:8:8 little endian */
        public const val AVUY8888: Int = 1498764865
        /** [31:0] X:Cr:Cb:Y 8:8:8:8 little endian */
        public const val XVUY8888: Int = 1498764888
        /** 2x2 subsampled Cr:Cb plane 10 bits per channel packed */
        public const val P030: Int = 808661072
        /** [47:0] R:G:B 16:16:16 little endian */
        public const val RGB161616: Int = 942950226
        /** [47:0] B:G:R 16:16:16 little endian */
        public const val BGR161616: Int = 942950210
        /** [15:0] R 16 little endian */
        public const val R16F: Int = 1210064978
        /** [31:0] G:R 16:16 little endian */
        public const val GR1616F: Int = 1210077767
        /** [47:0] B:G:R 16:16:16 little endian */
        public const val BGR161616F: Int = 1213351746
        /** [31:0] R 32 little endian */
        public const val R32F: Int = 1176510546
        /** [63:0] G:R 32:32 little endian */
        public const val GR3232F: Int = 1176523335
        /** [95:0] B:G:R 32:32:32 little endian */
        public const val BGR323232F: Int = 1179797314
        /** [127:0] A:B:G:R 32:32:32:32 little endian */
        public const val ABGR32323232F: Int = 1178092097
        /** 2x1 subsampled Cr:Cb plane */
        public const val NV20: Int = 808605262
        /** non-subsampled Cr:Cb plane */
        public const val NV30: Int = 808670798
        /** 2x2 subsampled Cb (1) and Cr (2) planes 10 bits per channel */
        public const val S010: Int = 808530003
        /** 2x1 subsampled Cb (1) and Cr (2) planes 10 bits per channel */
        public const val S210: Int = 808530515
        /** non-subsampled Cb (1) and Cr (2) planes 10 bits per channel */
        public const val S410: Int = 808531027
        /** 2x2 subsampled Cb (1) and Cr (2) planes 12 bits per channel */
        public const val S012: Int = 842084435
        /** 2x1 subsampled Cb (1) and Cr (2) planes 12 bits per channel */
        public const val S212: Int = 842084947
        /** non-subsampled Cb (1) and Cr (2) planes 12 bits per channel */
        public const val S412: Int = 842085459
        /** 2x2 subsampled Cb (1) and Cr (2) planes 16 bits per channel */
        public const val S016: Int = 909193299
        /** 2x1 subsampled Cb (1) and Cr (2) planes 16 bits per channel */
        public const val S216: Int = 909193811
        /** non-subsampled Cb (1) and Cr (2) planes 16 bits per channel */
        public const val S416: Int = 909194323
        /** [31:0] x:Cr:Cb:Y 2:10:10:10 little endian */
        public const val XVUY2101010: Int = 808671576
        /** 2x1 subsampled Cr:Cb plane 10 bits per channel packed */
        public const val P230: Int = 808661584
        public const val T430: Int = 808662100
        /** 8-bit Y-only */
        public const val Y8: Int = 1497715271
        /** [31:0] x:Y2:Y1:Y0 2:10:10:10 little endian */
        public const val XYYY2101010: Int = 876695641
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_shm is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                val fd = args.fd()
                val size = args.int()
                args.end()
                val id = client.register(WlShmPool.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "create_pool", "id=${Wire.show(id)}, fd=${Wire.show(fd)}, size=${Wire.show(size)}")
                handler.createPool(this, id, fd, size)
            }
            1 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_shm.release needs version 2")
                args.end()
                if (Wire.tracing) Wire.request(this, "release", "")
                handler.release(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_shm request $opcode")
        }
    }

    public companion object : ResourceType<WlShm>("wl_shm", 3) {
        internal override fun create(client: Client, id: Int, version: Int): WlShm = WlShm(client, id, version)
    }
}
