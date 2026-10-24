// Generated from pointer-gestures-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.pointergesturesunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlPointer

/**
 * Touchpad gestures
 *
 * A global interface to provide semantic touchpad gestures for a given
 * pointer.
 *
 * Three gestures are currently supported: swipe, pinch, and hold.
 * Pinch and swipe gestures follow a three-stage cycle: begin, update,
 * end. Hold gestures follow a two-stage cycle: begin and end. All
 * gestures are identified by a unique id.
 *
 * Warning! The protocol described in this file is experimental and
 * backward incompatible changes may be made. Backward compatible changes
 * may be added together with the corresponding interface version bump.
 * Backward incompatible changes are done by bumping the version number in
 * the protocol and interface names and resetting the interface version.
 * Once the protocol is to be declared stable, the 'z' prefix and the
 * version number in the protocol and interface names are removed and the
 * interface version number is reset.
 */
public class ZwpPointerGesturesV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpPointerGesturesV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_pointer_gestures_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Get swipe gesture
         *
         * Create a swipe gesture object. See the
         * wl_pointer_gesture_swipe interface for details.
         */
        public fun getSwipeGesture(resource: ZwpPointerGesturesV1, id: ZwpPointerGestureSwipeV1, pointer: WlPointer)

        /**
         * Get pinch gesture
         *
         * Create a pinch gesture object. See the
         * wl_pointer_gesture_pinch interface for details.
         */
        public fun getPinchGesture(resource: ZwpPointerGesturesV1, id: ZwpPointerGesturePinchV1, pointer: WlPointer)

        /**
         * Destroy the pointer gesture object
         *
         * Destroy the pointer gesture object. Swipe, pinch and hold objects
         * created via this gesture object remain valid.
         *
         * Since version 2.
         */
        public fun release(resource: ZwpPointerGesturesV1)

        /**
         * Get hold gesture
         *
         * Create a hold gesture object. See the
         * wl_pointer_gesture_hold interface for details.
         *
         * Since version 3.
         */
        public fun getHoldGesture(resource: ZwpPointerGesturesV1, id: ZwpPointerGestureHoldV1, pointer: WlPointer)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_pointer_gestures_v1 is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                val pointer = args.obj(WlPointer::class.java)
                args.end()
                val id = client.register(ZwpPointerGestureSwipeV1.create(client, idId, version))
                handler.getSwipeGesture(this, id, pointer)
            }
            1 -> {
                val idId = args.newId()
                val pointer = args.obj(WlPointer::class.java)
                args.end()
                val id = client.register(ZwpPointerGesturePinchV1.create(client, idId, version))
                handler.getPinchGesture(this, id, pointer)
            }
            2 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwp_pointer_gestures_v1.release needs version 2")
                args.end()
                handler.release(this)
                client.destroyResource(this)
            }
            3 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwp_pointer_gestures_v1.get_hold_gesture needs version 3")
                val idId = args.newId()
                val pointer = args.obj(WlPointer::class.java)
                args.end()
                val id = client.register(ZwpPointerGestureHoldV1.create(client, idId, version))
                handler.getHoldGesture(this, id, pointer)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_pointer_gestures_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpPointerGesturesV1>("zwp_pointer_gestures_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpPointerGesturesV1 = ZwpPointerGesturesV1(client, id, version)
    }
}
