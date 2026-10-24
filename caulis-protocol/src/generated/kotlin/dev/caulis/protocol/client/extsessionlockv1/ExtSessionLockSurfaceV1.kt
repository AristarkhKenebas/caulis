// Generated from ext-session-lock-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extsessionlockv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * A surface displayed while the session is locked
 *
 * The client may use lock surfaces to display a screensaver, render a
 * dialog to enter a password and unlock the session, or however else it
 * sees fit.
 *
 * On binding this interface the compositor will immediately send the
 * first configure event. After making the ack_configure request in
 * response to this event the client should attach and commit the first
 * buffer. Committing the surface before acking the first configure is a
 * protocol error. Committing the surface with a null buffer at any time
 * is a protocol error.
 *
 * The compositor is free to handle keyboard/pointer focus for lock
 * surfaces however it chooses. A reasonable way to do this would be to
 * give the first lock surface created keyboard focus and change keyboard
 * focus if the user clicks on other surfaces.
 */
public class ExtSessionLockSurfaceV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtSessionLockSurfaceV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * The client should resize its surface
         *
         * This event is sent once on binding the interface and may be sent again
         * at the compositor's discretion, for example if output geometry changes.
         *
         * The width and height are in surface-local coordinates and are exact
         * requirements. Failing to match these surface dimensions in the next
         * commit after acking a configure is a protocol error.
         */
        public fun configure(proxy: ExtSessionLockSurfaceV1, serial: Int, width: Int, height: Int) {}
    }

    /**
     * Destroy the lock surface object
     *
     * This informs the compositor that the lock surface object will no
     * longer be used.
     *
     * It is recommended for a lock client to destroy lock surfaces if
     * their corresponding wl_output global is removed.
     *
     * If a lock surface on an active output is destroyed before the
     * ext_session_lock_v1.unlock_and_destroy event is sent, the compositor
     * must fall back to rendering a solid color.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Ack a configure event
     *
     * When a configure event is received, if a client commits the surface
     * in response to the configure event, then the client must make an
     * ack_configure request sometime before the commit request, passing
     * along the serial of the configure event.
     *
     * If the client receives multiple configure events before it can
     * respond to one, it only has to ack the last configure event.
     *
     * A client is not required to commit immediately after sending an
     * ack_configure request - it may even ack_configure several times
     * before its next surface commit.
     *
     * A client may send multiple ack_configure requests before committing,
     * but only the last request sent before a commit indicates which
     * configure event the client really is responding to.
     *
     * Sending an ack_configure request consumes the configure event
     * referenced by the given serial, as well as all older configure events
     * sent on this object.
     *
     * It is a protocol error to issue multiple ack_configure requests
     * referencing the same configure event or to issue an ack_configure
     * request referencing a configure event older than the last configure
     * event acked for a given lock surface.
     */
    public fun ackConfigure(serial: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "ack_configure", "serial=${Wire.show(serial)}")
        request(1).uint(serial).end()
    }

    public object Error {
        /** surface committed before first ack_configure request */
        public const val COMMIT_BEFORE_FIRST_ACK: Int = 0
        /** surface committed with a null buffer */
        public const val NULL_BUFFER: Int = 1
        /** failed to match ack'd width/height */
        public const val DIMENSIONS_MISMATCH: Int = 2
        /** serial provided in ack_configure is invalid */
        public const val INVALID_SERIAL: Int = 3
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val serial = args.uint()
                val width = args.uint()
                val height = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "configure", "serial=${Wire.show(serial)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler?.configure(this, serial, width, height)
            }
        }
    }

    public companion object : ProxyType<ExtSessionLockSurfaceV1>("ext_session_lock_surface_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtSessionLockSurfaceV1 = ExtSessionLockSurfaceV1(connection, id, version)
    }
}
