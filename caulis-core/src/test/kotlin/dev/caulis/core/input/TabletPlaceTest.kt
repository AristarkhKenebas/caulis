package dev.caulis.core.input

import dev.caulis.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Where on the desktop a point of a tablet is, as its settings say; and which settings a tablet gets. */
class TabletPlaceTest {

    // A 16:10 tablet (216 × 135 mm) on a 16:9 monitor at 1000, 0.
    private val monitor = Rect(1000, 0, 1920, 1080)
    private fun tablet(settings: DeviceSettings.() -> Unit = {}) =
        Tablet("Pen tablet", widthMm = 216.0, heightMm = 135.0).apply { this.settings = DeviceSettings().apply(settings) }

    private fun assertNear(expected: Pair<Double, Double>, actual: Pair<Double, Double>) {
        assertEquals(expected.first, actual.first, 0.01, "across")
        assertEquals(expected.second, actual.second, 0.01, "down")
    }

    @Test
    fun `the whole tablet on the monitor, its shape kept - a strip at the bottom is unused`() {
        val t = tablet()
        assertNear(1000.0 to 0.0, t.place(0.0, 0.0, monitor))
        // 16:9 of a 16:10 tablet: 0.9 of its height is used.
        assertNear(2919.0 to 1079.0, t.place(1.0, 0.9, monitor))
        assertNear(2919.0 to 1079.0, t.place(1.0, 1.0, monitor))
        assertNear(1960.0 to 540.0, t.place(0.5, 0.45, monitor))
    }

    @Test
    fun `without keeping the shape, the whole tablet is the whole monitor`() {
        val t = tablet { keepAspect = false }
        assertNear(2919.0 to 1079.0, t.place(1.0, 1.0, monitor))
        assertNear(1960.0 to 540.0, t.place(0.5, 0.5, monitor))
    }

    @Test
    fun `left-handed, the tablet is turned around`() {
        val t = tablet { keepAspect = false; leftHanded = true }
        assertNear(2919.0 to 1079.0, t.place(0.0, 0.0, monitor))
        assertNear(1000.0 to 0.0, t.place(1.0, 1.0, monitor))
        assertEquals(TabletAxes(tiltX = -10.0, tiltY = 5.0, rotation = 270.0), TabletAxes(tiltX = 10.0, tiltY = -5.0, rotation = 90.0).turnedAround())
    }

    @Test
    fun `a part of the tablet covers the whole monitor`() {
        val t = tablet { keepAspect = false; area = TabletArea(0.5, 0.5, 0.5, 0.5) }
        assertNear(1000.0 to 0.0, t.place(0.5, 0.5, monitor))
        assertNear(2919.0 to 1079.0, t.place(1.0, 1.0, monitor))
        assertNear(1000.0 to 0.0, t.place(0.1, 0.2, monitor)) // outside it: its nearest edge
        assertFailsWith<IllegalArgumentException> { TabletArea(0.5, 0.0, 0.6, 1.0) }
    }

    @Test
    fun `tablets have settings of their own, a device's own on top, and the pen's buttons are mouse buttons`() {
        val input = InputSettings()
        input.pointers.leftHanded = true // a left-handed mouse does not turn the tablet around
        input.tablets.monitor = "DP-1"
        input.devices["Wacom*"] = DeviceSettings().apply { monitor = "HDMI-A-1"; lowerButton = 0 }
        assertEquals(null, input.forTablet("Huion").leftHanded)
        assertEquals("DP-1", input.forTablet("Huion").monitor)
        val wacom = input.forTablet("Wacom Intuos S Pen")
        assertEquals("HDMI-A-1", wacom.monitor)
        assertEquals(0, wacom.pointerButtonOf(0x14b), "the lower button: none")
        assertEquals(0x112, wacom.pointerButtonOf(0x14c), "the upper button: middle")
        assertEquals(0x111, DeviceSettings().pointerButtonOf(0x14b), "the lower button: right")
        assertEquals(0x110, DeviceSettings().pointerButtonOf(0x110), "a tablet mouse's own buttons stay")
    }
}
