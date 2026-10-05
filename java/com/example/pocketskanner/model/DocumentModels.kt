package com.example.pocketskanner.model

import android.graphics.Bitmap

enum class AppMode {
    HOME,
    SCAN,
    SCAN_RESULT,
    WORD_EDITOR,
    WORD_SCAN_RESULT,
    STAMP_LIBRARY,
    STAMP_CAPTURE,
    STAMP_DOCUMENT,
    STAMP_PLACEMENT
}

enum class StampType {
    SIGNATURE,
    STAMP,
    SEAL,
    COMBINATION
}

data class PageOverlay(
    val id: Long,
    val stampId: Long,
    val x: Float = 0.5f,
    val y: Float = 0.75f,
    val scale: Float = 1f,
    val rotation: Float = 0f
)

data class DocumentPage(
    val id: Long,
    val bitmap: Bitmap? = null,
    val overlays: List<PageOverlay> = emptyList(),
    val pdfWidthPoints: Int? = null,
    val pdfHeightPoints: Int? = null
)

data class StampItem(
    val id: Long,
    val name: String,
    val type: StampType,
    val imagePath: String,
    val widthMm: Float = 40f,
    val secondImagePath: String? = null,
    val secondOffsetX: Float = 0f,
    val secondOffsetY: Float = 0f,
    val secondScale: Float = 1f
)
