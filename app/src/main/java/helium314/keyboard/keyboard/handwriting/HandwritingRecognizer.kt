// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.handwriting

import helium314.keyboard.latin.utils.LogCatcher
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure Kotlin, zero-dependency, 0MB on-device geometric gesture and character recognizer
 * based on the $P (Point-Cloud) algorithmic model.
 *
 * Designed for real-time single-character recognition (A-Z, a-z, 0-9, punctuation) and
 * fast gesture commands (swipe left = backspace, swipe right = space, return = enter).
 */
object HandwritingRecognizer {
    private const val TAG = "HandwritingRecognizer"
    private const val NUM_POINTS = 32
    private const val BOUNDING_BOX_SIZE = 100.0f

    enum class Gesture { NONE, BACKSPACE, SPACE, ENTER }

    data class Point(val x: Float, val y: Float, val strokeId: Int = 0)

    data class Candidate(val text: String, val confidence: Float)

    private class Template(val name: String, val points: Array<Point>)

    private val templates = mutableListOf<Template>()

    init {
        initializeTemplates()
        LogCatcher.markComponentActive("HandwritingEngine", "Init", "Loaded ${templates.size} templates")
    }

    /**
     * Checks if a single stroke represents an immediate editing gesture.
     */
    fun detectGesture(points: List<Point>): Gesture {
        if (points.size < 4) return Gesture.NONE

        val start = points.first()
        val end = points.last()
        val dx = end.x - start.x
        val dy = end.y - start.y
        val absDx = abs(dx)
        val absDy = abs(dy)

        // Fast horizontal swipe left -> Backspace
        if (dx < -140f && absDy < 80f) {
            return Gesture.BACKSPACE
        }
        // Fast horizontal swipe right -> Space
        if (dx > 140f && absDy < 80f) {
            return Gesture.SPACE
        }
        // Down-right hook/return -> Enter
        if (dy > 150f && absDx < 90f) {
            // Check if stroke finishes with a return angle or sharp stop
            return Gesture.ENTER
        }

        return Gesture.NONE
    }

    /**
     * Recognizes character strokes and returns ranked candidates.
     */
    fun recognize(rawStrokes: List<List<Point>>): List<Candidate> {
        val flatPoints = mutableListOf<Point>()
        rawStrokes.forEachIndexed { sId, stroke ->
            stroke.forEach { p ->
                flatPoints.add(Point(p.x, p.y, sId))
            }
        }

        if (flatPoints.size < 3) return emptyList()

        val normalized = normalize(flatPoints)
        if (normalized.isEmpty()) return emptyList()

        val results = mutableListOf<Candidate>()
        for (template in templates) {
            val dist = greedyCloudMatch(normalized, template.points)
            // Normalizing score: 0 distance = 1.0, max distance ~140.0 (diagonal of 100x100) = 0.0
            val score = max(0f, 1.0f - (dist / (BOUNDING_BOX_SIZE * 0.9f)))
            results.add(Candidate(template.name, score))
        }

        // Group by label, take maximum score per unique character, and sort descending
        return results.groupBy { it.text }
            .map { (text, list) -> Candidate(text, list.maxOf { it.confidence }) }
            .sortedByDescending { it.confidence }
            .take(5)
    }

    // --- Algorithmic Normalization Pipeline ($P) ---

    private fun normalize(points: List<Point>): Array<Point> {
        val resampled = resample(points, NUM_POINTS)
        val scaled = scale(resampled)
        return translateToOrigin(scaled)
    }

    private fun resample(points: List<Point>, n: Int): List<Point> {
        val pathLength = pathLength(points)
        if (pathLength == 0f) return points

        val interval = pathLength / (n - 1)
        var d = 0f
        val newPoints = ArrayList<Point>(n)
        newPoints.add(points[0])

        var i = 1
        val pts = points.toMutableList()
        while (i < pts.size) {
            if (pts[i].strokeId == pts[i - 1].strokeId) {
                val dist = distance(pts[i - 1], pts[i])
                if (d + dist >= interval) {
                    val t = (interval - d) / dist
                    val qx = pts[i - 1].x + t * (pts[i].x - pts[i - 1].x)
                    val qy = pts[i - 1].y + t * (pts[i].y - pts[i - 1].y)
                    val q = Point(qx, qy, pts[i].strokeId)
                    newPoints.add(q)
                    pts.add(i, q)
                    d = 0f
                } else {
                    d += dist
                }
            }
            i++
        }

        while (newPoints.size < n) {
            newPoints.add(points.last())
        }
        return newPoints.take(n)
    }

    private fun scale(points: List<Point>): List<Point> {
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (p in points) {
            minX = min(minX, p.x)
            maxX = max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = max(maxY, p.y)
        }

        val width = max(1f, maxX - minX)
        val height = max(1f, maxY - minY)

        // Aspect ratio safeguard: preserve 1D lines (like '1', 'I', 'l', '-')
        val isVerticalLine = width / height < 0.2f
        val isHorizontalLine = height / width < 0.2f

        return points.map { p ->
            val sx = if (isVerticalLine) (p.x - minX) * (BOUNDING_BOX_SIZE * 0.2f / width) else (p.x - minX) * (BOUNDING_BOX_SIZE / width)
            val sy = if (isHorizontalLine) (p.y - minY) * (BOUNDING_BOX_SIZE * 0.2f / height) else (p.y - minY) * (BOUNDING_BOX_SIZE / height)
            Point(sx, sy, p.strokeId)
        }
    }

    private fun translateToOrigin(points: List<Point>): Array<Point> {
        var sumX = 0f
        var sumY = 0f
        for (p in points) {
            sumX += p.x
            sumY += p.y
        }
        val centroidX = sumX / points.size
        val centroidY = sumY / points.size

        return Array(points.size) { i ->
            Point(points[i].x - centroidX, points[i].y - centroidY, points[i].strokeId)
        }
    }

    private fun greedyCloudMatch(points1: Array<Point>, points2: Array<Point>): Float {
        val n = points1.size
        val eps = 0.5f
        val step = max(1, (Math.pow(n.toDouble(), 1.0 - eps)).toInt())

        var minDistance = Float.MAX_VALUE
        for (i in 0 until n step step) {
            val d1 = cloudDistance(points1, points2, i)
            val d2 = cloudDistance(points2, points1, i)
            minDistance = min(minDistance, min(d1, d2))
        }
        return minDistance
    }

    private fun cloudDistance(points1: Array<Point>, points2: Array<Point>, startIndex: Int): Float {
        val n = points1.size
        val matched = BooleanArray(n)
        var sum = 0f
        var i = startIndex
        do {
            var index = -1
            var minD = Float.MAX_VALUE
            for (j in 0 until n) {
                if (!matched[j]) {
                    val d = distance(points1[i], points2[j])
                    if (d < minD) {
                        minD = d
                        index = j
                    }
                }
            }
            if (index != -1) {
                matched[index] = true
                val weight = 1.0f - ((i - startIndex + n) % n) / (2.0f * n)
                sum += weight * minD
            }
            i = (i + 1) % n
        } while (i != startIndex)
        return sum
    }

    private fun distance(p1: Point, p2: Point): Float =
        hypot(p1.x - p2.x, p1.y - p2.y)

    private fun pathLength(points: List<Point>): Float {
        var d = 0f
        for (i in 1 until points.size) {
            if (points[i].strokeId == points[i - 1].strokeId) {
                d += distance(points[i - 1], points[i])
            }
        }
        return d
    }

    // --- Embedded Geometric Prototype Templates ---

    private fun addTemplate(name: String, vararg coords: Float) {
        val pts = mutableListOf<Point>()
        var sId = 0
        var i = 0
        while (i < coords.size) {
            val x = coords[i]
            val y = coords[i + 1]
            if (x < 0 && y < 0) {
                sId++
                i += 2
                continue
            }
            pts.add(Point(x, y, sId))
            i += 2
        }
        if (pts.size >= 2) {
            templates.add(Template(name, normalize(pts)))
        }
    }

    private fun initializeTemplates() {
        // Uppercase
        addTemplate("A", 10f, 100f, 50f, 0f, 90f, 100f, -1f, -1f, 30f, 60f, 70f, 60f)
        addTemplate("B", 20f, 100f, 20f, 0f, 70f, 0f, 70f, 50f, 20f, 50f, 80f, 50f, 80f, 100f, 20f, 100f)
        addTemplate("C", 80f, 15f, 50f, 0f, 20f, 25f, 20f, 75f, 50f, 100f, 80f, 85f)
        addTemplate("D", 20f, 100f, 20f, 0f, 70f, 10f, 80f, 50f, 70f, 90f, 20f, 100f)
        addTemplate("E", 20f, 100f, 20f, 0f, 80f, 0f, -1f, -1f, 20f, 50f, 70f, 50f, -1f, -1f, 20f, 100f, 80f, 100f)
        addTemplate("F", 20f, 100f, 20f, 0f, 80f, 0f, -1f, -1f, 20f, 50f, 70f, 50f)
        addTemplate("G", 80f, 20f, 50f, 0f, 20f, 25f, 20f, 75f, 50f, 100f, 80f, 80f, 80f, 50f, 50f, 50f)
        addTemplate("H", 20f, 0f, 20f, 100f, -1f, -1f, 80f, 0f, 80f, 100f, -1f, -1f, 20f, 50f, 80f, 50f)
        addTemplate("I", 50f, 0f, 50f, 100f)
        addTemplate("J", 80f, 0f, 80f, 75f, 50f, 100f, 20f, 80f)
        addTemplate("K", 20f, 0f, 20f, 100f, -1f, -1f, 80f, 0f, 20f, 50f, 80f, 100f)
        addTemplate("L", 20f, 0f, 20f, 100f, 80f, 100f)
        addTemplate("M", 10f, 100f, 10f, 0f, 50f, 60f, 90f, 0f, 90f, 100f)
        addTemplate("N", 15f, 100f, 15f, 0f, 85f, 100f, 85f, 0f)
        addTemplate("O", 50f, 0f, 80f, 25f, 80f, 75f, 50f, 100f, 20f, 75f, 20f, 25f, 50f, 0f)
        addTemplate("P", 20f, 100f, 20f, 0f, 75f, 0f, 75f, 50f, 20f, 50f)
        addTemplate("Q", 50f, 0f, 80f, 25f, 80f, 75f, 50f, 100f, 20f, 75f, 20f, 25f, 50f, 0f, -1f, -1f, 60f, 70f, 90f, 100f)
        addTemplate("R", 20f, 100f, 20f, 0f, 75f, 0f, 75f, 50f, 20f, 50f, 80f, 100f)
        addTemplate("S", 80f, 15f, 50f, 0f, 20f, 25f, 80f, 75f, 50f, 100f, 20f, 85f)
        addTemplate("T", 10f, 0f, 90f, 0f, -1f, -1f, 50f, 0f, 50f, 100f)
        addTemplate("U", 20f, 0f, 20f, 75f, 50f, 100f, 80f, 75f, 80f, 0f)
        addTemplate("V", 15f, 0f, 50f, 100f, 85f, 0f)
        addTemplate("W", 10f, 0f, 30f, 100f, 50f, 40f, 70f, 100f, 90f, 0f)
        addTemplate("X", 15f, 0f, 85f, 100f, -1f, -1f, 85f, 0f, 15f, 100f)
        addTemplate("Y", 10f, 0f, 50f, 50f, 90f, 0f, -1f, -1f, 50f, 50f, 50f, 100f)
        addTemplate("Z", 15f, 0f, 85f, 0f, 15f, 100f, 85f, 100f)

        // Lowercase
        addTemplate("a", 75f, 30f, 35f, 30f, 20f, 60f, 35f, 90f, 75f, 90f, 75f, 30f, 75f, 100f)
        addTemplate("b", 20f, 0f, 20f, 100f, 65f, 100f, 75f, 65f, 65f, 35f, 20f, 35f)
        addTemplate("c", 75f, 35f, 35f, 35f, 20f, 65f, 35f, 95f, 75f, 95f)
        addTemplate("d", 75f, 0f, 75f, 100f, 35f, 100f, 20f, 65f, 35f, 35f, 75f, 35f)
        addTemplate("e", 20f, 65f, 75f, 65f, 75f, 35f, 35f, 35f, 20f, 65f, 35f, 95f, 75f, 95f)
        addTemplate("f", 75f, 10f, 50f, 0f, 35f, 15f, 35f, 100f, -1f, -1f, 15f, 40f, 65f, 40f)
        addTemplate("g", 75f, 35f, 35f, 35f, 20f, 65f, 35f, 95f, 75f, 95f, 75f, 35f, 75f, 130f, 35f, 140f, 20f, 120f)
        addTemplate("h", 20f, 0f, 20f, 100f, -1f, -1f, 20f, 45f, 65f, 35f, 75f, 60f, 75f, 100f)
        addTemplate("i", 50f, 35f, 50f, 100f, -1f, -1f, 50f, 10f, 50f, 15f)
        addTemplate("j", 65f, 35f, 65f, 120f, 45f, 140f, 25f, 125f, -1f, -1f, 65f, 10f, 65f, 15f)
        addTemplate("k", 20f, 0f, 20f, 100f, -1f, -1f, 70f, 35f, 20f, 65f, 70f, 100f)
        addTemplate("l", 50f, 0f, 50f, 95f, 65f, 100f)
        addTemplate("m", 15f, 100f, 15f, 35f, 45f, 35f, 45f, 100f, 45f, 35f, 80f, 35f, 80f, 100f)
        addTemplate("n", 20f, 100f, 20f, 35f, 65f, 35f, 75f, 60f, 75f, 100f)
        addTemplate("o", 50f, 35f, 75f, 50f, 75f, 80f, 50f, 95f, 25f, 80f, 25f, 50f, 50f, 35f)
        addTemplate("p", 25f, 35f, 25f, 135f, -1f, -1f, 25f, 35f, 70f, 35f, 75f, 65f, 70f, 95f, 25f, 95f)
        addTemplate("q", 70f, 35f, 25f, 35f, 20f, 65f, 25f, 95f, 70f, 95f, 70f, 35f, 70f, 135f)
        addTemplate("r", 25f, 100f, 25f, 35f, 50f, 35f, 70f, 45f)
        addTemplate("s", 70f, 45f, 50f, 35f, 30f, 50f, 70f, 75f, 50f, 95f, 25f, 85f)
        addTemplate("t", 45f, 10f, 45f, 90f, 60f, 95f, -1f, -1f, 20f, 35f, 70f, 35f)
        addTemplate("u", 25f, 35f, 25f, 85f, 50f, 95f, 70f, 85f, 70f, 35f, 70f, 100f)
        addTemplate("v", 20f, 35f, 50f, 100f, 80f, 35f)
        addTemplate("w", 15f, 35f, 30f, 100f, 50f, 60f, 70f, 100f, 85f, 35f)
        addTemplate("x", 25f, 35f, 75f, 95f, -1f, -1f, 75f, 35f, 25f, 95f)
        addTemplate("y", 20f, 35f, 50f, 85f, 80f, 35f, -1f, -1f, 80f, 35f, 30f, 135f)
        addTemplate("z", 25f, 35f, 75f, 35f, 25f, 95f, 75f, 95f)

        // Digits
        addTemplate("0", 50f, 0f, 80f, 25f, 80f, 75f, 50f, 100f, 20f, 75f, 20f, 25f, 50f, 0f)
        addTemplate("1", 35f, 20f, 50f, 0f, 50f, 100f)
        addTemplate("2", 20f, 25f, 50f, 0f, 80f, 25f, 80f, 45f, 20f, 100f, 85f, 100f)
        addTemplate("3", 20f, 15f, 70f, 0f, 70f, 45f, 45f, 45f, 75f, 55f, 75f, 85f, 50f, 100f, 20f, 85f)
        addTemplate("4", 70f, 100f, 70f, 0f, 15f, 65f, 85f, 65f)
        addTemplate("5", 75f, 0f, 25f, 0f, 25f, 45f, 75f, 45f, 80f, 75f, 50f, 100f, 20f, 85f)
        addTemplate("6", 70f, 15f, 45f, 0f, 20f, 35f, 20f, 80f, 50f, 100f, 80f, 80f, 80f, 55f, 50f, 45f, 20f, 65f)
        addTemplate("7", 15f, 0f, 85f, 0f, 45f, 100f)
        addTemplate("8", 50f, 50f, 25f, 25f, 50f, 0f, 75f, 25f, 50f, 50f, 20f, 75f, 50f, 100f, 80f, 75f, 50f, 50f)
        addTemplate("9", 50f, 55f, 20f, 45f, 20f, 20f, 50f, 0f, 80f, 20f, 80f, 80f, 55f, 100f, 25f, 90f)

        // Punctuation
        addTemplate(".", 50f, 90f, 50f, 95f)
        addTemplate(",", 50f, 80f, 50f, 90f, 40f, 100f)
        addTemplate("?", 25f, 25f, 50f, 0f, 75f, 25f, 60f, 50f, 50f, 65f, -1f, -1f, 50f, 90f, 50f, 95f)
        addTemplate("!", 50f, 0f, 50f, 70f, -1f, -1f, 50f, 90f, 50f, 95f)
        addTemplate("-", 20f, 50f, 80f, 50f)
        addTemplate("/", 20f, 100f, 80f, 0f)
        addTemplate("@", 70f, 60f, 50f, 40f, 35f, 60f, 50f, 80f, 65f, 60f, 65f, 25f, 40f, 15f, 15f, 40f, 15f, 80f, 40f, 95f, 80f, 90f)
    }
}
