package com.example.pocketskanner.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.pocketskanner.camera.CameraScanner
import com.example.pocketskanner.data.StampRepository
import com.example.pocketskanner.document.DocumentImporter
import com.example.pocketskanner.document.DocumentProcessor
import com.example.pocketskanner.document.WordProcessor
import com.example.pocketskanner.image.ImageProcessor
import com.example.pocketskanner.model.AppMode
import com.example.pocketskanner.model.DocumentPage
import com.example.pocketskanner.model.StampItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PocketScannerApp(
    initialUri: Uri? = null,
    onOpenFile: () -> Unit,
    onSharePdf: (List<DocumentPage>, String) -> Unit,
    onSharePng: (List<DocumentPage>, String) -> Unit,
    onSavePdf: (List<DocumentPage>, String) -> Unit,
    onSavePng: (List<DocumentPage>, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { StampRepository(context) }
    var mode by remember(initialUri) { mutableStateOf(AppMode.HOME) }
    var pages by remember { mutableStateOf<List<DocumentPage>>(emptyList()) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var wordHtml by remember { mutableStateOf<String?>(null) }
    var stamps by remember { mutableStateOf(repository.getAll()) }
    var selectedStamp by remember { mutableStateOf<StampItem?>(null) }
    var placementReturnMode by remember { mutableStateOf(AppMode.SCAN_RESULT) }
    var placementPage by remember { mutableStateOf(0) }
    var openedPdfUri by remember(initialUri) { mutableStateOf<Uri?>(null) }

    fun goBack() {
        when (mode) {
            AppMode.HOME -> Unit
            AppMode.SCAN -> { capturedBitmap = null; mode = AppMode.HOME }
            AppMode.SCAN_RESULT, AppMode.WORD_SCAN_RESULT, AppMode.STAMP_DOCUMENT -> mode = AppMode.HOME
            AppMode.WORD_EDITOR -> { wordHtml = null; mode = AppMode.HOME }
            AppMode.STAMP_LIBRARY -> mode = if (pages.isNotEmpty()) placementReturnMode else AppMode.HOME
            AppMode.STAMP_CAPTURE -> { stamps = repository.getAll(); mode = AppMode.STAMP_LIBRARY }
            AppMode.STAMP_PLACEMENT -> mode = placementReturnMode
        }
    }
    BackHandler(enabled = mode != AppMode.HOME) { goBack() }

    LaunchedEffect(initialUri) {
        val uri = initialUri ?: return@LaunchedEffect
        loading = true
        val isWord = withContext(Dispatchers.IO) { WordProcessor.isWord(context, uri) }
        if (isWord) {
            wordHtml = withContext(Dispatchers.IO) { WordProcessor.toEditableHtml(context, uri) }
            mode = AppMode.WORD_EDITOR
        } else {
            val pdf = DocumentImporter.isPdf(context, uri)
            openedPdfUri = if (pdf) uri else null
            pages = withContext(Dispatchers.IO) { DocumentImporter.import(context, uri) }.mapIndexed { i, b ->
                DocumentPage(
                    id = System.currentTimeMillis() + i,
                    bitmap = b,
                    pdfWidthPoints = if (pdf) 595 else null,
                    pdfHeightPoints = if (pdf) 842 else null
                )
            }
            mode = AppMode.STAMP_DOCUMENT
        }
        loading = false
    }

    when (mode) {
        AppMode.HOME -> HomeScreen(
            onScan = { pages = emptyList(); capturedBitmap = null; mode = AppMode.SCAN },
            onWord = { onOpenFile() },
            onStamp = { stamps = repository.getAll(); mode = AppMode.STAMP_LIBRARY },
            onOpen = onOpenFile
        )
        AppMode.SCAN -> if (capturedBitmap == null) {
            CameraScanner(onImageCaptured = { capturedBitmap = it }, onBack = { mode = AppMode.HOME }, analyzeDarkFrame = true)
        } else {
            PerspectiveEditorScreen(bitmap = capturedBitmap!!, onBack = { capturedBitmap = null }, onDone = { corrected ->
                // The bitmap here is already perspective-corrected.
                // Only now do we reduce resolution and apply all tonal corrections.
                // Order: perspective -> resize to max 1600 -> brightness -> contrast -> exposure -> gamma.
                val correctedScan = ImageProcessor.finalizeScanAfterPerspective(corrected)
                val scanned = ImageProcessor.addScanArtifacts(correctedScan)
                pages = pages + DocumentPage(System.currentTimeMillis(), scanned)
                capturedBitmap = null; mode = AppMode.SCAN_RESULT
            })
        }
        AppMode.SCAN_RESULT -> DocumentResultScreen(
            "Скан", pages, ::goBack,
            onSavePdf = { onSavePdf(pages, it) }, onSharePdf = { onSharePdf(pages, it) },
            onSavePng = { onSavePng(pages, it) }, onSharePng = { onSharePng(pages, it) },
            onAddStamp = { placementReturnMode = AppMode.SCAN_RESULT; stamps = repository.getAll(); mode = AppMode.STAMP_LIBRARY },
            onAddPage = { capturedBitmap = null; mode = AppMode.SCAN }, showAddPage = true
        )
        AppMode.WORD_EDITOR -> WordEditorScreen(wordHtml, loading, ::goBack) { rendered ->
            pages = rendered.mapIndexed { i, b -> DocumentPage(System.currentTimeMillis() + i, ImageProcessor.simulateScan(b, System.nanoTime() + i, 1f)) }
            mode = AppMode.WORD_SCAN_RESULT
        }
        AppMode.WORD_SCAN_RESULT -> DocumentResultScreen(
            "Имитация скана", pages, ::goBack,
            onSavePdf = { onSavePdf(pages, it) }, onSharePdf = { onSharePdf(pages, it) },
            onSavePng = { onSavePng(pages, it) }, onSharePng = { onSharePng(pages, it) },
            onAddStamp = { placementReturnMode = AppMode.WORD_SCAN_RESULT; stamps = repository.getAll(); mode = AppMode.STAMP_LIBRARY }
        )
        AppMode.STAMP_DOCUMENT -> DocumentResultScreen(
            "Документ", pages, ::goBack,
            onSavePdf = { onSavePdf(pages, it) }, onSharePdf = { onSharePdf(pages, it) },
            onSavePng = { onSavePng(pages, it) }, onSharePng = { onSharePng(pages, it) },
            onAddStamp = { placementReturnMode = AppMode.STAMP_DOCUMENT; stamps = repository.getAll(); mode = AppMode.STAMP_LIBRARY }
        )
        AppMode.STAMP_LIBRARY -> StampLibraryScreen(
            stamps, ::goBack,
            onSelected = { stamp ->
                if (pages.isNotEmpty()) {
                    selectedStamp = stamp
                    placementPage = pages.lastIndex.coerceAtLeast(0)
                    val pdfUri = openedPdfUri
                    if (pdfUri != null) {
                        // The PDF is already imported as PNG pages at the normal application resolution.
                        // Keep that PNG resolution unchanged while placing the stamp.
                        mode = AppMode.STAMP_PLACEMENT
                    } else {
                        mode = AppMode.STAMP_PLACEMENT
                    }
                }
            }, onCreate = { mode = AppMode.STAMP_CAPTURE }
        )
        AppMode.STAMP_CAPTURE -> StampCaptureScreen(
            onBack = ::goBack,
            onSave = { bitmap, name, widthMm, type ->
                val item = repository.saveBitmap(bitmap, name, type, widthMm)
                repository.add(item)
                stamps = repository.getAll(); mode = AppMode.STAMP_LIBRARY
            }
        )
        AppMode.STAMP_PLACEMENT -> {
            val stamp = selectedStamp
            val stampBitmap = stamp?.let(repository::loadBitmap)
            if (stamp == null || stampBitmap == null) goBack() else StampPlacementScreen(
                pages, stamp, placementPage, stampBitmap, ::goBack,
                onConfirm = { pageIndex, x, y, scale ->
                    pages.getOrNull(pageIndex)?.let { page ->
                        val overlayPage = DocumentProcessor.applyOverlay(page, stamp, x, y, scale)
                        DocumentProcessor.renderPage(overlayPage, repository.getAll())?.let { rendered ->
                            pages = pages.mapIndexed { i, p -> if (i == pageIndex) p.copy(bitmap = rendered) else p }
                        }
                    }
                    mode = placementReturnMode
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(onScan: () -> Unit, onWord: () -> Unit, onStamp: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("PocketScanner", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(18.dp))
        MainButton("Сканировать", onScan)
        MainButton("Скан из Word", onWord)
        MainButton("Сохранить печать/подпись", onStamp)
        Spacer(Modifier.height(8.dp))
        MainButton("Открыть", onOpen)
    }
}

@Composable private fun MainButton(text: String, action: () -> Unit) = Button(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(text) }

@Composable
private fun StampLibraryScreen(stamps: List<StampItem>, onBack: () -> Unit, onSelected: (StampItem) -> Unit, onCreate: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 54.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Text("Печати и подписи", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Сохранить новую печать/подпись") }
            Spacer(Modifier.height(10.dp))
            LazyColumn {
                items(stamps) { stamp ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        val preview = remember(stamp.imagePath) { android.graphics.BitmapFactory.decodeFile(stamp.imagePath) }
                        if (preview != null) Image(preview.asImageBitmap(), stamp.name, Modifier.size(64.dp).padding(4.dp))
                        Button(onClick = { onSelected(stamp) }, modifier = Modifier.fillMaxWidth().padding(start = 6.dp)) { Text(stamp.name) }
                    }
                }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) { Text("‹  Назад") }
    }
}
