// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType

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
public class WlShmPool private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlShmPool> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_shm_pool. */
    public var requests: Requests? = null

    public interface Requests {
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
        public fun createBuffer(resource: WlShmPool, id: WlBuffer, offset: Int, width: Int, height: Int, stride: Int, format: Int)

        /**
         * Destroy the pool
         *
         * Destroy the shared memory pool.
         *
         * The mmapped memory will be released when all
         * buffers that have been created from this pool
         * are gone.
         */
        public fun destroy(resource: WlShmPool)

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
        public fun resize(resource: WlShmPool, size: Int)
    }

    /**
     * Wl_shm_pool error values
     */
    public object Error {
        /** buffer format is not known */
        public const val INVALID_FORMAT: Int = 0
        /** invalid size or stride during buffer creation */
        public const val INVALID_STRIDE: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_shm_pool is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                val offset = args.int()
                val width = args.int()
                val height = args.int()
                val stride = args.int()
                val format = args.uint()
                args.end()
                val id = client.register(WlBuffer.create(client, idId, version))
                handler.createBuffer(this, id, offset, width, height, stride, format)
            }
            1 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            2 -> {
                val size = args.int()
                args.end()
                handler.resize(this, size)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_shm_pool request $opcode")
        }
    }

    public companion object : ResourceType<WlShmPool>("wl_shm_pool", 3) {
        internal override fun create(client: Client, id: Int, version: Int): WlShmPool = WlShmPool(client, id, version)
    }
}
