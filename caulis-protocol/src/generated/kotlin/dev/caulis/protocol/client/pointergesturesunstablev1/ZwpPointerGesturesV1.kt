// Generated from pointer-gestures-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.pointergesturesunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlPointer

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
public class ZwpPointerGesturesV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpPointerGesturesV1> get() = Companion

    /**
     * Get swipe gesture
     *
     * Create a swipe gesture object. See the
     * wl_pointer_gesture_swipe interface for details.
     */
    public fun getSwipeGesture(pointer: WlPointer): ZwpPointerGestureSwipeV1 {
        val created = connection.createProxy(ZwpPointerGestureSwipeV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_swipe_gesture", "id=${Wire.show(created)}, pointer=${Wire.show(pointer)}")
        request(0).newId(created).obj(pointer).end()
        return created
    }

    /**
     * Get pinch gesture
     *
     * Create a pinch gesture object. See the
     * wl_pointer_gesture_pinch interface for details.
     */
    public fun getPinchGesture(pointer: WlPointer): ZwpPointerGesturePinchV1 {
        val created = connection.createProxy(ZwpPointerGesturePinchV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_pinch_gesture", "id=${Wire.show(created)}, pointer=${Wire.show(pointer)}")
        request(1).newId(created).obj(pointer).end()
        return created
    }

    /**
     * Destroy the pointer gesture object
     *
     * Destroy the pointer gesture object. Swipe, pinch and hold objects
     * created via this gesture object remain valid.
     *
     * Since version 2.
     */
    public fun release(): Unit {
        check(version >= 2) { "zwp_pointer_gestures_v1.release needs version 2, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "release", "")
        request(2).end()
        connection.destroyProxy(this)
    }

    /**
     * Get hold gesture
     *
     * Create a hold gesture object. See the
     * wl_pointer_gesture_hold interface for details.
     *
     * Since version 3.
     */
    public fun getHoldGesture(pointer: WlPointer): ZwpPointerGestureHoldV1 {
        check(version >= 3) { "zwp_pointer_gestures_v1.get_hold_gesture needs version 3, this object has $version" }
        val created = connection.createProxy(ZwpPointerGestureHoldV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_hold_gesture", "id=${Wire.show(created)}, pointer=${Wire.show(pointer)}")
        request(3).newId(created).obj(pointer).end()
        return created
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwpPointerGesturesV1>("zwp_pointer_gestures_v1", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpPointerGesturesV1 = ZwpPointerGesturesV1(connection, id, version)
    }
}
