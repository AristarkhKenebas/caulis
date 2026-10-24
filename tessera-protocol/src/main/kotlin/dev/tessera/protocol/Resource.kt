package dev.tessera.protocol

/**
 * One protocol object that belongs to one client, for example a client's wl_surface.
 *
 * Generated subclasses (WlSurface, WlOutput, ...) add the requests the client can send
 * (as a `Requests` interface the compositor implements) and the events the compositor
 * can send (as `sendXxx` functions).
 */
abstract class Resource(
    /** The client that owns this object. */
    val client: Client,
    /** The number the client uses to refer to this object. */
    val id: Int,
    /** The protocol version the client asked for; newer features are only used if it allows. */
    val version: Int,
) {
    abstract val type: ResourceType<*>

    /** False once the object was destroyed (by the client, the compositor, or a disconnect). */
    var isAlive: Boolean = true
        private set

    private var destroyListeners: MutableList<() -> Unit>? = null

    /** Runs [listener] when this object goes away, for any reason. */
    fun onDestroy(listener: () -> Unit) {
        val listeners = destroyListeners ?: ArrayList<() -> Unit>().also { destroyListeners = it }
        listeners += listener
    }

    /**
     * Reports that the client used this object wrongly. The client gets the error and
     * is disconnected; the compositor keeps running.
     */
    fun postError(code: Int, message: String) {
        client.postError(this, code, message)
    }

    /** Destroys the object from the compositor's side. */
    fun destroy() {
        client.destroyResource(this)
    }

    /** Called by generated code for each request the client sent to this object. */
    internal abstract fun dispatch(opcode: Int, args: MessageReader)

    /** Generated `sendXxx` functions use this to write an event. */
    protected fun event(opcode: Int): MessageWriter = client.beginEvent(this, opcode)

    internal fun markDestroyed() {
        if (!isAlive) return
        isAlive = false
        destroyListeners?.forEach { it() }
        destroyListeners = null
    }

    override fun toString() = "${type.name}@$id"
}
