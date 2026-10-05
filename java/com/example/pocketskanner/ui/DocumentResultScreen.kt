package com.example.pocketskanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.pocketskanner.model.DocumentPage

@Composable
fun DocumentResultScreen(
    title: String, pages: List<DocumentPage>, onBack: () -> Unit,
    onSavePdf: (String) -> Unit, onSharePdf: (String) -> Unit,
    onSavePng: (String) -> Unit, onSharePng: (String) -> Unit,
    onAddStamp: () -> Unit, onAddPage: (() -> Unit)? = null, showAddPage: Boolean = false,
    showLastPageOnly: Boolean = false
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(TextFieldValue("PocketSkanner_", TextRange("PocketSkanner_".length))) }
    val nameFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("‹  Назад") }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }

            val pagesToDisplay = if (showLastPageOnly) listOfNotNull(pages.lastOrNull()) else pages
            pagesToDisplay.forEach { page ->
                page.bitmap?.let { bitmap ->
                    ZoomableBitmap(bitmap)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { dialog = "pdf-save" }, modifier = Modifier.weight(1f)) {
                    Text("Сохранить PDF")
                }
                Button(onClick = { dialog = "pdf-share" }, modifier = Modifier.weight(1f)) {
                    Text("Отправить PDF")
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { dialog = "png-save" }, modifier = Modifier.weight(1f)) {
                    Text("Сохранить PNG")
                }
                Button(onClick = { dialog = "png-share" }, modifier = Modifier.weight(1f)) {
                    Text("Отправить PNG")
                }
            }

            Button(
                onClick = onAddStamp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("Добавить печать/подпись") }

            if (showAddPage && onAddPage != null) {
                Button(
                    onClick = onAddPage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("Добавить страницу") }
            }
        }
    }

    LaunchedEffect(dialog) {
        if (dialog != null) {
            name = TextFieldValue("PocketSkanner_", TextRange("PocketSkanner_".length))
            nameFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    if (dialog != null) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Имя файла") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { value ->
                        val prefix = "PocketSkanner_"
                        name = if (value.text.startsWith(prefix)) {
                            value
                        } else {
                            // The prefix is fixed. Backspace/delete must not recreate it
                            // by appending the old text to the prefix.
                            name
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.focusRequester(nameFocusRequester)
                )
            },
            confirmButton = {
                Button(onClick = {
                    val n = name.text.trim()
                    when (dialog) {
                        "pdf-save" -> onSavePdf(n)
                        "pdf-share" -> onSharePdf(n)
                        "png-save" -> onSavePng(n)
                        "png-share" -> onSharePng(n)
                    }
                    dialog = null
                }) { Text("ОК") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ZoomableBitmap(bitmap: Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offsetX by remember(bitmap) { mutableFloatStateOf(0f) }
    var offsetY by remember(bitmap) { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(450.dp)
            .padding(6.dp)
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportWidth = constraints.maxWidth.toFloat()
            val viewportHeight = constraints.maxHeight.toFloat()
            val baseScale = minOf(
                viewportWidth / bitmap.width.toFloat(),
                viewportHeight / bitmap.height.toFloat()
            )
            val imageWidth = bitmap.width * baseScale
            val imageHeight = bitmap.height * baseScale
            val maxPanX = ((imageWidth * scale - viewportWidth) / 2f).coerceAtLeast(0f)
            val maxPanY = ((imageHeight * scale - viewportHeight) / 2f).coerceAtLeast(0f)

            Image(
                bitmap.asImageBitmap(),
                null,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            val newMaxX = ((imageWidth * newScale - viewportWidth) / 2f).coerceAtLeast(0f)
                            val newMaxY = ((imageHeight * newScale - viewportHeight) / 2f).coerceAtLeast(0f)
                            // Move relative to the displayed viewport rather than to the
                            // source bitmap's native pixel dimensions. This keeps finger
                            // movement consistent for high-resolution scans.
                            val panScaleX = (viewportWidth / imageWidth).coerceAtLeast(1f)
                            val panScaleY = (viewportHeight / imageHeight).coerceAtLeast(1f)
                            offsetX = (offsetX + pan.x * panScaleX).coerceIn(-newMaxX, newMaxX)
                            offsetY = (offsetY + pan.y * panScaleY).coerceIn(-newMaxY, newMaxY)
                            if (newScale <= 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
    }
}
