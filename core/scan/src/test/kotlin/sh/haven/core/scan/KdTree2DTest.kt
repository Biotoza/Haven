package sh.haven.core.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class KdTree2DTest {

    data class Pt(val id: Int, val x: Double, val y: Double)

    @Test
    fun `nearest matches brute-force k-NN across random 2D point clouds`() {
        val rng = Random(42)
        val points = (0 until 250).map { i ->
            Pt(i, rng.nextDouble(0.0, 2560.0), rng.nextDouble(0.0, 1440.0))
        }
        val tree = KdTree2D(points) { it.x to it.y }

        repeat(40) {
            val qx = rng.nextDouble(0.0, 2560.0)
            val qy = rng.nextDouble(0.0, 1440.0)
            val k = rng.nextInt(1, 12)

            val expected = points
                .map { p -> p.id to hypot(qx - p.x, qy - p.y) }
                .sortedBy { it.second }
                .take(k)

            val actual = tree.nearest(qx, qy, k = k)
            assertEquals(expected.size, actual.size)
            for (i in expected.indices) {
                assertEquals(expected[i].first, actual[i].item.id)
                assertEquals(expected[i].second, actual[i].distance, 1e-6)
            }
        }
    }

    @Test
    fun `withinRadius and filter return exact subset sorted by distance`() {
        val points = listOf(
            Pt(1, 100.0, 100.0),
            Pt(2, 130.0, 140.0), // dist = 50 from (100,100)
            Pt(3, 200.0, 100.0), // dist = 100 from (100,100)
            Pt(4, 500.0, 500.0),
        )
        val tree = KdTree2D(points) { it.x to it.y }

        val within60 = tree.withinRadius(100.0, 100.0, radius = 60.0)
        assertEquals(listOf(1, 2), within60.map { it.item.id })

        val filtered = tree.nearest(100.0, 100.0, k = 3, filter = { it.id != 1 })
        assertEquals(listOf(2, 3, 4), filtered.map { it.item.id })
    }

    @Test
    fun `empty tree returns empty results`() {
        val tree = KdTree2D<Pt>(emptyList()) { it.x to it.y }
        assertTrue(tree.isEmpty())
        assertTrue(tree.nearest(10.0, 20.0, k = 5).isEmpty())
        assertTrue(tree.withinRadius(10.0, 20.0, radius = 100.0).isEmpty())
    }
}
