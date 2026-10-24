// Generated from wlr-screencopy-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrscreencopyunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlOutput

/**
 * Manager to inform clients and begin capturing
 *
 * This object is a manager which offers requests to start capturing from a
 * source.
 */
public class ZwlrScreencopyManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrScreencopyManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_screencopy_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Capture an output
         *
         * Capture the next frame of an entire output.
         */
        public fun captureOutput(resource: ZwlrScreencopyManagerV1, frame: ZwlrScreencopyFrameV1, overlayCursor: Int, output: WlOutput)

        /**
         * Capture an output's region
         *
         * Capture the next frame of an output's region.
         *
         * The region is given in output logical coordinates, see
         * xdg_output.logical_size. The region will be clipped to the output's
         * extents.
         */
        public fun captureOutputRegion(resource: ZwlrScreencopyManagerV1, frame: ZwlrScreencopyFrameV1, overlayCursor: Int, output: WlOutput, x: Int, y: Int, width: Int, height: Int)

        /**
         * Destroy the manager
         *
         * All objects created by the manager will still remain valid, until their
         * appropriate destroy request has been called.
         */
        public fun destroy(resource: ZwlrScreencopyManagerV1)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_screencopy_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                val frameId = args.newId()
                val overlayCursor = args.int()
                val output = args.obj(WlOutput::class.java)
                args.end()
                val frame = client.register(ZwlrScreencopyFrameV1.create(client, frameId, version))
                if (Wire.tracing) Wire.request(this, "capture_output", "frame=${Wire.show(frame)}, overlay_cursor=${Wire.show(overlayCursor)}, output=${Wire.show(output)}")
                handler.captureOutput(this, frame, overlayCursor, output)
            }
            1 -> {
                val frameId = args.newId()
                val overlayCursor = args.int()
                val output = args.obj(WlOutput::class.java)
                val x = args.int()
                val y = args.int()
                val width = args.int()
                val height = args.int()
                args.end()
                val frame = client.register(ZwlrScreencopyFrameV1.create(client, frameId, version))
                if (Wire.tracing) Wire.request(this, "capture_output_region", "frame=${Wire.show(frame)}, overlay_cursor=${Wire.show(overlayCursor)}, output=${Wire.show(output)}, x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler.captureOutputRegion(this, frame, overlayCursor, output, x, y, width, height)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_screencopy_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrScreencopyManagerV1>("zwlr_screencopy_manager_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrScreencopyManagerV1 = ZwlrScreencopyManagerV1(client, id, version)
    }
}
