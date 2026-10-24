// Generated from xdg-output-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.xdgoutputunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Compositor logical output region
 *
 * An xdg_output describes part of the compositor geometry.
 *
 * This typically corresponds to a monitor that displays part of the
 * compositor space.
 *
 * For objects version 3 onwards, after all xdg_output properties have been
 * sent (when the object is created and when properties are updated), a
 * wl_output.done event is sent. This allows changes to the output
 * properties to be seen as atomic, even if they happen via multiple events.
 */
public class ZxdgOutputV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZxdgOutputV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zxdg_output_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the xdg_output object
         *
         * Using this request a client can tell the server that it is not
         * going to use the xdg_output object anymore.
         */
        public fun destroy(resource: ZxdgOutputV1)
    }

    /**
     * Position of the output within the global compositor space
     *
     * The position event describes the location of the wl_output within
     * the global compositor space.
     *
     * The logical_position event is sent after creating an xdg_output
     * (see xdg_output_manager.get_xdg_output) and whenever the location
     * of the output changes within the global compositor space.
     */
    public fun sendLogicalPosition(x: Int, y: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "logical_position", "x=${Wire.show(x)}, y=${Wire.show(y)}")
        event(0).int(x).int(y).end()
    }

    /**
     * Size of the output in the global compositor space
     *
     * The logical_size event describes the size of the output in the
     * global compositor space.
     *
     * Most regular Wayland clients should not pay attention to the
     * logical size and would rather rely on xdg_shell interfaces.
     *
     * Some clients such as Xwayland, however, need this to configure
     * their surfaces in the global compositor space as the compositor
     * may apply a different scale from what is advertised by the output
     * scaling property (to achieve fractional scaling, for example).
     *
     * For example, for a wl_output mode 3840×2160 and a scale factor 2:
     *
     * - A compositor not scaling the monitor viewport in its compositing space
     *   will advertise a logical size of 3840×2160,
     *
     * - A compositor scaling the monitor viewport with scale factor 2 will
     *   advertise a logical size of 1920×1080,
     *
     * - A compositor scaling the monitor viewport using a fractional scale of
     *   1.5 will advertise a logical size of 2560×1440.
     *
     * For example, for a wl_output mode 1920×1080 and a 90 degree rotation,
     * the compositor will advertise a logical size of 1080x1920.
     *
     * The logical_size event is sent after creating an xdg_output
     * (see xdg_output_manager.get_xdg_output) and whenever the logical
     * size of the output changes, either as a result of a change in the
     * applied scale or because of a change in the corresponding output
     * mode(see wl_output.mode) or transform (see wl_output.transform).
     */
    public fun sendLogicalSize(width: Int, height: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "logical_size", "width=${Wire.show(width)}, height=${Wire.show(height)}")
        event(1).int(width).int(height).end()
    }

    /**
     * All information about the output have been sent
     *
     * This event is sent after all other properties of an xdg_output
     * have been sent.
     *
     * This allows changes to the xdg_output properties to be seen as
     * atomic, even if they happen via multiple events.
     *
     * For objects version 3 onwards, this event is deprecated. Compositors
     * are not required to send it anymore and must send wl_output.done
     * instead.
     */
    public fun sendDone() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "done", "")
        event(2).end()
    }

    /**
     * Name of this output
     *
     * Many compositors will assign names to their outputs, show them to the
     * user, allow them to be configured by name, etc. The client may wish to
     * know this name as well to offer the user similar behaviors.
     *
     * The naming convention is compositor defined, but limited to
     * alphanumeric characters and dashes (-). Each name is unique among all
     * wl_output globals, but if a wl_output global is destroyed the same name
     * may be reused later. The names will also remain consistent across
     * sessions with the same hardware and software configuration.
     *
     * Examples of names include 'HDMI-A-1', 'WL-1', 'X11-1', etc. However, do
     * not assume that the name is a reflection of an underlying DRM
     * connector, X11 connection, etc.
     *
     * The name event is sent after creating an xdg_output (see
     * xdg_output_manager.get_xdg_output). This event is only sent once per
     * xdg_output, and the name does not change over the lifetime of the
     * wl_output global.
     *
     *        This event is deprecated, instead clients should use wl_output.name.
     *        Compositors must still support this event.
     *
     * Since version 2.
     */
    public fun sendName(name: String) {
        if (!isAlive || !client.isConnected) return
        if (version < 2) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "name", "name=${Wire.show(name)}")
        event(3).string(name).end()
    }

    /**
     * Human-readable description of this output
     *
     * Many compositors can produce human-readable descriptions of their
     * outputs.  The client may wish to know this description as well, to
     * communicate the user for various purposes.
     *
     * The description is a UTF-8 string with no convention defined for its
     * contents. Examples might include 'Foocorp 11" Display' or 'Virtual X11
     * output via :1'.
     *
     * The description event is sent after creating an xdg_output (see
     * xdg_output_manager.get_xdg_output) and whenever the description
     * changes. The description is optional, and may not be sent at all.
     *
     * For objects of version 2 and lower, this event is only sent once per
     * xdg_output, and the description does not change over the lifetime of
     * the wl_output global.
     *
     * This event is deprecated, instead clients should use
     * wl_output.description. Compositors must still support this event.
     *
     * Since version 2.
     */
    public fun sendDescription(description: String) {
        if (!isAlive || !client.isConnected) return
        if (version < 2) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "description", "description=${Wire.show(description)}")
        event(4).string(description).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zxdg_output_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zxdg_output_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZxdgOutputV1>("zxdg_output_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZxdgOutputV1 = ZxdgOutputV1(client, id, version)
    }
}
