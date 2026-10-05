package com.example.pocketskanner.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlin.math.roundToInt

object DocumentImporter {

    fun isPdf(context: Context, uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        val value = uri.toString().lowercase()
        return mime == "application/pdf" || value.endsWith(".pdf")
    }

    fun import(context: Context, uri: Uri): List<Bitmap> {
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        val value = uri.toString().lowercase()

        return when {
            mime == "application/pdf" || value.endsWith(".pdf") -> importPdf(context, uri)
            mime.startsWith("image/") ||
                    mime == "image/jpeg" ||
                    mime == "image/jpg" ||
                    mime == "image/png" ||
                    value.endsWith(".jpg") ||
                    value.endsWith(".jpeg") ||
                    value.endsWith(".png") -> importImage(context, uri)
            mime == "application/msword" || value.endsWith(".doc") -> WordProcessor.convert(context, uri)
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                    value.endsWith(".docx") ||
                    value.contains("openxmlformats-officedocument") -> WordProcessor.convert(context, uri)
            else -> WordProcessor.convert(context, uri)
        }
    }

    fun importPdfAtStampResolution(
        context: Context,
        uri: Uri,
        stampPixelsPerMm: Float,
        targetPageIndex: Int = Int.MAX_VALUE
    ): List<Bitmap> {
        if (stampPixelsPerMm <= 0f) return importPdf(context, uri)

        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return descriptor.use {
            try {
                PdfRenderer(it).use { renderer ->
                    val result = mutableListOf<Bitmap>()
                    val target = if (targetPageIndex == Int.MAX_VALUE) renderer.pageCount - 1
                    else targetPageIndex.coerceIn(0, renderer.pageCount - 1)

                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val width: Int
                            val height: Int
                            if (index == target) {
                                val physicalWidthMm = 210f
                                val physicalHeightMm = physicalWidthMm * page.height.toFloat() / page.width.toFloat()
                                width = (physicalWidthMm * stampPixelsPerMm).roundToInt().coerceAtLeast(1)
                                height = (physicalHeightMm * stampPixelsPerMm).roundToInt().coerceAtLeast(1)
                            } else {
                                width = 1240
                                height = (width.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                            }

                            // Keep the working bitmap bounded enough for Android while retaining
                            // the requested stamp pixel density on the page being edited.
                            val safeWidth = width.coerceAtMost(7000)
                            val safeHeight = height.coerceAtMost(10000)
                            val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result += bitmap
                        }
                    }
                    result
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    private fun importImage(context: Context, uri: Uri): List<Bitmap> {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        return if (bitmap != null) listOf(bitmap) else emptyList()
    }

    private fun importPdf(context: Context, uri: Uri): List<Bitmap> {
        val descriptor: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return descriptor.use {
            try {
                PdfRenderer(it).use { renderer ->
                    val result = mutableListOf<Bitmap>()
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val width = 1240
                            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result += bitmap
                        }
                    }
                    result
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
