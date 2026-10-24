// Generated from pointer-gestures-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.pointergesturesunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSurface

/**
 * A hold gesture object
 *
 * A hold gesture object notifies a client about a single- or
 * multi-finger hold gesture detected on an indirect input device such as
 * a touchpad. The gesture is usually initiated by one or more fingers
 * being held down without significant movement. The precise conditions
 * of when such a gesture is detected are implementation-dependent.
 *
 * In particular, this gesture may be used to cancel kinetic scrolling.
 *
 * A hold gesture consists of two stages: begin and end. Unlike pinch and
 * swipe there is no update stage.
 * There cannot be multiple simultaneous hold, pinch or swipe gestures on a
 * same pointer/seat, how compositors prevent these situations is
 * implementation-dependent.
 *
 * A gesture may be cancelled by the compositor or the hardware.
 * Clients should not consider performing permanent or irreversible
 * actions until the end of a gesture has been received.
 */
public class ZwpPointerGestureHoldV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpPointerGestureHoldV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_pointer_gesture_hold_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the hold gesture object
         *
         * Since version 3.
         */
        public fun destroy(resource: ZwpPointerGestureHoldV1)
    }

    /**
     * Multi-finger hold begin
     *
     * This event is sent when a hold gesture is detected on the device.
     *
     * Since version 3.
     */
    public fun sendBegin(serial: Int, time: Int, surface: WlSurface, fingers: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        event(0).uint(serial).uint(time).obj(surface).uint(fingers).end()
    }

    /**
     * Multi-finger hold end
     *
     * This event is sent when a hold gesture ceases to
     * be valid. This may happen when the holding fingers are lifted or
     * the gesture is cancelled, for example if the fingers move past an
     * implementation-defined threshold, the finger count changes or the hold
     * gesture changes into a different type of gesture.
     *
     * When a gesture is cancelled, the client may need to undo state changes
     * caused by this gesture. What causes a gesture to be cancelled is
     * implementation-dependent.
     *
     * Since version 3.
     */
    public fun sendEnd(serial: Int, time: Int, cancelled: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        event(1).uint(serial).uint(time).int(cancelled).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_pointer_gesture_hold_v1 is not implemented")
        when (opcode) {
            0 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwp_pointer_gesture_hold_v1.destroy needs version 3")
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_pointer_gesture_hold_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpPointerGestureHoldV1>("zwp_pointer_gesture_hold_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpPointerGestureHoldV1 = ZwpPointerGestureHoldV1(client, id, version)
    }
}
