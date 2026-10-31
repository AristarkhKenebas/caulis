package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgPositioner

/** The rules a client gives for placing a popup (xdg_positioner). */
class Positioner internal constructor(resource: XdgPositioner) {

    private val rules = Rules()
    val width: Int get() = rules.width
    val height: Int get() = rules.height

    internal class Rules(
        var width: Int = 0,
        var height: Int = 0,
        var anchorRect: Rect = Rect(0, 0, 0, 0),
        var anchor: Int = XdgPositioner.Anchor.NONE,
        var gravity: Int = XdgPositioner.Gravity.NONE,
        var offsetX: Int = 0,
        var offsetY: Int = 0,
        /** What may be done when it does not fit (XdgPositioner.ConstraintAdjustment bits). */
        var adjustment: Int = 0,
    ) {
        fun copy() = Rules(width, height, anchorRect, anchor, gravity, offsetX, offsetY, adjustment)

        /**
         * Where the popup goes, relative to the parent, kept inside [bounds] (same
         * coordinates; null: anywhere) as far as the program allows: first turned to the
         * other side (a menu near the bottom opens upwards), then slid along the edge, then
         * made smaller — each only if the program said it may be.
         */
        fun place(bounds: Rect?): Rect {
            var box = Rect(place().first, place().second, width, height)
            if (bounds == null || bounds.isEmpty) return box
            fun fitsX(r: Rect) = r.x >= bounds.x && r.right <= bounds.right
            fun fitsY(r: Rect) = r.y >= bounds.y && r.bottom <= bounds.bottom
            // Turned over: anchor and gravity to the other side, the offset too.
            if (!fitsX(box) && adjustment and FLIP_X != 0) {
                val turned = Rules(width, height, anchorRect, flipX(anchor), flipX(gravity), -offsetX, offsetY).place()
                val other = Rect(turned.first, box.y, width, height)
                if (fitsX(other)) box = other
            }
            if (!fitsY(box) && adjustment and FLIP_Y != 0) {
                val turned = Rules(width, height, anchorRect, flipY(anchor), flipY(gravity), offsetX, -offsetY).place()
                val other = Rect(box.x, turned.second, width, height)
                if (fitsY(other)) box = other
            }
            // Slid along the edge (its start stays inside when it is bigger than the room).
            if (!fitsX(box) && adjustment and SLIDE_X != 0) {
                val x = if (box.right > bounds.right) bounds.right - box.width else box.x
                box = Rect(maxOf(x, bounds.x), box.y, box.width, box.height)
            }
            if (!fitsY(box) && adjustment and SLIDE_Y != 0) {
                val y = if (box.bottom > bounds.bottom) bounds.bottom - box.height else box.y
                box = Rect(box.x, maxOf(y, bounds.y), box.width, box.height)
            }
            // Made smaller to what is left.
            if (!fitsX(box) && adjustment and RESIZE_X != 0) {
                val left = maxOf(box.x, bounds.x)
                val right = minOf(box.right, bounds.right)
                if (right > left) box = Rect(left, box.y, right - left, box.height)
            }
            if (!fitsY(box) && adjustment and RESIZE_Y != 0) {
                val top = maxOf(box.y, bounds.y)
                val bottom = minOf(box.bottom, bounds.bottom)
                if (bottom > top) box = Rect(box.x, top, box.width, bottom - top)
            }
            return box
        }

        private fun flipX(edge: Int): Int = when (edge) {
            XdgPositioner.Anchor.LEFT -> XdgPositioner.Anchor.RIGHT
            XdgPositioner.Anchor.RIGHT -> XdgPositioner.Anchor.LEFT
            XdgPositioner.Anchor.TOP_LEFT -> XdgPositioner.Anchor.TOP_RIGHT
            XdgPositioner.Anchor.TOP_RIGHT -> XdgPositioner.Anchor.TOP_LEFT
            XdgPositioner.Anchor.BOTTOM_LEFT -> XdgPositioner.Anchor.BOTTOM_RIGHT
            XdgPositioner.Anchor.BOTTOM_RIGHT -> XdgPositioner.Anchor.BOTTOM_LEFT
            else -> edge
        }

        private fun flipY(edge: Int): Int = when (edge) {
            XdgPositioner.Anchor.TOP -> XdgPositioner.Anchor.BOTTOM
            XdgPositioner.Anchor.BOTTOM -> XdgPositioner.Anchor.TOP
            XdgPositioner.Anchor.TOP_LEFT -> XdgPositioner.Anchor.BOTTOM_LEFT
            XdgPositioner.Anchor.BOTTOM_LEFT -> XdgPositioner.Anchor.TOP_LEFT
            XdgPositioner.Anchor.TOP_RIGHT -> XdgPositioner.Anchor.BOTTOM_RIGHT
            XdgPositioner.Anchor.BOTTOM_RIGHT -> XdgPositioner.Anchor.TOP_RIGHT
            else -> edge
        }

        /**
         * Top left corner of the popup relative to the parent, without bounds: take the
         * anchor point on the anchor rectangle, then grow the popup from it in the gravity
         * direction.
         */
        fun place(): Pair<Int, Int> {
            val r = anchorRect
            val anchorX = when (anchor) {
                XdgPositioner.Anchor.LEFT, XdgPositioner.Anchor.TOP_LEFT, XdgPositioner.Anchor.BOTTOM_LEFT -> r.x
                XdgPositioner.Anchor.RIGHT, XdgPositioner.Anchor.TOP_RIGHT, XdgPositioner.Anchor.BOTTOM_RIGHT -> r.x + r.width
                else -> r.x + r.width / 2
            }
            val anchorY = when (anchor) {
                XdgPositioner.Anchor.TOP, XdgPositioner.Anchor.TOP_LEFT, XdgPositioner.Anchor.TOP_RIGHT -> r.y
                XdgPositioner.Anchor.BOTTOM, XdgPositioner.Anchor.BOTTOM_LEFT, XdgPositioner.Anchor.BOTTOM_RIGHT -> r.y + r.height
                else -> r.y + r.height / 2
            }
            val x = when (gravity) {
                XdgPositioner.Gravity.LEFT, XdgPositioner.Gravity.TOP_LEFT, XdgPositioner.Gravity.BOTTOM_LEFT -> anchorX - width
                XdgPositioner.Gravity.RIGHT, XdgPositioner.Gravity.TOP_RIGHT, XdgPositioner.Gravity.BOTTOM_RIGHT -> anchorX
                else -> anchorX - width / 2
            }
            val y = when (gravity) {
                XdgPositioner.Gravity.TOP, XdgPositioner.Gravity.TOP_LEFT, XdgPositioner.Gravity.TOP_RIGHT -> anchorY - height
                XdgPositioner.Gravity.BOTTOM, XdgPositioner.Gravity.BOTTOM_LEFT, XdgPositioner.Gravity.BOTTOM_RIGHT -> anchorY
                else -> anchorY - height / 2
            }
            return (x + offsetX) to (y + offsetY)
        }
    }

    internal fun copy() = rules.copy()

    init {
        resource.data = this
        resource.requests = object : XdgPositioner.Requests {
            override fun destroy(resource: XdgPositioner) {}

            override fun setSize(resource: XdgPositioner, width: Int, height: Int) {
                if (width <= 0 || height <= 0) {
                    resource.postError(XdgPositioner.Error.INVALID_INPUT, "size must be positive")
                    return
                }
                rules.width = width
                rules.height = height
            }

            override fun setAnchorRect(resource: XdgPositioner, x: Int, y: Int, width: Int, height: Int) {
                if (width < 0 || height < 0) {
                    resource.postError(XdgPositioner.Error.INVALID_INPUT, "anchor rectangle size must not be negative")
                    return
                }
                rules.anchorRect = Rect(x, y, width, height)
            }

            override fun setAnchor(resource: XdgPositioner, anchor: Int) {
                if (anchor !in 0..8) {
                    resource.postError(XdgPositioner.Error.INVALID_INPUT, "anchor $anchor is not one of the nine")
                    return
                }
                rules.anchor = anchor
            }

            override fun setGravity(resource: XdgPositioner, gravity: Int) {
                if (gravity !in 0..8) {
                    resource.postError(XdgPositioner.Error.INVALID_INPUT, "gravity $gravity is not one of the nine")
                    return
                }
                rules.gravity = gravity
            }

            override fun setConstraintAdjustment(resource: XdgPositioner, constraintAdjustment: Int) {
                rules.adjustment = constraintAdjustment
            }

            override fun setOffset(resource: XdgPositioner, x: Int, y: Int) {
                rules.offsetX = x
                rules.offsetY = y
            }

            override fun setReactive(resource: XdgPositioner) {}

            override fun setParentSize(resource: XdgPositioner, parentWidth: Int, parentHeight: Int) {}

            override fun setParentConfigure(resource: XdgPositioner, serial: Int) {}
        }
    }
}
