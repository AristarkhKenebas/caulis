// Generated from wlr-screencopy-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlrscreencopyunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlOutput

/**
 * Manager to inform clients and begin capturing
 *
 * This object is a manager which offers requests to start capturing from a
 * source.
 */
public class ZwlrScreencopyManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrScreencopyManagerV1> get() = Companion

    /**
     * Capture an output
     *
     * Capture the next frame of an entire output.
     */
    public fun captureOutput(overlayCursor: Int, output: WlOutput): ZwlrScreencopyFrameV1 {
        val created = connection.createProxy(ZwlrScreencopyFrameV1, this.version)
        if (Wire.tracing) Wire.sent(this, "capture_output", "frame=${Wire.show(created)}, overlay_cursor=${Wire.show(overlayCursor)}, output=${Wire.show(output)}")
        request(0).newId(created).int(overlayCursor).obj(output).end()
        return created
    }

    /**
     * Capture an output's region
     *
     * Capture the next frame of an output's region.
     *
     * The region is given in output logical coordinates, see
     * xdg_output.logical_size. The region will be clipped to the output's
     * extents.
     */
    public fun captureOutputRegion(overlayCursor: Int, output: WlOutput, x: Int, y: Int, width: Int, height: Int): ZwlrScreencopyFrameV1 {
        val created = connection.createProxy(ZwlrScreencopyFrameV1, this.version)
        if (Wire.tracing) Wire.sent(this, "capture_output_region", "frame=${Wire.show(created)}, overlay_cursor=${Wire.show(overlayCursor)}, output=${Wire.show(output)}, x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
        request(1).newId(created).int(overlayCursor).obj(output).int(x).int(y).int(width).int(height).end()
        return created
    }

    /**
     * Destroy the manager
     *
     * All objects created by the manager will still remain valid, until their
     * appropriate destroy request has been called.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(2).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwlrScreencopyManagerV1>("zwlr_screencopy_manager_v1", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrScreencopyManagerV1 = ZwlrScreencopyManagerV1(connection, id, version)
    }
}
