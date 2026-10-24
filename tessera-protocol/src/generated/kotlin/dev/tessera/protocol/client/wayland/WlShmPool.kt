// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.client.wayland

import dev.tessera.protocol.client.Connection
import dev.tessera.protocol.client.EventReader
import dev.tessera.protocol.client.Proxy
import dev.tessera.protocol.client.ProxyType

/**
 * A shared memory pool
 *
 * The wl_shm_pool object encapsulates a piece of memory shared
 * between the compositor and client.  Through the wl_shm_pool
 * object, the client can allocate shared memory wl_buffer objects.
 * All objects created through the same pool share the same
 * underlying mapped memory. Reusing the mapped memory avoids the
 * setup/teardown overhead and is useful when interactively resizing
 * a surface or for many small buffers.
 */
public class WlShmPool private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlShmPool> get() = Companion

    /**
     * Create a buffer from the pool
     *
     * Create a wl_buffer object from the pool.
     *
     * The buffer is created offset bytes into the pool and has
     * width and height as specified.  The stride argument specifies
     * the number of bytes from the beginning of one row to the beginning
     * of the next.  The format is the pixel format of the buffer and
     * must be one of those advertised through the wl_shm.format event.
     *
     * A buffer will keep a reference to the pool it was created from
     * so it is valid to destroy the pool immediately after creating
     * a buffer from it.
     */
    public fun createBuffer(offset: Int, width: Int, height: Int, stride: Int, format: Int): WlBuffer {
        val created = connection.createProxy(WlBuffer, this.version)
        request(0).newId(created).int(offset).int(width).int(height).int(stride).uint(format).end()
        return created
    }

    /**
     * Destroy the pool
     *
     * Destroy the shared memory pool.
     *
     * The mmapped memory will be released when all
     * buffers that have been created from this pool
     * are gone.
     */
    public fun destroy(): Unit {
        request(1).end()
        connection.destroyProxy(this)
    }

    /**
     * Change the size of the pool mapping
     *
     * This request will cause the server to remap the backing memory
     * for the pool from the file descriptor passed when the pool was
     * created, but using the new size.  This request can only be
     * used to make the pool bigger.
     *
     * This request only changes the amount of bytes that are mmapped
     * by the server and does not touch the file corresponding to the
     * file descriptor passed at creation time. It is the client's
     * responsibility to ensure that the file is at least as big as
     * the new pool size.
     */
    public fun resize(size: Int): Unit {
        request(2).int(size).end()
    }

    /** wl_shm_pool error values */
    public object Error {
        /** buffer format is not known */
        public const val INVALID_FORMAT: Int = 0
        /** invalid size or stride during buffer creation */
        public const val INVALID_STRIDE: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WlShmPool>("wl_shm_pool", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlShmPool = WlShmPool(connection, id, version)
    }
}
