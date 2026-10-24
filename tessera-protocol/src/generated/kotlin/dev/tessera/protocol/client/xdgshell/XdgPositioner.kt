// Generated from xdg-shell.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.client.xdgshell

import dev.tessera.protocol.client.Connection
import dev.tessera.protocol.client.EventReader
import dev.tessera.protocol.client.Proxy
import dev.tessera.protocol.client.ProxyType

/**
 * Child surface positioner
 *
 * The xdg_positioner provides a collection of rules for the placement of a
 * child surface relative to a parent surface. Rules can be defined to ensure
 * the child surface remains within the visible area's borders, and to
 * specify how the child surface changes its position, such as sliding along
 * an axis, or flipping around a rectangle. These positioner-created rules are
 * constrained by the requirement that a child surface must intersect with or
 * be at least partially adjacent to its parent surface.
 *
 * See the various requests for details about possible rules.
 *
 * At the time of the request, the compositor makes a copy of the rules
 * specified by the xdg_positioner. Thus, after the request is complete the
 * xdg_positioner object can be destroyed or reused; further changes to the
 * object will have no effect on previous usages.
 *
 * For an xdg_positioner object to be considered complete, it must have a
 * non-zero size set by set_size, and a non-zero anchor rectangle set by
 * set_anchor_rect. Passing an incomplete xdg_positioner object when
 * positioning a surface raises an invalid_positioner error.
 */
public class XdgPositioner private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<XdgPositioner> get() = Companion

    /**
     * Destroy the xdg_positioner object
     *
     * Notify the compositor that the xdg_positioner will no longer be used.
     */
    public fun destroy(): Unit {
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Set the size of the to-be positioned rectangle
     *
     * Set the size of the surface that is to be positioned with the positioner
     * object. The size is in surface-local coordinates and corresponds to the
     * window geometry. See xdg_surface.set_window_geometry.
     *
     * If a zero or negative size is set the invalid_input error is raised.
     */
    public fun setSize(width: Int, height: Int): Unit {
        request(1).int(width).int(height).end()
    }

    /**
     * Set the anchor rectangle within the parent surface
     *
     * Specify the anchor rectangle within the parent surface that the child
     * surface will be placed relative to. The rectangle is relative to the
     * window geometry as defined by xdg_surface.set_window_geometry of the
     * parent surface.
     *
     * When the xdg_positioner object is used to position a child surface, the
     * anchor rectangle may not extend outside the window geometry of the
     * positioned child's parent surface.
     *
     * If a negative size is set the invalid_input error is raised.
     */
    public fun setAnchorRect(x: Int, y: Int, width: Int, height: Int): Unit {
        request(2).int(x).int(y).int(width).int(height).end()
    }

    /**
     * Set anchor rectangle anchor
     *
     * Defines the anchor point for the anchor rectangle. The specified anchor
     * is used to derive an anchor point that the child surface will be
     * positioned relative to. If a corner anchor is set (e.g. 'top_left' or
     * 'bottom_right'), the anchor point will be at the specified corner;
     * otherwise, the derived anchor point will be centered on the specified
     * edge, or in the center of the anchor rectangle if no edge is specified.
     */
    public fun setAnchor(anchor: Int): Unit {
        request(3).uint(anchor).end()
    }

    /**
     * Set child surface gravity
     *
     * Defines in what direction a surface should be positioned, relative to
     * the anchor point of the parent surface. If a corner gravity is
     * specified (e.g. 'bottom_right' or 'top_left'), then the child surface
     * will be placed towards the specified gravity; otherwise, the child
     * surface will be centered over the anchor point on any axis that had no
     * gravity specified. If the gravity is not in the ‘gravity’ enum, an
     * invalid_input error is raised.
     */
    public fun setGravity(gravity: Int): Unit {
        request(4).uint(gravity).end()
    }

    /**
     * Set the adjustment to be done when constrained
     *
     * Specify how the window should be positioned if the originally intended
     * position caused the surface to be constrained, meaning at least
     * partially outside positioning boundaries set by the compositor. The
     * adjustment is set by constructing a bitmask describing the adjustment to
     * be made when the surface is constrained on that axis.
     *
     * If no bit for one axis is set, the compositor will assume that the child
     * surface should not change its position on that axis when constrained.
     *
     * If more than one bit for one axis is set, the order of how adjustments
     * are applied is specified in the corresponding adjustment descriptions.
     *
     * The default adjustment is none.
     */
    public fun setConstraintAdjustment(constraintAdjustment: Int): Unit {
        request(5).uint(constraintAdjustment).end()
    }

    /**
     * Set surface position offset
     *
     * Specify the surface position offset relative to the position of the
     * anchor on the anchor rectangle and the anchor on the surface. For
     * example if the anchor of the anchor rectangle is at (x, y), the surface
     * has the gravity bottom|right, and the offset is (ox, oy), the calculated
     * surface position will be (x + ox, y + oy). The offset position of the
     * surface is the one used for constraint testing. See
     * set_constraint_adjustment.
     *
     * An example use case is placing a popup menu on top of a user interface
     * element, while aligning the user interface element of the parent surface
     * with some user interface element placed somewhere in the popup surface.
     */
    public fun setOffset(x: Int, y: Int): Unit {
        request(6).int(x).int(y).end()
    }

    /**
     * Continuously reconstrain the surface
     *
     * When set reactive, the surface is reconstrained if the conditions used
     * for constraining changed, e.g. the parent window moved.
     *
     * If the conditions changed and the popup was reconstrained, an
     * xdg_popup.configure event is sent with updated geometry, followed by an
     * xdg_surface.configure event.
     *
     * Since version 3.
     */
    public fun setReactive(): Unit {
        check(version >= 3) { "xdg_positioner.set_reactive needs version 3, this object has $version" }
        request(7).end()
    }

    /**
     * Set parent size
     *
     * Set the parent window geometry the compositor should use when
     * positioning the popup. The compositor may use this information to
     * determine the future state the popup should be constrained using. If
     * this doesn't match the dimension of the parent the popup is eventually
     * positioned against, the behavior is undefined.
     *
     * The arguments are given in the surface-local coordinate space.
     *
     * Since version 3.
     */
    public fun setParentSize(parentWidth: Int, parentHeight: Int): Unit {
        check(version >= 3) { "xdg_positioner.set_parent_size needs version 3, this object has $version" }
        request(8).int(parentWidth).int(parentHeight).end()
    }

    /**
     * Set parent configure this is a response to
     *
     * Set the serial of an xdg_surface.configure event this positioner will be
     * used in response to. The compositor may use this information together
     * with set_parent_size to determine what future state the popup should be
     * constrained using.
     *
     * Since version 3.
     */
    public fun setParentConfigure(serial: Int): Unit {
        check(version >= 3) { "xdg_positioner.set_parent_configure needs version 3, this object has $version" }
        request(9).uint(serial).end()
    }

    public object Error {
        /** invalid input provided */
        public const val INVALID_INPUT: Int = 0
    }

    public object Anchor {
        public const val NONE: Int = 0
        public const val TOP: Int = 1
        public const val BOTTOM: Int = 2
        public const val LEFT: Int = 3
        public const val RIGHT: Int = 4
        public const val TOP_LEFT: Int = 5
        public const val BOTTOM_LEFT: Int = 6
        public const val TOP_RIGHT: Int = 7
        public const val BOTTOM_RIGHT: Int = 8
    }

    public object Gravity {
        public const val NONE: Int = 0
        public const val TOP: Int = 1
        public const val BOTTOM: Int = 2
        public const val LEFT: Int = 3
        public const val RIGHT: Int = 4
        public const val TOP_LEFT: Int = 5
        public const val BOTTOM_LEFT: Int = 6
        public const val TOP_RIGHT: Int = 7
        public const val BOTTOM_RIGHT: Int = 8
    }

    /** constraint adjustments */
    public object ConstraintAdjustment {
        public const val NONE: Int = 0
        public const val SLIDE_X: Int = 1
        public const val SLIDE_Y: Int = 2
        public const val FLIP_X: Int = 4
        public const val FLIP_Y: Int = 8
        public const val RESIZE_X: Int = 16
        public const val RESIZE_Y: Int = 32
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<XdgPositioner>("xdg_positioner", 7) {
        internal override fun create(connection: Connection, id: Int, version: Int): XdgPositioner = XdgPositioner(connection, id, version)
    }
}
