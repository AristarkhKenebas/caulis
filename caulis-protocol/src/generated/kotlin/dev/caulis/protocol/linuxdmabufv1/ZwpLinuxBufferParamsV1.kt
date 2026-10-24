// Generated from linux-dmabuf-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.linuxdmabufv1

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlBuffer
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Parameters for creating a dmabuf-based wl_buffer
 *
 * This temporary object is a collection of dmabufs and other
 * parameters that together form a single logical buffer. The temporary
 * object may eventually create one wl_buffer unless cancelled by
 * destroying it before requesting 'create'.
 *
 * Single-planar formats only require one dmabuf, however
 * multi-planar formats may require more than one dmabuf. For all
 * formats, an 'add' request must be called once per plane (even if the
 * underlying dmabuf fd is identical).
 *
 * You must use consecutive plane indices ('plane_idx' argument for 'add')
 * from zero to the number of planes used by the drm_fourcc format code.
 * All planes required by the format must be given exactly once, but can
 * be given in any order. Each plane index can only be set once; subsequent
 * calls with a plane index which has already been set will result in a
 * plane_set error being generated.
 */
public class ZwpLinuxBufferParamsV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpLinuxBufferParamsV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_linux_buffer_params_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Delete this object, used or not
         *
         * Cleans up the temporary data sent to the server for dmabuf-based
         * wl_buffer creation.
         */
        public fun destroy(resource: ZwpLinuxBufferParamsV1)

        /**
         * Add a dmabuf to the temporary set
         *
         * This request adds one dmabuf to the set in this
         * zwp_linux_buffer_params_v1.
         *
         * The 64-bit unsigned value combined from modifier_hi and modifier_lo
         * is the dmabuf layout modifier. DRM AddFB2 ioctl calls this the
         * fb modifier, which is defined in drm_mode.h of Linux UAPI.
         * This is an opaque token. Drivers use this token to express tiling,
         * compression, etc. driver-specific modifications to the base format
         * defined by the DRM fourcc code.
         *
         * Starting from version 4, the invalid_format protocol error is sent if
         * the format + modifier pair was not advertised as supported.
         *
         * Starting from version 5, the invalid_format protocol error is sent if
         * all planes don't use the same modifier.
         *
         * This request raises the PLANE_IDX error if plane_idx is too large.
         * The error PLANE_SET is raised if attempting to set a plane that
         * was already set.
         */
        public fun add(resource: ZwpLinuxBufferParamsV1, fd: Fd, planeIdx: Int, offset: Int, stride: Int, modifierHi: Int, modifierLo: Int)

        /**
         * Create a wl_buffer from the given dmabufs
         *
         * This asks for creation of a wl_buffer from the added dmabuf
         * buffers. The wl_buffer is not created immediately but returned via
         * the 'created' event if the dmabuf sharing succeeds. The sharing
         * may fail at runtime for reasons a client cannot predict, in
         * which case the 'failed' event is triggered.
         *
         * The 'format' argument is a DRM_FORMAT code, as defined by the
         * libdrm's drm_fourcc.h. The Linux kernel's DRM sub-system is the
         * authoritative source on how the format codes should work.
         *
         * The 'flags' is a bitfield of the flags defined in enum "flags".
         * 'y_invert' means that the image needs to be y-flipped.
         *
         * Flag 'interlaced' means that the frame in the buffer is not
         * progressive as usual, but interlaced. An interlaced buffer as
         * supported here must always contain both top and bottom fields.
         * The top field always begins on the first pixel row. The temporal
         * ordering between the two fields is top field first, unless
         * 'bottom_first' is specified. It is undefined whether 'bottom_first'
         * is ignored if 'interlaced' is not set.
         *
         * This protocol does not convey any information about field rate,
         * duration, or timing, other than the relative ordering between the
         * two fields in one buffer. A compositor may have to estimate the
         * intended field rate from the incoming buffer rate. It is undefined
         * whether the time of receiving wl_surface.commit with a new buffer
         * attached, applying the wl_surface state, wl_surface.frame callback
         * trigger, presentation, or any other point in the compositor cycle
         * is used to measure the frame or field times. There is no support
         * for detecting missed or late frames/fields/buffers either, and
         * there is no support whatsoever for cooperating with interlaced
         * compositor output.
         *
         * The composited image quality resulting from the use of interlaced
         * buffers is explicitly undefined. A compositor may use elaborate
         * hardware features or software to deinterlace and create progressive
         * output frames from a sequence of interlaced input buffers, or it
         * may produce substandard image quality. However, compositors that
         * cannot guarantee reasonable image quality in all cases are recommended
         * to just reject all interlaced buffers.
         *
         * Any argument errors, including non-positive width or height,
         * mismatch between the number of planes and the format, bad
         * format, bad offset or stride, may be indicated by fatal protocol
         * errors: INCOMPLETE, INVALID_FORMAT, INVALID_DIMENSIONS,
         * OUT_OF_BOUNDS.
         *
         * Dmabuf import errors in the server that are not obvious client
         * bugs are returned via the 'failed' event as non-fatal. This
         * allows attempting dmabuf sharing and falling back in the client
         * if it fails.
         *
         * This request can be sent only once in the object's lifetime, after
         * which the only legal request is destroy. This object should be
         * destroyed after issuing a 'create' request. Attempting to use this
         * object after issuing 'create' raises the ALREADY_USED protocol error.
         *
         * It is not mandatory to issue 'create'. If a client wants to
         * cancel the buffer creation, it can just destroy this object.
         */
        public fun create(resource: ZwpLinuxBufferParamsV1, width: Int, height: Int, format: Int, flags: Int)

        /**
         * Immediately create a wl_buffer from the given                      dmabufs
         *
         * This asks for immediate creation of a wl_buffer by importing the
         * added dmabufs.
         *
         * In case of import success, no event is sent from the server, and the
         * wl_buffer is ready to be used by the client.
         *
         * Upon import failure, either of the following may happen, as seen fit
         * by the implementation:
         * - the client is terminated with one of the following fatal protocol
         *   errors:
         *   - INCOMPLETE, INVALID_FORMAT, INVALID_DIMENSIONS, OUT_OF_BOUNDS,
         *     in case of argument errors such as mismatch between the number
         *     of planes and the format, bad format, non-positive width or
         *     height, or bad offset or stride.
         *   - INVALID_WL_BUFFER, in case the cause for failure is unknown or
         *     platform specific.
         * - the server creates an invalid wl_buffer, marks it as failed and
         *   sends a 'failed' event to the client. The result of using this
         *   invalid wl_buffer as an argument in any request by the client is
         *   defined by the compositor implementation.
         *
         * This takes the same arguments as a 'create' request, and obeys the
         * same restrictions.
         *
         * Since version 2.
         */
        public fun createImmed(resource: ZwpLinuxBufferParamsV1, bufferId: WlBuffer, width: Int, height: Int, format: Int, flags: Int)

        /**
         * Set the target device of the wl_buffer
         *
         * Set the device the compositor should import the dmabufs to for sampling
         * in the next create or create_immed request.
         *
         * To avoid race conditions when the compositor removes a device from the
         * tranches, it is not a protocol error if the device hasn't been advertised
         * by the compositor in a tranche with the sampling flag, but the import is
         * likely to fail in that case.
         *
         * If the client doesn't know a suitable target device, it shouldn't set one,
         * and the compositor should attempt import on all devices it supports.
         *
         * If the array is too small to contain a dev_t or larger than required, the
         * invalid_dev_t_size error will be emitted.
         *
         * Since version 6.
         */
        public fun setSamplingDevice(resource: ZwpLinuxBufferParamsV1, device: ByteArray)
    }

    /**
     * Buffer creation succeeded
     *
     * This event indicates that the attempted buffer creation was
     * successful. It provides the new wl_buffer referencing the dmabuf(s).
     *
     * Upon receiving this event, the client should destroy the
     * zwp_linux_buffer_params_v1 object.
     */
    public fun sendCreated(buffer: WlBuffer) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "created", "buffer=${Wire.show(buffer)}")
        event(0).newId(buffer).end()
    }

    /**
     * Buffer creation failed
     *
     * This event indicates that the attempted buffer creation has
     * failed. It usually means that one of the dmabuf constraints
     * has not been fulfilled.
     *
     * Upon receiving this event, the client should destroy the
     * zwp_linux_buffer_params_v1 object.
     */
    public fun sendFailed() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "failed", "")
        event(1).end()
    }

    public object Error {
        /** the zwp_linux_buffer_params_v1 object has already been used to create a wl_buffer */
        public const val ALREADY_USED: Int = 0
        /** plane index out of bounds */
        public const val PLANE_IDX: Int = 1
        /** the plane index was already set */
        public const val PLANE_SET: Int = 2
        /** missing or too many planes to create a buffer */
        public const val INCOMPLETE: Int = 3
        /** format not supported */
        public const val INVALID_FORMAT: Int = 4
        /** invalid width or height */
        public const val INVALID_DIMENSIONS: Int = 5
        /** offset + stride * height goes out of dmabuf bounds */
        public const val OUT_OF_BOUNDS: Int = 6
        /** invalid wl_buffer resulted from importing dmabufs via                the create_immed request on given buffer_params */
        public const val INVALID_WL_BUFFER: Int = 7
        /** an array with mismatching size for a dev_t was used */
        public const val INVALID_DEV_T_SIZE: Int = 8
    }

    /**
     * Bit flags: combine with `or`.
     */
    public object Flags {
        /** contents are y-inverted */
        public const val Y_INVERT: Int = 1
        /** content is interlaced */
        public const val INTERLACED: Int = 2
        /** bottom field first */
        public const val BOTTOM_FIRST: Int = 4
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_linux_buffer_params_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val fd = args.fd()
                val planeIdx = args.uint()
                val offset = args.uint()
                val stride = args.uint()
                val modifierHi = args.uint()
                val modifierLo = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "add", "fd=${Wire.show(fd)}, plane_idx=${Wire.show(planeIdx)}, offset=${Wire.show(offset)}, stride=${Wire.show(stride)}, modifier_hi=${Wire.show(modifierHi)}, modifier_lo=${Wire.show(modifierLo)}")
                handler.add(this, fd, planeIdx, offset, stride, modifierHi, modifierLo)
            }
            2 -> {
                val width = args.int()
                val height = args.int()
                val format = args.uint()
                val flags = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "create", "width=${Wire.show(width)}, height=${Wire.show(height)}, format=${Wire.show(format)}, flags=${Wire.show(flags)}")
                handler.create(this, width, height, format, flags)
            }
            3 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwp_linux_buffer_params_v1.create_immed needs version 2")
                val bufferIdId = args.newId()
                val width = args.int()
                val height = args.int()
                val format = args.uint()
                val flags = args.uint()
                args.end()
                val bufferId = client.register(WlBuffer.create(client, bufferIdId, version))
                if (Wire.tracing) Wire.request(this, "create_immed", "buffer_id=${Wire.show(bufferId)}, width=${Wire.show(width)}, height=${Wire.show(height)}, format=${Wire.show(format)}, flags=${Wire.show(flags)}")
                handler.createImmed(this, bufferId, width, height, format, flags)
            }
            4 -> {
                if (version < 6) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwp_linux_buffer_params_v1.set_sampling_device needs version 6")
                val device = args.array()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_sampling_device", "device=${Wire.show(device)}")
                handler.setSamplingDevice(this, device)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_linux_buffer_params_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpLinuxBufferParamsV1>("zwp_linux_buffer_params_v1", 6) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpLinuxBufferParamsV1 = ZwpLinuxBufferParamsV1(client, id, version)
    }
}
