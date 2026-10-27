package dev.caulis.core.input

import dev.caulis.core.Region
import dev.caulis.core.Surface
import dev.caulis.log.Logger
import dev.caulis.protocol.Display
import dev.caulis.protocol.Resource
import dev.caulis.protocol.pointerconstraintsunstablev1.ZwpConfinedPointerV1
import dev.caulis.protocol.pointerconstraintsunstablev1.ZwpLockedPointerV1
import dev.caulis.protocol.pointerconstraintsunstablev1.ZwpPointerConstraintsV1
import dev.caulis.protocol.relativepointerunstablev1.ZwpRelativePointerManagerV1
import dev.caulis.protocol.relativepointerunstablev1.ZwpRelativePointerV1
import dev.caulis.protocol.wayland.WlPointer
import dev.caulis.protocol.wayland.WlRegion
import dev.caulis.protocol.wayland.WlSurface

/**
 * pointer-constraints and relative-pointer: games and 3D programs (Blender) hold the pointer
 * in place (locked: only its movement counts) or inside their window (confined), and get
 * the mouse's movement itself, also where the pointer cannot go further. A constraint works
 * while its window has the keyboard and the pointer is over it.
 */
class PointerConstraints(display: Display, private val seat: Seat) {

    private val log = Logger("input")
    private val relativePointers = ArrayList<ZwpRelativePointerV1>()

    /** A lock or a confinement asked for by a surface. */
    inner class Constraint internal constructor(val surface: Surface, val locks: Boolean, val resource: Resource, private val oneShot: Boolean) {
        /** Where it holds the pointer, in the surface's coordinates (null: the whole surface). */
        var region: Region.Snapshot? = null
            internal set

        /** Locked: where the pointer should be when it is let go (surface coordinates). */
        var hint: Pair<Double, Double>? = null
            internal set

        var active = false
            private set

        // set_region and set_cursor_position_hint wait for the surface's next commit (the
        // protocol makes them double-buffered state, as the surface's own).
        private var pendingRegion: Region.Snapshot? = null
        private var regionPending = false
        private var pendingHint: Pair<Double, Double>? = null

        internal fun regionAsked(region: Region.Snapshot?) {
            pendingRegion = region
            regionPending = true
        }

        internal fun hintAsked(x: Double, y: Double) {
            pendingHint = x to y
        }

        internal fun committed() {
            if (regionPending) {
                region = pendingRegion
                regionPending = false
                pendingRegion = null
            }
            pendingHint?.let {
                hint = it
                pendingHint = null
            }
        }

        internal fun activate() {
            if (active) return
            active = true
            log.info { "${resource.client.name} ${if (locks) "locks" else "confines"} the pointer" }
            if (locks) (resource as ZwpLockedPointerV1).sendLocked() else (resource as ZwpConfinedPointerV1).sendConfined()
        }

        internal fun deactivate() {
            if (!active) return
            active = false
            if (resource.isAlive) {
                if (locks) (resource as ZwpLockedPointerV1).sendUnlocked() else (resource as ZwpConfinedPointerV1).sendUnconfined()
            }
            // One-shot constraints end when they are let go.
            if (oneShot) constraints.remove(surface)
        }
    }

    private val constraints = HashMap<Surface, Constraint>()
    private val watched = HashSet<Surface>()

    /** Decides whether a surface's constraint may work now (its window has the keyboard). */
    var allowed: (Surface) -> Boolean = { surface -> seat.keyboardFocus?.resource?.client === surface.resource.client }

    init {
        display.addGlobal(ZwpRelativePointerManagerV1, 1) { manager ->
            manager.requests = object : ZwpRelativePointerManagerV1.Requests {
                override fun getRelativePointer(resource: ZwpRelativePointerManagerV1, id: ZwpRelativePointerV1, pointer: WlPointer) {
                    relativePointers += id
                    id.onDestroy { relativePointers -= id }
                    id.requests = object : ZwpRelativePointerV1.Requests {
                        override fun destroy(resource: ZwpRelativePointerV1) {}
                    }
                }

                override fun destroy(resource: ZwpRelativePointerManagerV1) {}
            }
        }
        display.addGlobal(ZwpPointerConstraintsV1, 1) { manager ->
            manager.requests = object : ZwpPointerConstraintsV1.Requests {
                override fun lockPointer(resource: ZwpPointerConstraintsV1, id: ZwpLockedPointerV1, surface: WlSurface, pointer: WlPointer, region: WlRegion?, lifetime: Int) {
                    val target = surface.dataAs<Surface>() ?: return
                    val constraint = add(resource, target, id, locks = true, region, lifetime) ?: return
                    id.requests = object : ZwpLockedPointerV1.Requests {
                        override fun setCursorPositionHint(resource: ZwpLockedPointerV1, surfaceX: Double, surfaceY: Double) {
                            constraint.hintAsked(surfaceX, surfaceY)
                        }

                        override fun setRegion(resource: ZwpLockedPointerV1, region: WlRegion?) {
                            constraint.regionAsked(region?.dataAs<Region>()?.snapshot())
                        }

                        override fun destroy(resource: ZwpLockedPointerV1) {}
                    }
                }

                override fun confinePointer(resource: ZwpPointerConstraintsV1, id: ZwpConfinedPointerV1, surface: WlSurface, pointer: WlPointer, region: WlRegion?, lifetime: Int) {
                    val target = surface.dataAs<Surface>() ?: return
                    val constraint = add(resource, target, id, locks = false, region, lifetime) ?: return
                    id.requests = object : ZwpConfinedPointerV1.Requests {
                        override fun setRegion(resource: ZwpConfinedPointerV1, region: WlRegion?) {
                            constraint.regionAsked(region?.dataAs<Region>()?.snapshot())
                        }

                        override fun destroy(resource: ZwpConfinedPointerV1) {}
                    }
                }

                override fun destroy(resource: ZwpPointerConstraintsV1) {}
            }
        }
    }

    private fun add(manager: ZwpPointerConstraintsV1, surface: Surface, id: Resource, locks: Boolean, region: WlRegion?, lifetime: Int): Constraint? {
        if (constraints[surface]?.resource?.isAlive == true) {
            manager.postError(ZwpPointerConstraintsV1.Error.ALREADY_CONSTRAINED, "this surface already has a pointer constraint")
            return null
        }
        val constraint = Constraint(surface, locks, id, oneShot = lifetime == ZwpPointerConstraintsV1.Lifetime.ONESHOT)
        constraint.region = region?.dataAs<Region>()?.snapshot()
        constraints[surface] = constraint
        id.onDestroy {
            if (constraints[surface] === constraint) constraints.remove(surface)
        }
        // Once per surface (constraints come and go; listeners on the surface would pile up).
        if (watched.add(surface)) {
            surface.resource.onDestroy {
                constraints.remove(surface)
                watched.remove(surface)
            }
            surface.onCommit { constraints[surface]?.committed() }
        }
        // The pointer is over it already: it works at once.
        if (seat.pointerFocus === surface && allowed(surface)) constraint.activate()
        return constraint
    }

    /**
     * The constraint working now: the one of the surface under the pointer, if its window has
     * the keyboard; it is told when it starts and ends.
     */
    fun current(): Constraint? {
        val under = seat.pointerFocus
        for (constraint in constraints.values.toList()) {
            val wanted = constraint.surface === under && constraint.resource.isAlive && allowed(constraint.surface)
            if (!wanted) constraint.deactivate()
        }
        val constraint = under?.let { constraints[it] }?.takeIf { it.resource.isAlive && allowed(it.surface) } ?: return null
        constraint.activate()
        return constraint
    }

    /** The mouse moved by [dx], [dy] (and [rawDx], [rawDy] before acceleration): programs that asked hear it. */
    fun relativeMotion(timeMillis: Int, dx: Double, dy: Double, rawDx: Double, rawDy: Double) {
        val client = seat.pointerFocus?.resource?.client ?: return
        if (relativePointers.none { it.client === client }) return
        val micros = Integer.toUnsignedLong(timeMillis) * 1000 // (the millisecond clock wraps after 49 days: never negative)
        for (pointer in relativePointers) {
            if (pointer.client === client) pointer.sendRelativeMotion((micros ushr 32).toInt(), micros.toInt(), dx, dy, rawDx, rawDy)
        }
    }
}
