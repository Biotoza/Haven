package sh.haven.core.scan

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Balanced 2D k-d tree for spatial nearest-neighbor and radius queries over
 * screen-space detections.
 *
 * Alternates splitting on X (even depth) and Y (odd depth) at the median point.
 * Branch-and-bound k-NN search visits the nearer half-space first and prunes
 * the farther half-space whenever the perpendicular distance to the splitting
 * axis exceeds the current k-th best distance (or [maxDistance]).
 */
class KdTree2D<T>(
    items: List<T>,
    private val pointOf: (T) -> Pair<Double, Double>,
) {
    data class Hit<T>(
        val item: T,
        val x: Double,
        val y: Double,
        val distance: Double,
    )

    private class Node<T>(
        val item: T,
        val x: Double,
        val y: Double,
        val axis: Int,
        val left: Node<T>?,
        val right: Node<T>?,
    )

    val size: Int = items.size
    fun isEmpty(): Boolean = size == 0

    private val root: Node<T>? = if (items.isEmpty()) {
        null
    } else {
        val entries = items.map { item ->
            val (x, y) = pointOf(item)
            Triple(item, x, y)
        }.toMutableList()
        build(entries, 0, entries.size, 0)
    }

    private fun build(
        entries: MutableList<Triple<T, Double, Double>>,
        start: Int,
        end: Int,
        depth: Int,
    ): Node<T>? {
        val count = end - start
        if (count <= 0) return null
        val axis = depth and 1
        val sub = entries.subList(start, end)
        if (axis == 0) {
            sub.sortWith(compareBy<Triple<T, Double, Double>> { it.second }.thenBy { it.third })
        } else {
            sub.sortWith(compareBy<Triple<T, Double, Double>> { it.third }.thenBy { it.second })
        }
        val mid = start + count / 2
        val (item, x, y) = entries[mid]
        return Node(
            item = item,
            x = x,
            y = y,
            axis = axis,
            left = build(entries, start, mid, depth + 1),
            right = build(entries, mid + 1, end, depth + 1),
        )
    }

    /**
     * Find up to [k] items nearest to ([qx], [qy]) within [maxDistance] that
     * satisfy [filter], sorted in ascending order of Euclidean distance.
     */
    fun nearest(
        qx: Double,
        qy: Double,
        k: Int = 5,
        maxDistance: Double = Double.POSITIVE_INFINITY,
        filter: (T) -> Boolean = { true },
    ): List<Hit<T>> {
        if (k <= 0 || root == null || maxDistance < 0.0) return emptyList()
        // Max-heap by distance so heap.peek() is the current k-th farthest hit.
        val heap = PriorityQueue<Hit<T>>(k + 1) { a, b -> b.distance.compareTo(a.distance) }

        fun search(node: Node<T>?) {
            if (node == null) return
            val dist = hypot(qx - node.x, qy - node.y)
            if (dist <= maxDistance && filter(node.item)) {
                if (heap.size < k) {
                    heap.add(Hit(node.item, node.x, node.y, dist))
                } else if (dist < heap.peek()!!.distance) {
                    heap.poll()
                    heap.add(Hit(node.item, node.x, node.y, dist))
                }
            }

            val delta = if (node.axis == 0) qx - node.x else qy - node.y
            val near = if (delta <= 0.0) node.left else node.right
            val far = if (delta <= 0.0) node.right else node.left

            search(near)

            val limit = if (heap.size < k) maxDistance else minOf(maxDistance, heap.peek()!!.distance)
            if (abs(delta) <= limit) {
                search(far)
            }
        }

        search(root)
        return heap.sortedWith(compareBy<Hit<T>> { it.distance }.thenBy { it.y }.thenBy { it.x })
    }

    /**
     * Return all items within Euclidean [radius] of ([qx], [qy]) that satisfy
     * [filter], sorted in ascending order of distance.
     */
    fun withinRadius(
        qx: Double,
        qy: Double,
        radius: Double,
        filter: (T) -> Boolean = { true },
    ): List<Hit<T>> {
        if (radius < 0.0 || root == null) return emptyList()
        val out = mutableListOf<Hit<T>>()

        fun search(node: Node<T>?) {
            if (node == null) return
            val dist = hypot(qx - node.x, qy - node.y)
            if (dist <= radius && filter(node.item)) {
                out.add(Hit(node.item, node.x, node.y, dist))
            }
            val delta = if (node.axis == 0) qx - node.x else qy - node.y
            val near = if (delta <= 0.0) node.left else node.right
            val far = if (delta <= 0.0) node.right else node.left
            search(near)
            if (abs(delta) <= radius) {
                search(far)
            }
        }

        search(root)
        out.sortWith(compareBy<Hit<T>> { it.distance }.thenBy { it.y }.thenBy { it.x })
        return out
    }
}
