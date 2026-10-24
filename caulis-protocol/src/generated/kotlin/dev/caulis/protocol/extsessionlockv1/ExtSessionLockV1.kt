// Generated from ext-session-lock-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extsessionlockv1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlSurface

/**
 * Manage lock state and create lock surfaces
 *
 * In response to the creation of this object the compositor must send
 * either the locked or finished event.
 *
 * The locked event indicates that the session is locked. This means
 * that the compositor must stop rendering and providing input to normal
 * clients. Instead the compositor must blank all outputs with an opaque
 * color such that their normal content is fully hidden.
 *
 * The only surfaces that should be rendered while the session is locked
 * are the lock surfaces created through this interface and optionally,
 * at the compositor's discretion, special privileged surfaces such as
 * input methods or portions of desktop shell UIs.
 *
 * The locked event must not be sent until a new "locked" frame (either
 * from a session lock surface or the compositor blanking the output) has
 * been presented on all outputs and no security sensitive normal/unlocked
 * content is possibly visible.
 *
 * The finished event should be sent immediately on creation of this
 * object if the compositor decides that the locked event will not be sent.
 *
 * The compositor may wait for the client to create and render session lock
 * surfaces before sending the locked event to avoid displaying intermediate
 * blank frames. However, it must impose a reasonable time limit if
 * waiting and send the locked event as soon as the hard requirements
 * described above can be met if the time limit expires. Clients should
 * immediately create lock surfaces for all outputs on creation of this
 * object to make this possible.
 *
 * This behavior of the locked event is required in order to prevent
 * possible race conditions with clients that wish to suspend the system
 * or similar after locking the session. Without these semantics, clients
 * triggering a suspend after receiving the locked event would race with
 * the first "locked" frame being presented and normal/unlocked frames
 * might be briefly visible as the system is resumed if the suspend
 * operation wins the race.
 *
 * If the client dies while the session is locked, the compositor must not
 * unlock the session in response. It is acceptable for the session to be
 * permanently locked if this happens. The compositor may choose to continue
 * to display the lock surfaces the client had mapped before it died or
 * alternatively fall back to a solid color, this is compositor policy.
 *
 * Compositors may also allow a secure way to recover the session, the
 * details of this are compositor policy. Compositors may allow a new
 * client to create a ext_session_lock_v1 object and take responsibility
 * for unlocking the session, they may even start a new lock client
 * instance automatically.
 */
public class ExtSessionLockV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtSessionLockV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_session_lock_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the session lock
         *
         * This informs the compositor that the lock object will no longer be
         * used. Existing objects created through this interface remain valid.
         *
         * After this request is made, lock surfaces created through this object
         * should be destroyed by the client as they will no longer be used by
         * the compositor.
         *
         * It is a protocol error to make this request if the locked event was
         * sent, the unlock_and_destroy request must be used instead.
         */
        public fun destroy(resource: ExtSessionLockV1)

        /**
         * Create a lock surface for a given output
         *
         * The client is expected to create lock surfaces for all outputs
         * currently present and any new outputs as they are advertised. These
         * won't be displayed by the compositor unless the lock is successful
         * and the locked event is sent.
         *
         * Providing a wl_surface which already has a role or already has a buffer
         * attached or committed is a protocol error, as is attaching/committing
         * a buffer before the first ext_session_lock_surface_v1.configure event.
         *
         * Attempting to create more than one lock surface for a given output
         * is a duplicate_output protocol error.
         */
        public fun getLockSurface(resource: ExtSessionLockV1, id: ExtSessionLockSurfaceV1, surface: WlSurface, output: WlOutput)

        /**
         * Unlock the session, destroying the object
         *
         * This request indicates that the session should be unlocked, for
         * example because the user has entered their password and it has been
         * verified by the client.
         *
         * This request also informs the compositor that the lock object will
         * no longer be used and should be destroyed. Existing objects created
         * through this interface remain valid.
         *
         * After this request is made, lock surfaces created through this object
         * should be destroyed by the client as they will no longer be used by
         * the compositor.
         *
         * It is a protocol error to make this request if the locked event has
         * not been sent. In that case, the lock object must be destroyed using
         * the destroy request.
         *
         * Note that a correct client that wishes to exit directly after unlocking
         * the session must use the wl_display.sync request to ensure the server
         * receives and processes the unlock_and_destroy request. Otherwise
         * there is no guarantee that the server has unlocked the session due
         * to the asynchronous nature of the Wayland protocol. For example,
         * the server might terminate the client with a protocol error before
         * it processes the unlock_and_destroy request.
         */
        public fun unlockAndDestroy(resource: ExtSessionLockV1)
    }

    /**
     * Session successfully locked
     *
     * This client is now responsible for displaying graphics while the
     * session is locked and deciding when to unlock the session.
     *
     * The locked event must not be sent until a new "locked" frame has been
     * presented on all outputs and no security sensitive normal/unlocked
     * content is possibly visible.
     *
     * If this event is sent, making the destroy request is a protocol error,
     * the lock object must be destroyed using the unlock_and_destroy request.
     */
    public fun sendLocked() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "locked", "")
        event(0).end()
    }

    /**
     * The session lock object should be destroyed
     *
     * The compositor has decided that the session lock should be destroyed
     * as it will no longer be used by the compositor. Exactly when this
     * event is sent is compositor policy, but it must never be sent more
     * than once for a given session lock object.
     *
     * This might be sent because there is already another ext_session_lock_v1
     * object held by a client, or the compositor has decided to deny the
     * request to lock the session for some other reason. This might also
     * be sent because the compositor implements some alternative, secure
     * way to authenticate and unlock the session.
     *
     * The finished event should be sent immediately on creation of this
     * object if the compositor decides that the locked event will not
     * be sent.
     *
     * If the locked event is sent on creation of this object the finished
     * event may still be sent at some later time in this object's
     * lifetime. This is compositor policy.
     *
     * Upon receiving this event, the client should make either the destroy
     * request or the unlock_and_destroy request, depending on whether or
     * not the locked event was received on this object.
     */
    public fun sendFinished() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "finished", "")
        event(1).end()
    }

    public object Error {
        /** attempted to destroy session lock while locked */
        public const val INVALID_DESTROY: Int = 0
        /** unlock requested but locked event was never sent */
        public const val INVALID_UNLOCK: Int = 1
        /** given wl_surface already has a role */
        public const val ROLE: Int = 2
        /** given output already has a lock surface */
        public const val DUPLICATE_OUTPUT: Int = 3
        /** given wl_surface has a buffer attached or committed */
        public const val ALREADY_CONSTRUCTED: Int = 4
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_session_lock_v1 is not implemented")
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
                val output = args.obj(WlOutput::class.java)
                args.end()
                val id = client.register(ExtSessionLockSurfaceV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_lock_surface", "id=${Wire.show(id)}, surface=${Wire.show(surface)}, output=${Wire.show(output)}")
                handler.getLockSurface(this, id, surface, output)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "unlock_and_destroy", "")
                handler.unlockAndDestroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_session_lock_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtSessionLockV1>("ext_session_lock_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtSessionLockV1 = ExtSessionLockV1(client, id, version)
    }
}
