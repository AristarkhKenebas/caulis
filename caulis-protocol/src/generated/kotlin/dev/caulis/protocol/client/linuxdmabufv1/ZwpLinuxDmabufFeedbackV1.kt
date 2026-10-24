// Generated from linux-dmabuf-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.linuxdmabufv1

import dev.caulis.ffi.Fd
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Dmabuf feedback
 *
 * This object advertises dmabuf parameters feedback. This includes the
 * preferred devices and the supported formats/modifiers.
 *
 * The parameters are sent once when this object is created and whenever they
 * change. The done event is always sent once after all parameters have been
 * sent. When a single parameter changes, all parameters are re-sent by the
 * compositor.
 *
 * Compositors can re-send the parameters when the current client buffer
 * allocations are sub-optimal. Compositors should not re-send the
 * parameters if re-allocating the buffers would not result in a more optimal
 * configuration. In particular, compositors should avoid sending the exact
 * same parameters multiple times in a row.
 *
 * The tranche_target_device and tranche_formats events are grouped by
 * tranches of preference. For each tranche, a tranche_target_device, one
 * tranche_flags and one or more tranche_formats events are sent, followed
 * by a tranche_done event finishing the list. The tranches are sent in
 * descending order of preference. All formats and modifiers in the same
 * tranche have the same preference.
 *
 * To send parameters, the compositor sends one main_device event (unless
 * the client bound version 6 or above), tranches (each consisting of one
 * tranche_target_device event, one tranche_flags event, tranche_formats
 * events and then a tranche_done event), then one done event.
 *
 * With version 6 and above, the compositor must always advertise at least
 * one tranche with the sampling flag set.
 */
public class ZwpLinuxDmabufFeedbackV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpLinuxDmabufFeedbackV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * All feedback has been sent
         *
         * This event is sent after all parameters of a zwp_linux_dmabuf_feedback_v1
         * object have been sent.
         *
         * This allows changes to the zwp_linux_dmabuf_feedback_v1 parameters to be
         * seen as atomic, even if they happen via multiple events.
         */
        public fun done(proxy: ZwpLinuxDmabufFeedbackV1) {}

        /**
         * Format and modifier table
         *
         * This event provides a file descriptor which can be memory-mapped to
         * access the format and modifier table.
         *
         * The table contains a tightly packed array of consecutive format +
         * modifier pairs. Each pair is 16 bytes wide. It contains a format as a
         * 32-bit unsigned integer, followed by 4 bytes of unused padding, and a
         * modifier as a 64-bit unsigned integer. The native endianness is used.
         *
         * The client must map the file descriptor in read-only private mode.
         *
         * Compositors are not allowed to mutate the table file contents once this
         * event has been sent. Instead, compositors must create a new, separate
         * table file and re-send feedback parameters. Compositors are allowed to
         * store duplicate format + modifier pairs in the table.
         */
        public fun formatTable(proxy: ZwpLinuxDmabufFeedbackV1, fd: Fd, size: Int) {}

        /**
         * Preferred main device
         *
         * This event advertises the main device that the server prefers to use
         * when direct scan-out to the target device isn't possible. The
         * advertised main device may be different for each
         * zwp_linux_dmabuf_feedback_v1 object, and may change over time.
         *
         * There is exactly one main device. The compositor must send at least
         * one preference tranche with tranche_target_device equal to main_device.
         *
         * Clients need to create buffers that the main device can import and
         * read from, otherwise creating the dmabuf wl_buffer will fail (see the
         * zwp_linux_buffer_params_v1.create and create_immed requests for details).
         * The main device will also likely be kept active by the compositor,
         * so clients can use it instead of waking up another device for power
         * savings.
         *
         * In general the device is a DRM node. The DRM node type (primary vs.
         * render) is unspecified. Clients must not rely on the compositor sending
         * a particular node type. Clients cannot check two devices for equality
         * by comparing the dev_t value.
         *
         * If explicit modifiers are not supported and the client performs buffer
         * allocations on a different device than the main device, then the client
         * must force the buffer to have a linear layout.
         *
         * With version 6 and above, this event is no longer sent. Clients should
         * use a device with the sampling flag in the tranches instead.
         */
        public fun mainDevice(proxy: ZwpLinuxDmabufFeedbackV1, device: ByteArray) {}

        /**
         * A preference tranche has been sent
         *
         * This event splits tranche_target_device and tranche_formats events into
         * preference tranches. It is sent after a set of tranche_target_device
         * and tranche_formats events; it represents the end of a tranche. The
         * next tranche will have a lower preference.
         */
        public fun trancheDone(proxy: ZwpLinuxDmabufFeedbackV1) {}

        /**
         * Target device
         *
         * This event advertises the target device that the server prefers to use
         * for a buffer created given this tranche. The advertised target device
         * may be different for each preference tranche, and may change over time.
         *
         * There is exactly one target device per tranche.
         *
         * The target device may be a scan-out device, for example if the
         * compositor prefers to directly scan-out a buffer created given this
         * tranche. The target device may be a rendering device, for example if
         * the compositor prefers to texture from said buffer.
         *
         * The client can use this hint to allocate the buffer in a way that makes
         * it accessible from the target device, ideally directly. The buffer must
         * still be accessible from a device with the sampling flag, either through
         * direct import or a potentially more expensive fallback path. If the
         * buffer can't be directly imported for sampling, then clients must be
         * prepared for the compositor changing the tranche priority or making
         * wl_buffer creation fail (see the zwp_linux_buffer_params_v1.create and
         * create_immed requests for details).
         *
         * If the device is a DRM node, the DRM node type (primary vs. render) is
         * unspecified. Clients must not rely on the compositor sending a
         * particular node type. Clients cannot check two devices for equality by
         * comparing the dev_t value.
         *
         * This event is tied to a preference tranche, see the tranche_done event.
         */
        public fun trancheTargetDevice(proxy: ZwpLinuxDmabufFeedbackV1, device: ByteArray) {}

        /**
         * Supported buffer format modifiers
         *
         * This event advertises the format + modifier combinations that the
         * compositor supports.
         *
         * It carries an array of indices, each referring to a format + modifier
         * pair in the last received format table (see the format_table event).
         * Each index is a 16-bit unsigned integer in native endianness.
         *
         * For legacy support, DRM_FORMAT_MOD_INVALID is an allowed modifier.
         * It indicates that the server can support the format with an implicit
         * modifier. When a buffer has DRM_FORMAT_MOD_INVALID as its modifier, it
         * is as if no explicit modifier is specified. The effective modifier
         * will be derived from the dmabuf.
         *
         * A compositor that sends valid modifiers and DRM_FORMAT_MOD_INVALID for
         * a given format supports both explicit modifiers and implicit modifiers.
         *
         * Compositors must not send duplicate format + modifier pairs within the
         * same tranche or across two different tranches with the same target
         * device and flags.
         *
         * This event is tied to a preference tranche, see the tranche_done event.
         *
         * For the definition of the format and modifier codes, see the
         * zwp_linux_buffer_params_v1.create request.
         */
        public fun trancheFormats(proxy: ZwpLinuxDmabufFeedbackV1, indices: ByteArray) {}

        /**
         * Tranche flags
         *
         * This event sets tranche-specific flags. This event is tied to a
         * preference tranche, see the tranche_done event.
         * With version 6 and above, the compositor must set at least one flag
         * in each tranche.
         */
        public fun trancheFlags(proxy: ZwpLinuxDmabufFeedbackV1, flags: Int) {}
    }

    /**
     * Destroy the feedback object
     *
     * Using this request a client can tell the server that it is not going to
     * use the zwp_linux_dmabuf_feedback_v1 object anymore.
     */
    public fun destroy(): Unit {
        request(0).end()
        connection.destroyProxy(this)
    }

    public object TrancheFlags {
        public const val SCANOUT: Int = 1
        public const val SAMPLING: Int = 2
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                args.end()
                handler?.done(this)
            }
            1 -> {
                val fd = args.fd()
                val size = args.uint()
                args.end()
                handler?.formatTable(this, fd, size)
            }
            2 -> {
                val device = args.array()
                args.end()
                handler?.mainDevice(this, device)
            }
            3 -> {
                args.end()
                handler?.trancheDone(this)
            }
            4 -> {
                val device = args.array()
                args.end()
                handler?.trancheTargetDevice(this, device)
            }
            5 -> {
                val indices = args.array()
                args.end()
                handler?.trancheFormats(this, indices)
            }
            6 -> {
                val flags = args.uint()
                args.end()
                handler?.trancheFlags(this, flags)
            }
        }
    }

    public companion object : ProxyType<ZwpLinuxDmabufFeedbackV1>("zwp_linux_dmabuf_feedback_v1", 6) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpLinuxDmabufFeedbackV1 = ZwpLinuxDmabufFeedbackV1(connection, id, version)
    }
}
