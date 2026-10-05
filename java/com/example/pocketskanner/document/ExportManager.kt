package com.example.pocketskanner.document

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.pocketskanner.model.DocumentPage
import java.io.File
import java.io.FileOutputStream

object ExportManager {
    private fun baseName(input: String): String {
        val clean = input.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val withoutSuffix = clean.removeSuffix(".pdf").removeSuffix(".png")
        return if (withoutSuffix.isBlank()) "PocketSkanner" else withoutSuffix
    }
    private fun withPrefix(input: String): String {
        val b = baseName(input)
        return if (b.startsWith("PocketSkanner_")) b else "PocketSkanner_$b"
    }

    fun savePdf(context: Context, pages: List<DocumentPage>, requestedName: String): Uri? {
        if (pages.isEmpty()) return null
        val pdf = PdfDocument()
        return try {
            var pageNumber = 1
            pages.forEach { page ->
                val bitmap = page.bitmap ?: return@forEach
                val pageWidth = page.pdfWidthPoints ?: bitmap.width
                val pageHeight = page.pdfHeightPoints ?: bitmap.height
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create()
                val pdfPage = pdf.startPage(info)
                val destination = android.graphics.Rect(0, 0, pageWidth, pageHeight)
                pdfPage.canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(pdfPage)
            }
            val file = File(context.cacheDir, "${withPrefix(requestedName)}.pdf")
            FileOutputStream(file).use { pdf.writeTo(it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } finally { pdf.close() }
    }

    fun savePng(context: Context, pages: List<DocumentPage>, requestedName: String): List<Uri> {
        val result = mutableListOf<Uri>()
        pages.forEachIndexed { index, page ->
            val bitmap = page.bitmap ?: return@forEachIndexed
            val file = File(context.cacheDir, "${withPrefix(requestedName)}_${index + 1}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            result += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        return result
    }

    fun savePdfToDevice(context: Context, pages: List<DocumentPage>, requestedName: String): Boolean {
        if (pages.isEmpty()) return false
        val pdf = PdfDocument()
        return try {
            var pageNumber = 1
            pages.forEach { page ->
                val bitmap = page.bitmap ?: return@forEach
                val pageWidth = page.pdfWidthPoints ?: bitmap.width
                val pageHeight = page.pdfHeightPoints ?: bitmap.height
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create()
                val pdfPage = pdf.startPage(info)
                val destination = android.graphics.Rect(0, 0, pageWidth, pageHeight)
                pdfPage.canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(pdfPage)
            }
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "${withPrefix(requestedName)}.pdf")
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/PocketSkanner")
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return false
            try {
                context.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) } ?: return false
                values.clear(); values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null); true
            } catch (_: Exception) { context.contentResolver.delete(uri, null, null); false }
        } catch (_: Exception) { false } finally { pdf.close() }
    }

    fun savePngToDevice(context: Context, pages: List<DocumentPage>, requestedName: String): Boolean {
        if (pages.isEmpty()) return false
        var saved = 0
        pages.forEachIndexed { index, page ->
            val bitmap = page.bitmap ?: return@forEachIndexed
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${withPrefix(requestedName)}_${index + 1}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocketSkanner")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@forEachIndexed
            try {
                val ok = context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
                if (ok) { values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); context.contentResolver.update(uri, values, null, null); saved++ }
                else context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) { context.contentResolver.delete(uri, null, null) }
        }
        return saved > 0
    }

    fun share(context: Context, uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = mimeType; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(intent, "Отправить"))
    }
    fun shareMultiple(context: Context, uris: List<Uri>, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = mimeType; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(intent, "Отправить"))
    }
}
