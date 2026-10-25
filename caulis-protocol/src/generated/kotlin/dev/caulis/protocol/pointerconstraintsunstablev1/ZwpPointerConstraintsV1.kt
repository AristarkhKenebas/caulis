// Generated from pointer-constraints-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.pointerconstraintsunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlPointer
import dev.caulis.protocol.wayland.WlRegion
import dev.caulis.protocol.wayland.WlSurface

/**
 * Constrain the movement of a pointer
 *
 * The global interface exposing pointer constraining functionality. It
 * exposes two requests: lock_pointer for locking the pointer to its
 * position, and confine_pointer for locking the pointer to a region.
 *
 * The lock_pointer and confine_pointer requests create the objects
 * wp_locked_pointer and wp_confined_pointer respectively, and the client can
 * use these objects to interact with the lock.
 *
 * For any surface, only one lock or confinement may be active across all
 * wl_pointer objects of the same seat. If a lock or confinement is requested
 * when another lock or confinement is active or requested on the same surface
 * and with any of the wl_pointer objects of the same seat, an
 * 'already_constrained' error will be raised.
 */
public class ZwpPointerConstraintsV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpPointerConstraintsV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_pointer_constraints_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the pointer constraints manager object
         *
         * Used by the client to notify the server that it will no longer use this
         * pointer constraints object.
         */
        public fun destroy(resource: ZwpPointerConstraintsV1)

        /**
         * Lock pointer to a position
         *
         * The lock_pointer request lets the client request to disable movements of
         * the virtual pointer (i.e. the cursor), effectively locking the pointer
         * to a position. This request may not take effect immediately; in the
         * future, when the compositor deems implementation-specific constraints
         * are satisfied, the pointer lock will be activated and the compositor
         * sends a locked event.
         *
         * The protocol provides no guarantee that the constraints are ever
         * satisfied, and does not require the compositor to send an error if the
         * constraints cannot ever be satisfied. It is thus possible to request a
         * lock that will never activate.
         *
         * There may not be another pointer constraint of any kind requested or
         * active on the surface for any of the wl_pointer objects of the seat of
         * the passed pointer when requesting a lock. If there is, an error will be
         * raised. See general pointer lock documentation for more details.
         *
         * The intersection of the region passed with this request and the input
         * region of the surface is used to determine where the pointer must be
         * in order for the lock to activate. It is up to the compositor whether to
         * warp the pointer or require some kind of user interaction for the lock
         * to activate. If the region is null the surface input region is used.
         *
         * A surface may receive pointer focus without the lock being activated.
         *
         * The request creates a new object wp_locked_pointer which is used to
         * interact with the lock as well as receive updates about its state. See
         * the the description of wp_locked_pointer for further information.
         *
         * Note that while a pointer is locked, the wl_pointer objects of the
         * corresponding seat will not emit any wl_pointer.motion events, but
         * relative motion events will still be emitted via wp_relative_pointer
         * objects of the same seat. wl_pointer.axis and wl_pointer.button events
         * are unaffected.
         */
        public fun lockPointer(resource: ZwpPointerConstraintsV1, id: ZwpLockedPointerV1, surface: WlSurface, pointer: WlPointer, region: WlRegion?, lifetime: Int)

        /**
         * Confine pointer to a region
         *
         * The confine_pointer request lets the client request to confine the
         * pointer cursor to a given region. This request may not take effect
         * immediately; in the future, when the compositor deems implementation-
         * specific constraints are satisfied, the pointer confinement will be
         * activated and the compositor sends a confined event.
         *
         * The intersection of the region passed with this request and the input
         * region of the surface is used to determine where the pointer must be
         * in order for the confinement to activate. It is up to the compositor
         * whether to warp the pointer or require some kind of user interaction for
         * the confinement to activate. If the region is null the surface input
         * region is used.
         *
         * The request will create a new object wp_confined_pointer which is used
         * to interact with the confinement as well as receive updates about its
         * state. See the the description of wp_confined_pointer for further
         * information.
         */
        public fun confinePointer(resource: ZwpPointerConstraintsV1, id: ZwpConfinedPointerV1, surface: WlSurface, pointer: WlPointer, region: WlRegion?, lifetime: Int)
    }

    /**
     * Wp_pointer_constraints error values
     */
    public object Error {
        /** pointer constraint already requested on that surface */
        public const val ALREADY_CONSTRAINED: Int = 1
    }

    /**
     * Constraint lifetime
     */
    public object Lifetime {
        public const val ONESHOT: Int = 1
        public const val PERSISTENT: Int = 2
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_pointer_constraints_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                val pointer = args.obj(WlPointer::class.java)
                val region = args.objOrNull(WlRegion::class.java)
                val lifetime = args.uint()
                args.end()
                val id = client.register(ZwpLockedPointerV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "lock_pointer", "id=${Wire.show(id)}, surface=${Wire.show(surface)}, pointer=${Wire.show(pointer)}, region=${Wire.show(region)}, lifetime=${Wire.show(lifetime)}")
                handler.lockPointer(this, id, surface, pointer, region, lifetime)
            }
            2 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                val pointer = args.obj(WlPointer::class.java)
                val region = args.objOrNull(WlRegion::class.java)
                val lifetime = args.uint()
                args.end()
                val id = client.register(ZwpConfinedPointerV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "confine_pointer", "id=${Wire.show(id)}, surface=${Wire.show(surface)}, pointer=${Wire.show(pointer)}, region=${Wire.show(region)}, lifetime=${Wire.show(lifetime)}")
                handler.confinePointer(this, id, surface, pointer, region, lifetime)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_pointer_constraints_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpPointerConstraintsV1>("zwp_pointer_constraints_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpPointerConstraintsV1 = ZwpPointerConstraintsV1(client, id, version)
    }
}
