package com.example.pocketskanner.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object PerspectiveProcessor {
    fun detectCorners(source: Bitmap): List<PointF> {
        val scale = min(1f, 900f / max(source.width, source.height).toFloat())
        val w = max(1, (source.width * scale).toInt())
        val h = max(1, (source.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        fun gray(c: Int) = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
        val g = IntArray(pixels.size) { gray(pixels[it]) }

        for (threshold in intArrayOf(175, 155, 135, 115)) {
            val component = centerPaperComponent(g, w, h, threshold)
            if (component.size > w * h * .08f) {
                val corners = extremaCorners(component)
                if (isPlausible(corners, w, h)) {
                    return corners.map { PointF(it.x / w, it.y / h) }
                }
            }
        }

        return listOf(
            PointF(.04f, .04f), PointF(.96f, .04f),
            PointF(.96f, .96f), PointF(.04f, .96f)
        )
    }

    /**
     * Finds the blue stamp only AFTER the page corners are known.
     * The page is first perspective-corrected to a rectangular image,
     * blue pixels are searched only inside that white page, and the
     * resulting four corners are mapped back to the original photo.
     */
    fun detectBlueObjectInsidePage(
        source: Bitmap,
        pageCorners: List<PointF>
    ): List<PointF>? {
        if (pageCorners.size != 4) return null

        val rectified = correctPerspective(source, pageCorners)
        val bounds = ImageProcessor.findBlueObjectBounds(rectified) ?: return null

        val pageWidth = rectified.width.toFloat()
        val pageHeight = rectified.height.toFloat()
        val rectifiedCorners = listOf(
            PointF(bounds.left, bounds.top),
            PointF(bounds.right, bounds.top),
            PointF(bounds.right, bounds.bottom),
            PointF(bounds.left, bounds.bottom)
        )

        val src = floatArrayOf(
            0f, 0f,
            pageWidth, 0f,
            pageWidth, pageHeight,
            0f, pageHeight
        )
        val dst = floatArrayOf(
            pageCorners[0].x * source.width, pageCorners[0].y * source.height,
            pageCorners[1].x * source.width, pageCorners[1].y * source.height,
            pageCorners[2].x * source.width, pageCorners[2].y * source.height,
            pageCorners[3].x * source.width, pageCorners[3].y * source.height
        )

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null

        val values = FloatArray(9)
        matrix.getValues(values)

        fun mapPoint(p: PointF): PointF {
            val x = p.x
            val y = p.y
            val denominator = values[6] * x + values[7] * y + values[8]
            val nx = (values[0] * x + values[1] * y + values[2]) / denominator
            val ny = (values[3] * x + values[4] * y + values[5]) / denominator
            return PointF(
                (nx / source.width).coerceIn(0f, 1f),
                (ny / source.height).coerceIn(0f, 1f)
            )
        }

        return rectifiedCorners.map(::mapPoint)
    }

    private fun centerPaperComponent(g: IntArray, w: Int, h: Int, threshold: Int): List<PointF> {
        val cx = w / 2
        val cy = h / 2
        if (g[cy * w + cx] < threshold) {
            var found = false
            var fx = cx
            var fy = cy
            outer@ for (r in 0 until min(w, h) / 5 step 4) {
                for (dy in -r..r step 4) for (dx in -r..r step 4) {
                    val x = (cx + dx).coerceIn(0, w - 1)
                    val y = (cy + dy).coerceIn(0, h - 1)
                    if (g[y * w + x] >= threshold) {
                        fx = x
                        fy = y
                        found = true
                        break@outer
                    }
                }
            }
            if (!found) return emptyList()
            return flood(g, w, h, fx, fy, threshold)
        }
        return flood(g, w, h, cx, cy, threshold)
    }

    private fun flood(g: IntArray, w: Int, h: Int, sx: Int, sy: Int, threshold: Int): List<PointF> {
        val seen = BooleanArray(w * h)
        val q = ArrayDeque<Int>()
        val result = ArrayList<PointF>()
        q.add(sy * w + sx)
        seen[sy * w + sx] = true
        val dirs = intArrayOf(1, -1, w, -w)
        var visited = 0
        while (q.isNotEmpty() && visited < w * h / 2) {
            val idx = q.removeFirst()
            visited++
            val x = idx % w
            val y = idx / w
            result += PointF(x.toFloat(), y.toFloat())
            for (d in dirs) {
                val ni = idx + d
                if (ni < 0 || ni >= g.size || seen[ni]) continue
                val nx = ni % w
                val ny = ni / w
                if (abs(nx - x) + abs(ny - y) != 1) continue
                if (g[ni] >= threshold) {
                    seen[ni] = true
                    q.add(ni)
                }
            }
        }
        return result
    }

    private fun extremaCorners(points: List<PointF>): List<PointF> {
        fun pick(score: (PointF) -> Float): PointF = points.maxByOrNull(score)!!
        val tl = pick { -it.x - it.y }
        val tr = pick { it.x - it.y }
        val br = pick { it.x + it.y }
        val bl = pick { -it.x + it.y }
        return listOf(tl, tr, br, bl)
    }

    private fun isPlausible(c: List<PointF>, w: Int, h: Int): Boolean {
        if (c.size != 4) return false
        val widthTop = hypot((c[1].x - c[0].x).toDouble(), (c[1].y - c[0].y).toDouble())
        val widthBottom = hypot((c[2].x - c[3].x).toDouble(), (c[2].y - c[3].y).toDouble())
        val heightLeft = hypot((c[3].x - c[0].x).toDouble(), (c[3].y - c[0].y).toDouble())
        val heightRight = hypot((c[2].x - c[1].x).toDouble(), (c[2].y - c[1].y).toDouble())
        return widthTop > w * .30 && widthBottom > w * .30 && heightLeft > h * .30 && heightRight > h * .30
    }

    fun correctPerspective(source: Bitmap, normalizedCorners: List<PointF>): Bitmap {
        if (normalizedCorners.size != 4) return source
        val p = normalizedCorners.map { PointF(it.x * source.width, it.y * source.height) }
        val topWidth = distance(p[0], p[1])
        val bottomWidth = distance(p[3], p[2])
        val leftHeight = distance(p[0], p[3])
        val rightHeight = distance(p[1], p[2])
        val outputWidth = max(1, ((topWidth + bottomWidth) / 2f).toInt())
        val outputHeight = max(1, ((leftHeight + rightHeight) / 2f).toInt())
        val src = floatArrayOf(
            p[0].x, p[0].y, p[1].x, p[1].y,
            p[2].x, p[2].y, p[3].x, p[3].y
        )
        val dst = floatArrayOf(
            0f, 0f,
            outputWidth.toFloat(), 0f,
            outputWidth.toFloat(), outputHeight.toFloat(),
            0f, outputHeight.toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return source
        val result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return result
    }

    private fun distance(a: PointF, b: PointF): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
}
