package sh.haven.core.glkvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GlkvmGeometryTest {

    private val w = 2560
    private val h = 1440

    @Test
    fun `left dead-margin edge maps to axis min`() {
        assertEquals(-32768, GlkvmGeometry.vFromPxX(0.05 * w, w))
    }

    @Test
    fun `right edge maps to axis max`() {
        assertEquals(32767, GlkvmGeometry.vFromPxX(w.toDouble(), w))
    }

    @Test
    fun `frame centre maps to axis centre`() {
        val v = GlkvmGeometry.vFromPxX(w / 2.0, w)
        assertTrue("centre v=$v expected |v| < 2", abs(v) < 2)
        assertEquals(0, GlkvmGeometry.vFromPxY(h / 2.0, h))
    }

    @Test
    fun `full height spans the y axis`() {
        assertEquals(-32768, GlkvmGeometry.vFromPxY(0.0, h))
        assertEquals(32767, GlkvmGeometry.vFromPxY(h.toDouble(), h))
    }

    @Test
    fun `round trip stays within a pixel inside the reachable band`() {
        // 1px accuracy claim (kvmd.py) holds within [0.05W, 0.95W]: the GL
        // absolute-mouse mode maps exactly the middle 90% of width, so pixels
        // outside the band are simply unreachable (not rounding error).
        for (x in listOf(128, 400, 1280, 2000, 2432)) {
            val v = GlkvmGeometry.vFromPxX(x.toDouble(), w)
            val (bx, _) = GlkvmGeometry.pxFromV(v, 0, w, h)
            assertTrue("x=$x round trip ${abs(bx - x)}px", abs(bx - x) <= 1.0)
        }
        for (y in listOf(0, 100, 720, 1300, 1440)) {
            val v = GlkvmGeometry.vFromPxY(y.toDouble(), h)
            val (_, by) = GlkvmGeometry.pxFromV(0, v, w, h)
            assertTrue("y=$y round trip ${abs(by - y)}px", abs(by - y) <= 1.0)
        }
    }

    @Test
    fun `the five percent side margins clamp at the reachable edge`() {
        // Documented limitation, same as kvmd.py: vFromPx clamps to int16, so
        // a coordinate in the outer 5% of width lands at the nearest reachable
        // pixel (2432 at 2560). The device simply has no axis value there.
        val (bx, _) = GlkvmGeometry.pxFromV(GlkvmGeometry.vFromPxX(2450.0, w), 0, w, h)
        assertEquals(2432.0, bx, 1.0)
        val (lx, _) = GlkvmGeometry.pxFromV(GlkvmGeometry.vFromPxX(30.0, w), 0, w, h)
        assertEquals(128.0, lx, 1.0)
    }

    @Test
    fun `clamps stay inside int16`() {
        assertEquals(-32768, GlkvmGeometry.vFromPxX(-50.0, w))
        assertEquals(32767, GlkvmGeometry.vFromPxX(999_999.0, w))
        assertEquals(-32768, GlkvmGeometry.vFromPxY(-1.0, h))
        assertEquals(32767, GlkvmGeometry.vFromPxY(9_999.0, h))
    }

    @Test
    fun `values match the reference python for known points`() {
        // Hand-computed from the kvmd.py formulas at 2560x1440.
        // x=640: (640-128)*65535/2304 - 32768 = 512*28.4166.. - 32768 ≈ -18255 (-ish)
        // verify against the formula directly rather than a magic constant
        val expected = Math.round((640 - 0.05 * 2560) * 65535.0 / (0.90 * 2560)) - 32768
        assertEquals(expected, GlkvmGeometry.vFromPxX(640.0, w).toLong())
    }
}