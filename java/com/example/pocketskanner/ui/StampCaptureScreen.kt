package com.example.pocketskanner.ui

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.pocketskanner.camera.CameraScanner
import com.example.pocketskanner.image.ImageProcessor
import com.example.pocketskanner.model.StampType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.sqrt

@Composable
fun StampCaptureScreen(
    onBack: () -> Unit,
    onSave: (bitmap: Bitmap, name: String, widthMm: Float, type: StampType) -> Unit
) {
    var stage by remember { mutableIntStateOf(1) }
        var stampBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var referencePhoto by remember { mutableStateOf<Bitmap?>(null) }
    var pageCorners by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var objectCorners by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var objectBounds by remember { mutableStateOf<RectF?>(null) }
    var widthMm by remember { mutableFloatStateOf(40f) }
    var heightMm by remember { mutableFloatStateOf(40f) }
    var showNameDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(TextFieldValue("")) }
    val nameFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var busy by remember { mutableStateOf(false) }
    var temporaryStampFile by remember { mutableStateOf<File?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val objectName = "печать"

    when (stage) {
        1 -> {
            Box(Modifier.fillMaxSize()) {
                CameraScanner(
                    onBack = onBack,
                    hint = "1 этап. Снимите печать/подпись крупным планом",
                    onImageCaptured = { bitmap ->
                        busy = true
                        scope.launch {
                            val processed = withContext(Dispatchers.Default) {
                                val withoutBackground = ImageProcessor.removeWhiteBackground(bitmap)
                                val cropped = ImageProcessor.cropTransparentArea(withoutBackground)
                                ImageProcessor.ensureStampOpacity(cropped, 179)
                            }

                            val tempFile = File(
                                context.cacheDir,
                                "pocketskanner_stamp_temp_${System.currentTimeMillis()}.png"
                            )
                            withContext(Dispatchers.IO) {
                                FileOutputStream(tempFile).use { output ->
                                    processed.compress(Bitmap.CompressFormat.PNG, 100, output)
                                }
                            }
                            temporaryStampFile?.delete()
                            temporaryStampFile = tempFile

                            stampBitmap = processed
                            busy = false
                            stage = 2
                        }
                    }
                )

                if (busy) BusyOverlay()
            }
        }

        2 -> {
            Box(Modifier.fillMaxSize()) {
                CameraScanner(
                    onBack = { stage = 1 },
                    hint = "2 этап. Снимите печать на чистом листе А4",
                    onImageCaptured = { bitmap ->
                        busy = true
                        scope.launch {
                            val detected = withContext(Dispatchers.Default) {
                                val red = com.example.pocketskanner.image.PerspectiveProcessor.detectCorners(bitmap)
                                val green = com.example.pocketskanner.image.PerspectiveProcessor.detectBlueObjectInsidePage(bitmap, red)
                                Triple(red, green, null)
                            }

                            referencePhoto = bitmap
                            pageCorners = detected.first
                            objectCorners = detected.second ?: defaultObjectCorners()
                            objectBounds = detected.third
                            busy = false
                            stage = 3
                        }
                    }
                )
                if (busy) BusyOverlay()
            }
        }

        3 -> {
            val photo = referencePhoto
            if (photo == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Нет фотографии А4")
                }
            } else {
                StampReferenceEditorScreen(
                    bitmap = photo,
                    initialPageCorners = pageCorners,
                    initialObjectCorners = objectCorners,
                    onBack = { stage = 2 },
                    onDone = { red, green ->
                        val redWidth = averageHorizontalLength(red, photo)
                        val redHeight = averageVerticalLength(red, photo)
                        val greenWidth = averageHorizontalLength(green, photo)
                        val greenHeight = averageVerticalLength(green, photo)

                        if (redWidth > 0f && redHeight > 0f && greenWidth > 0f && greenHeight > 0f) {
                            widthMm = (greenWidth / redWidth * 210f).coerceIn(0.1f, 210f)
                            heightMm = (greenHeight / redHeight * 297f).coerceIn(0.1f, 297f)
                            pageCorners = red
                            objectCorners = green
                            objectBounds = cornersToBounds(green, photo)
                            stage = 4
                        }
                    }
                )
            }
        }

        4 -> {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Проверка $objectName",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.height(10.dp))

                    val photo = referencePhoto
                    if (photo != null) {
                        ReferencePagePreview(
                            page = photo,
                            pageCorners = pageCorners,
                            objectCorners = objectCorners,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(330.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Размер печати: ${String.format(Locale.US, "%.1f × %.1f", widthMm, heightMm)} мм"
                    )
                    Spacer(Modifier.height(8.dp))
                    stampBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentScale = ContentScale.Fit
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(onClick = { stage = 3 }) {
                            Text("Изменить рамки")
                        }
                        Button(
                            enabled = stampBitmap != null,
                            onClick = { showNameDialog = true }
                        ) {
                            Text("Сохранить")
                        }
                    }

                    TextButton(onClick = { stage = 1 }) {
                        Text("Снять заново")
                    }
                }
            }
        }
    }

    LaunchedEffect(showNameDialog) {
        if (showNameDialog) {
            name = TextFieldValue("")
            nameFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Название $objectName") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.focusRequester(nameFocusRequester)
                )
            },
            confirmButton = {
                Button(
                    enabled = name.text.trim().isNotEmpty() && stampBitmap != null,
                    onClick = {
                        val rawName = name.text.trim()
                        if (rawName.isNotEmpty()) {
                            onSave(
                                stampBitmap!!,
                                rawName,
                                widthMm,
                                StampType.STAMP
                            )
                        }
                        temporaryStampFile?.delete()
                        temporaryStampFile = null
                        showNameDialog = false
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun BusyOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

private fun rectToNormalizedCorners(rect: RectF?, bitmap: Bitmap): List<PointF>? {
    if (rect == null || bitmap.width <= 0 || bitmap.height <= 0) return null
    return listOf(
        PointF(rect.left / bitmap.width, rect.top / bitmap.height),
        PointF(rect.right / bitmap.width, rect.top / bitmap.height),
        PointF(rect.right / bitmap.width, rect.bottom / bitmap.height),
        PointF(rect.left / bitmap.width, rect.bottom / bitmap.height)
    )
}

private fun defaultObjectCorners(): List<PointF> = listOf(
    PointF(.35f, .35f),
    PointF(.65f, .35f),
    PointF(.65f, .65f),
    PointF(.35f, .65f)
)

private fun distance(a: PointF, b: PointF): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

private fun averageHorizontalLength(corners: List<PointF>, bitmap: Bitmap): Float {
    if (corners.size != 4) return 0f
    val p = corners.map { PointF(it.x * bitmap.width, it.y * bitmap.height) }
    return (distance(p[0], p[1]) + distance(p[3], p[2])) / 2f
}

private fun averageVerticalLength(corners: List<PointF>, bitmap: Bitmap): Float {
    if (corners.size != 4) return 0f
    val p = corners.map { PointF(it.x * bitmap.width, it.y * bitmap.height) }
    return (distance(p[0], p[3]) + distance(p[1], p[2])) / 2f
}

private fun cornersToBounds(corners: List<PointF>, bitmap: Bitmap): RectF? {
    if (corners.size != 4) return null
    return RectF(
        corners.minOf { it.x } * bitmap.width,
        corners.minOf { it.y } * bitmap.height,
        corners.maxOf { it.x } * bitmap.width,
        corners.maxOf { it.y } * bitmap.height
    )
}

@Composable
private fun ReferencePagePreview(
    page: Bitmap,
    pageCorners: List<PointF>,
    objectCorners: List<PointF>,
    modifier: Modifier = Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            bitmap = page.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        Canvas(Modifier.fillMaxSize()) {
            val scale = minOf(
                size.width / page.width.toFloat(),
                size.height / page.height.toFloat()
            )
            val shownWidth = page.width * scale
            val shownHeight = page.height * scale
            val leftOffset = (size.width - shownWidth) / 2f
            val topOffset = (size.height - shownHeight) / 2f

            fun drawCorners(corners: List<PointF>, color: Color) {
                if (corners.size != 4) return
                val points = corners.map {
                    androidx.compose.ui.geometry.Offset(
                        leftOffset + it.x * shownWidth,
                        topOffset + it.y * shownHeight
                    )
                }
                for (i in points.indices) {
                    drawLine(
                        color,
                        points[i],
                        points[(i + 1) % points.size],
                        strokeWidth = 5f
                    )
                }
            }

            drawCorners(pageCorners, Color.Red)
            drawCorners(objectCorners, Color.Green)
        }
    }
}
