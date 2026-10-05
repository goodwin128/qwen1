package com.example.pocketskanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.pocketskanner.model.DocumentPage
import com.example.pocketskanner.model.StampItem
import kotlin.math.roundToInt
import kotlin.math.min

@Composable
fun StampPlacementScreen(
    pages: List<DocumentPage>,
    stamp: StampItem,
    initialPage: Int,
    stampBitmap: Bitmap,
    onBack: () -> Unit,
    onConfirm: (pageIndex: Int, x: Float, y: Float, scale: Float) -> Unit
) {
    var pageIndex by remember { mutableIntStateOf(initialPage.coerceIn(0, maxOf(0, pages.lastIndex))) }
    var x by remember { mutableFloatStateOf(.5f) }
    var y by remember { mutableFloatStateOf(.65f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var showPageDialog by remember { mutableStateOf(false) }
    var pageText by remember { mutableStateOf((pageIndex + 1).toString()) }

    val page = pages.getOrNull(pageIndex)
    if (page == null || page.bitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Нет страницы") }
        return
    }

    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val pageBitmap = page.bitmap

        val fitScale = min(
            boxWidth / pageBitmap.width.toFloat(),
            boxHeight / pageBitmap.height.toFloat()
        )
        val shownWidth = pageBitmap.width * fitScale
        val shownHeight = pageBitmap.height * fitScale
        val imageLeft = (boxWidth - shownWidth) / 2f
        val imageTop = (boxHeight - shownHeight) / 2f

        val stampWidth = (shownWidth * (stamp.widthMm / 210f) * scale).coerceAtLeast(8f)
        val stampHeight = (stampWidth * stampBitmap.height / stampBitmap.width.toFloat()).coerceAtLeast(8f)

        val minX = (stampWidth / 2f / shownWidth).coerceIn(0f, .5f)
        val maxX = (1f - stampWidth / 2f / shownWidth).coerceIn(.5f, 1f)
        val placementShift = 0.07f
        val minY = ((stampHeight / 2f / shownHeight) - placementShift).coerceIn(0f, .5f)
        val maxY = ((1f - stampHeight / 2f / shownHeight) - placementShift).coerceIn(.5f, 1f)
        val displayY = (y + placementShift).coerceIn(stampHeight / 2f / shownHeight, 1f - stampHeight / 2f / shownHeight)

        Box(Modifier.fillMaxSize()) {
            Image(
                bitmap = pageBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    image = stampBitmap.asImageBitmap(),
                    dstOffset = IntOffset(
                        (imageLeft + x * shownWidth - stampWidth / 2f).roundToInt(),
                        (imageTop + displayY * shownHeight - stampHeight / 2f).roundToInt()
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        stampWidth.roundToInt(),
                        stampHeight.roundToInt()
                    )
                )
            }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (imageLeft + x * shownWidth - stampWidth / 2f).roundToInt(),
                            (imageTop + displayY * shownHeight - stampHeight / 2f).roundToInt()
                        )
                    }
                    .size(
                        with(density) { stampWidth.toDp() },
                        with(density) { stampHeight.toDp() }
                    )
                    .pointerInput(pageIndex, stamp.id, shownWidth, shownHeight) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            x = (x + drag.x / shownWidth).coerceIn(minX, maxX)
                            y = (y + drag.y / shownHeight).coerceIn(minY, maxY)
                        }
                    }
            )

            Button(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) { Text("‹  Назад") }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { pageText = (pageIndex + 1).toString(); showPageDialog = true }) {
                    Text("Выбор страницы")
                }
                Button(onClick = { onConfirm(pageIndex, x, displayY, scale) }) { Text("ОК") }
            }
        }
    }

    if (showPageDialog) {
        AlertDialog(
            onDismissRequest = { showPageDialog = false },
            title = { Text("Номер страницы") },
            text = {
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter(Char::isDigit) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val requested = pageText.toIntOrNull()?.minus(1)
                    if (requested != null) pageIndex = requested.coerceIn(0, pages.lastIndex)
                    showPageDialog = false
                }) { Text("ОК") }
            },
            dismissButton = { Button(onClick = { showPageDialog = false }) { Text("Отмена") } }
        )
    }
}
