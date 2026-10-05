package com.example.pocketskanner.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

object ImageProcessor {
    fun applyScanFilter(source: Bitmap): Bitmap = applyFinalScanCorrection(source, applyDarkLift = false)

    /**
     * Final scan processing. This function must receive the bitmap only after
     * perspective correction. Resolution is reduced first, then all tonal
     * corrections are applied in the required order.
     *
     * Order: perspective (caller) -> resize to max 1200 -> brightness ->
     * contrast -> exposure -> gamma.
     */
    fun finalizeScanAfterPerspective(source: Bitmap): Bitmap {
        val resized = resizeIfNeeded(source, 2500, 1875)
        val darkFrame = isDarkFrame(resized)
        val corrected = applyFinalScanCorrection(resized, darkFrame)
        if (resized !== source) resized.recycle()
        return corrected
    }

    fun applyFinalScanCorrection(source: Bitmap, applyDarkLift: Boolean): Bitmap {
        // Order is intentional: brightness -> contrast -> exposure -> gamma.
        val brightened = applyBrightness(source, applyDarkLift)
        val contrasted = applyContrast(brightened, 1.35f)
        val exposed = applyExposureGamma(contrasted, exposureEv = 0.105, gamma = 1.7f)
        val result = applySaturation(exposed, 0.75f)
        if (result !== exposed) exposed.recycle()
        if (contrasted !== source) contrasted.recycle()
        if (brightened !== contrasted && brightened !== source) brightened.recycle()
        return result
    }

    fun applyBrightness(source: Bitmap, liftDarkFrame: Boolean): Bitmap {
        if (!liftDarkFrame) return source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val input = IntArray(source.width * source.height)
        val output = IntArray(input.size)
        source.getPixels(input, 0, source.width, 0, 0, source.width, source.height)
        for (i in input.indices) {
            val c = input[i]
            fun lift(v: Int): Int = (v * 1.04f).coerceAtMost(255f).toInt()
            output[i] = Color.argb(Color.alpha(c), lift(Color.red(c)), lift(Color.green(c)), lift(Color.blue(c)))
        }
        result.setPixels(output, 0, source.width, 0, 0, source.width, source.height)
        return result
    }

    fun applyContrast(source: Bitmap, contrast: Float): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val matrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(result).drawBitmap(source, 0f, 0f, paint)
        return result
    }

    fun applySaturation(source: Bitmap, saturation: Float = 0.80f): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(result).drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /** Applies exposure +0.12 EV and gamma 1.8 after brightness and contrast. */
    fun applyExposureGamma(
        source: Bitmap,
        applyDarkLift: Boolean = false,
        exposureEv: Double = 0.12,
        gamma: Float = 1.8f
    ): Bitmap {
        val base = if (applyDarkLift) applyBrightness(source, true) else source
        val result = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val input = IntArray(base.width * base.height)
        val output = IntArray(input.size)
        base.getPixels(input, 0, base.width, 0, 0, base.width, base.height)

        val exposureMultiplier = 2.0.pow(exposureEv).toFloat()
        for (i in input.indices) {
            val c = input[i]
            fun correct(v: Int): Int {
                val x = ((v / 255f) * exposureMultiplier).coerceAtMost(1f).pow(gamma)
                return (x * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            output[i] = Color.argb(
                Color.alpha(c),
                correct(Color.red(c)),
                correct(Color.green(c)),
                correct(Color.blue(c))
            )
        }
        result.setPixels(output, 0, base.width, 0, 0, base.width, base.height)
        if (base !== source) base.recycle()
        return result
    }

    fun applyScanSimulation(source: Bitmap, brightness: Float = 8f, contrast: Float = 1.22f): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val translate = (-0.5f * contrast + 0.5f) * 255f + brightness
        val matrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    fun addScanArtifacts(source: Bitmap, seed: Long = System.nanoTime()): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val random = Random(seed)
        val count = ((source.width * source.height) / 50000).coerceIn(12, 120)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(135, 70, 70, 70) }
        repeat(count) {
            val x = random.nextInt(source.width).toFloat()
            val y = random.nextInt(source.height).toFloat()
            val radius = random.nextDouble(0.45, 2.0).toFloat()
            canvas.drawCircle(x, y, radius, paint)
        }
        return result
    }

    fun rotateForScan(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun simulateScan(source: Bitmap, seed: Long = System.nanoTime(), maxRotationDegrees: Float = 1f): Bitmap {
        val angle = Random(seed).nextFloat() * maxRotationDegrees * 2f - maxRotationDegrees
        val rotated = rotateForScan(source, angle)
        val adjusted = applyScanSimulation(rotated, 6.3f, 1.20f)
        return addScanArtifacts(adjusted, seed + 1)
    }

    fun applyBrightnessContrast(source: Bitmap): Bitmap = applyFinalScanCorrection(source, applyDarkLift = false)

    fun isDarkFrame(source: Bitmap): Boolean {
        val stepX = max(1, source.width / 24)
        val stepY = max(1, source.height / 24)
        var sum = 0L
        var count = 0
        var bright = 0
        var dark = 0
        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val c = source.getPixel(x, y)
                val luminance = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
                sum += luminance
                count++
                if (luminance >= 205) bright++
                if (luminance < 90) dark++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false
        val average = sum.toFloat() / count
        val brightRatio = bright.toFloat() / count
        val darkRatio = dark.toFloat() / count
        return average < 135f && brightRatio < 0.45f && darkRatio < 0.65f
    }

    fun findObjectBoundsOnPage(source: Bitmap): android.graphics.RectF? {
        val maxDimension = 1100f
        val scale = min(1f, maxDimension / max(source.width, source.height).toFloat())
        val sw = max(1, (source.width * scale).toInt())
        val sh = max(1, (source.height * scale).toInt())
        val small = if (sw == source.width && sh == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, sw, sh, true)
        }

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        val xHistogram = IntArray(sw)
        val yHistogram = IntArray(sh)
        var selected = 0

        val marginX = (sw * .045f).toInt().coerceAtLeast(1)
        val marginY = (sh * .045f).toInt().coerceAtLeast(1)

        for (y in marginY until sh - marginY) {
            for (x in marginX until sw - marginX) {
                val c = pixels[y * sw + x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val gray = (r * 30 + g * 59 + b * 11) / 100
                val saturation = max(r, max(g, b)) - min(r, min(g, b))

                if (gray < 210 || saturation > 38) {
                    xHistogram[x]++
                    yHistogram[y]++
                    selected++
                }
            }
        }

        if (selected < (sw * sh * .0005f).toInt()) return null

        fun percentileStart(hist: IntArray, total: Int, fraction: Float): Int {
            val target = (total * fraction).toInt().coerceAtLeast(1)
            var sum = 0
            for (i in hist.indices) {
                sum += hist[i]
                if (sum >= target) return i
            }
            return 0
        }

        fun percentileEnd(hist: IntArray, total: Int, fraction: Float): Int {
            val target = (total * fraction).toInt().coerceAtLeast(1)
            var sum = 0
            for (i in hist.indices.reversed()) {
                sum += hist[i]
                if (sum >= target) return i
            }
            return hist.lastIndex
        }

        val left = percentileStart(xHistogram, selected, .01f)
        val right = percentileEnd(xHistogram, selected, .01f)
        val top = percentileStart(yHistogram, selected, .01f)
        val bottom = percentileEnd(yHistogram, selected, .01f)

        if (right <= left || bottom <= top) return null

        val area = (right - left).toLong() * (bottom - top).toLong()
        val pageArea = sw.toLong() * sh.toLong()
        if (area > (pageArea * .45f).toLong()) return null

        val margin = min(sw, sh) * .01f
        if (left <= margin || top <= margin || right >= sw - margin || bottom >= sh - margin) {
            return null
        }

        return android.graphics.RectF(
            left / scale,
            top / scale,
            right / scale,
            bottom / scale
        )
    }


    fun findBlueObjectBounds(source: Bitmap): android.graphics.RectF? {
        val maxDimension = 1400f
        val scale = min(1f, maxDimension / max(source.width, source.height).toFloat())
        val sw = max(1, (source.width * scale).toInt())
        val sh = max(1, (source.height * scale).toInt())
        val small = if (sw == source.width && sh == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, sw, sh, true)
        }

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        val xHistogram = IntArray(sw)
        val yHistogram = IntArray(sh)
        var selected = 0

        val marginX = (sw * .03f).toInt().coerceAtLeast(1)
        val marginY = (sh * .03f).toInt().coerceAtLeast(1)

        for (y in marginY until sh - marginY) {
            for (x in marginX until sw - marginX) {
                val c = pixels[y * sw + x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val maxRgb = max(r, max(g, b))
                val minRgb = min(r, min(g, b))
                val saturation = maxRgb - minRgb

                val isBlue =
                    b >= 70 &&
                    b > r + 22 &&
                    b > g + 8 &&
                    saturation >= 28

                if (isBlue) {
                    xHistogram[x]++
                    yHistogram[y]++
                    selected++
                }
            }
        }

        if (selected < (sw * sh * .00015f).toInt().coerceAtLeast(20)) return null

        fun firstOccupied(hist: IntArray): Int {
            return hist.indexOfFirst { it > 0 }.takeIf { it >= 0 } ?: 0
        }

        fun lastOccupied(hist: IntArray): Int {
            return hist.indexOfLast { it > 0 }.takeIf { it >= 0 } ?: hist.lastIndex
        }

        val left = firstOccupied(xHistogram)
        val right = lastOccupied(xHistogram)
        val top = firstOccupied(yHistogram)
        val bottom = lastOccupied(yHistogram)

        if (right <= left || bottom <= top) return null

        val area = (right - left).toLong() * (bottom - top).toLong()
        if (area > (sw.toLong() * sh.toLong() * .35f).toLong()) return null

        return android.graphics.RectF(
            left / scale,
            top / scale,
            right / scale,
            bottom / scale
        )
    }

    fun removeScanNoise(source: Bitmap): Bitmap {
        if (source.width < 3 || source.height < 3) return source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val input = IntArray(source.width * source.height)
        val output = IntArray(input.size)
        source.getPixels(input, 0, source.width, 0, 0, source.width, source.height)
        val values = IntArray(9)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            if (x == 0 || y == 0 || x == source.width - 1 || y == source.height - 1) {
                output[y * source.width + x] = input[y * source.width + x]
                continue
            }
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val c = input[(y + dy) * source.width + x + dx]
                values[n++] = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
            }
            values.sort()
            val g = values[4]
            output[y * source.width + x] = Color.argb(Color.alpha(input[y * source.width + x]), g, g, g)
        }
        result.setPixels(output, 0, source.width, 0, 0, source.width, source.height)
        return result
    }

    fun applyBlackAndWhiteFilter(source: Bitmap): Bitmap {
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return result
    }

    fun resizeIfNeeded(source: Bitmap, maxWidth: Int = 2000, maxHeight: Int = 2800): Bitmap {
        if (source.width <= maxWidth && source.height <= maxHeight) return source
        val scale = min(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height)
        return Bitmap.createScaledBitmap(source, max(1, (source.width * scale).toInt()), max(1, (source.height * scale).toInt()), true)
    }

    fun removeWhiteBackground(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val maxRgb = max(r, max(g, b))
            val minRgb = min(r, min(g, b))
            val saturation = maxRgb - minRgb
            val isBlue = b >= 45 && b > r + 18 && b >= g + 6 && saturation >= 24

            pixels[i] = if (isBlue) {
                Color.argb(255, r, g, b)
            } else {
                Color.TRANSPARENT
            }
        }

        result.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return result
    }

    fun cropTransparentArea(source: Bitmap, alphaThreshold: Int = 8): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        var left = source.width; var top = source.height; var right = -1; var bottom = -1
        for (y in 0 until source.height) for (x in 0 until source.width) {
            if (Color.alpha(pixels[y * source.width + x]) > alphaThreshold) {
                left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
            }
        }
        if (right < left || bottom < top) return source
        return Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
    }

    fun ensureStampOpacity(source: Bitmap, opacity: Int = 179): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        for (i in pixels.indices) {
            val oldAlpha = Color.alpha(pixels[i])
            val alpha = (oldAlpha * opacity / 255f).toInt().coerceIn(0, opacity)
            pixels[i] = Color.argb(
                alpha,
                Color.red(pixels[i]),
                Color.green(pixels[i]),
                Color.blue(pixels[i])
            )
        }
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        return result
    }

    fun overlayStamp(source: Bitmap, stamp: Bitmap, centerX: Float = .5f, centerY: Float = .55f, widthFraction: Float = .30f): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val targetWidth = (source.width * widthFraction).toInt().coerceAtLeast(1)
        val targetHeight = (stamp.height.toFloat() / stamp.width * targetWidth).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(stamp, targetWidth, targetHeight, true)
        val left = (source.width * centerX - targetWidth / 2f).toInt().coerceIn(0, max(0, source.width - targetWidth))
        val top = (source.height * centerY - targetHeight / 2f).toInt().coerceIn(0, max(0, source.height - targetHeight))
        Canvas(result).drawBitmap(scaled, left.toFloat(), top.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (scaled !== stamp) scaled.recycle()
        return result
    }
}
