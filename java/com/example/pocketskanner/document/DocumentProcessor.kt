package com.example.pocketskanner.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.example.pocketskanner.model.DocumentPage
import com.example.pocketskanner.model.PageOverlay
import com.example.pocketskanner.model.StampItem
import java.io.File

object DocumentProcessor {
    private const val A4_WIDTH_MM = 210f
    private const val A4_HEIGHT_MM = 297f

    fun addPage(pages: List<DocumentPage>, bitmap: Bitmap): List<DocumentPage> = pages + DocumentPage(System.currentTimeMillis(), bitmap)

    fun applyOverlay(page: DocumentPage, stamp: StampItem, x: Float = .5f, y: Float = .75f, scale: Float = 1f): DocumentPage =
        page.copy(overlays = page.overlays + PageOverlay(System.nanoTime(), stamp.id, x, y, scale))

    fun renderPage(page: DocumentPage, stamps: List<StampItem>): Bitmap? {
        val source = page.bitmap ?: return null
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        page.overlays.forEach { overlay ->
            stamps.find { it.id == overlay.stampId }?.let { stamp ->
                drawStamp(
                    canvas,
                    stamp,
                    result.width,
                    result.height,
                    overlay,
                    edgeOpacity = if (page.pdfWidthPoints != null) 0.35f else null
                )
            }
        }
        return result
    }

    private fun drawStamp(
        canvas: Canvas,
        stamp: StampItem,
        width: Int,
        height: Int,
        overlay: PageOverlay,
        edgeOpacity: Float? = null
    ) {
        val file = File(stamp.imagePath)
        if (!file.exists()) return
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return
        val pageWidthMm = if (width > 0 && height > 0 && width.toFloat() / height.toFloat() < .78f) A4_WIDTH_MM else A4_HEIGHT_MM
        val pxPerMm = width / pageWidthMm
        val targetWidth = (stamp.widthMm * pxPerMm * overlay.scale).toInt().coerceAtLeast(1)
        val targetHeight = (targetWidth.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
        val centerX = (width * overlay.x).toInt(); val centerY = (height * overlay.y).toInt()
        val destination = Rect(centerX - targetWidth/2, centerY-targetHeight/2, centerX+targetWidth/2, centerY+targetHeight/2)
        val drawBitmap = if (edgeOpacity != null) capEdgeOpacity(bitmap, edgeOpacity) else bitmap
        canvas.save(); canvas.rotate(overlay.rotation, centerX.toFloat(), centerY.toFloat())
        canvas.drawBitmap(drawBitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        if (drawBitmap !== bitmap) drawBitmap.recycle()
        bitmap.recycle()
    }


    /** Caps opacity of contour pixels only. Interior pixels keep their original opacity. */
    private fun capEdgeOpacity(source: Bitmap, opacity: Float): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val maxAlpha = (opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        fun alphaAt(x: Int, y: Int): Int = (pixels[y * w + x] ushr 24) and 0xFF

        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                val color = pixels[index]
                val alpha = (color ushr 24) and 0xFF
                if (alpha == 0) continue

                var edge = false
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h || alphaAt(nx, ny) == 0) {
                            edge = true
                        }
                    }
                }

                if (edge) {
                    val newAlpha = minOf(alpha, maxAlpha)
                    pixels[index] = (color and 0x00FFFFFF) or (newAlpha shl 24)
                }
            }
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    fun pageWithOverlay(page: DocumentPage, stamp: StampItem, x: Float, y: Float, scale: Float): DocumentPage =
        page.copy(overlays = listOf(PageOverlay(System.nanoTime(), stamp.id, x, y, scale)))
}
