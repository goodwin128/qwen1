package com.example.pocketskanner.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Environment
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.pocketskanner.image.PerspectiveProcessor
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CaptureSettings(
    val iso: Int,
    val exposureNs: Long
)

enum class LightMode { OFF, AUTO, FLASH, TORCH }

@ExperimentalCamera2Interop
@Composable
fun CameraScanner(
    onImageCaptured: (Bitmap) -> Unit,
    onBack: () -> Unit,
    analyzeDarkFrame: Boolean = false,
    hint: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var lightMode by remember { mutableStateOf(LightMode.OFF) }
    var hasFlash by remember { mutableStateOf(false) }
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }
    var processingFrame by remember { mutableStateOf(false) }
    val latestCaptureSettings = remember { AtomicReference<CaptureSettings?>(null) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionGranted = it }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!permissionGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Нужен доступ к камере")
        }
        return
    }

    fun applyLightMode(mode: LightMode) {
        val c = camera ?: return
        val capture = imageCapture ?: return
        lightMode = mode
        when (mode) {
            LightMode.OFF -> {
                c.cameraControl.enableTorch(false)
                capture.flashMode = ImageCapture.FLASH_MODE_OFF
            }
            LightMode.AUTO -> {
                c.cameraControl.enableTorch(false)
                capture.flashMode = ImageCapture.FLASH_MODE_AUTO
            }
            LightMode.FLASH -> {
                c.cameraControl.enableTorch(false)
                capture.flashMode = ImageCapture.FLASH_MODE_ON
            }
            LightMode.TORCH -> {
                capture.flashMode = ImageCapture.FLASH_MODE_OFF
                c.cameraControl.enableTorch(true)
            }
        }
    }

    val previewResolutionSelector = remember {
        ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
    }

    // Capture is deliberately limited to a maximum 1600-pixel long side.
    // 1600x1200 keeps the usual 4:3 sensor geometry while preventing an
    // unnecessarily large full-resolution JPEG from entering the pipeline.
    val captureResolutionSelector = remember {
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(4400, 3300),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()

                        val finalPreviewBuilder = Preview.Builder()
                            .setResolutionSelector(previewResolutionSelector)
                        Camera2Interop.Extender(finalPreviewBuilder)
                            .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult
                                ) {
                                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                                    val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                    if (iso != null && exposure != null && iso > 0 && exposure > 0L) {
                                        latestCaptureSettings.set(CaptureSettings(iso, exposure))
                                    }
                                }
                            })
                        val finalPreview = finalPreviewBuilder.build()
                            .also { it.surfaceProvider = previewView.surfaceProvider }

                        val finalCapture = ImageCapture.Builder()
                            .setResolutionSelector(captureResolutionSelector)
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .build()

                        try {
                            provider.unbindAll()
                            imageCapture = finalCapture
                            camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                finalPreview,
                                finalCapture
                            )
                            hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                            applyLightMode(LightMode.OFF)
                        } catch (_: Exception) {
                            Toast.makeText(
                                ctx,
                                "Не удалось запустить камеру",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Live view: document scanning area is 88% of screen width and 1.41 times
        // that width high. The corners are deliberately simple so the user can
        // align a physical A4/letter-like document without a heavy rectangle.
        ScanAreaOverlay(
            modifier = Modifier.fillMaxSize(),
            visible = analyzeDarkFrame && frozenFrame == null
        )

        frozenFrame?.let { frame ->
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        if (analyzeDarkFrame && processingFrame && frozenFrame != null) {
            ScannerLampOverlay()
        }

        if (hasFlash && frozenFrame == null) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 58.dp)
                    .background(Color.Black.copy(alpha = .55f), CircleShape)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                LightButton("Выкл", lightMode == LightMode.OFF) { applyLightMode(LightMode.OFF) }
                LightButton("Авто", lightMode == LightMode.AUTO) { applyLightMode(LightMode.AUTO) }
                LightButton("⚡", lightMode == LightMode.FLASH) { applyLightMode(LightMode.FLASH) }
                LightButton("🔦", lightMode == LightMode.TORCH) { applyLightMode(LightMode.TORCH) }
            }
        }

        if (frozenFrame == null) {
            TextButton(
                onClick = {
                    camera?.cameraControl?.enableTorch(false)
                    onBack()
                },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) { Text("‹  Назад", fontSize = 18.sp) }

            if (analyzeDarkFrame) {
                Text(
                    text = "Расположите документ в пределах области сканирования",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (hasFlash) 106.dp else 58.dp, start = 20.dp, end = 20.dp)
                        .background(
                            Color.Black.copy(alpha = .58f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }

            // Keep any legacy hint available below the main scanning instruction.
            if (hint != null) {
                Text(
                    text = hint,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (hasFlash) 150.dp else 102.dp, start = 20.dp, end = 20.dp)
                        .background(
                            Color.Black.copy(alpha = .48f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            Button(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .size(72.dp),
                shape = CircleShape,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color.Red,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(0.dp),
                onClick = {
                    val capture = imageCapture ?: return@Button
                    if (processingFrame) return@Button
                    val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                        ?: return@Button
                    val file = File(directory, "scan_${System.currentTimeMillis()}.jpg")
                    val output = ImageCapture.OutputFileOptions.Builder(file).build()
                    val settings = latestCaptureSettings.get()

                    fun restoreCameraAfterCapture() {
                        val c = camera ?: return
                        val control = Camera2CameraControl.from(c.cameraControl)

                        // Remove the temporary ISO/exposure/flash request used only for this frame.
                        runCatching { control.clearCaptureRequestOptions() }

                        // Restore exactly the light mode selected by the user.
                        when (lightMode) {
                            LightMode.TORCH -> {
                                c.cameraControl.enableTorch(true)
                                imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                            }
                            LightMode.AUTO -> {
                                c.cameraControl.enableTorch(false)
                                imageCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
                            }
                            LightMode.FLASH -> {
                                c.cameraControl.enableTorch(false)
                                imageCapture?.flashMode = ImageCapture.FLASH_MODE_ON
                            }
                            LightMode.OFF -> {
                                c.cameraControl.enableTorch(false)
                                imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                            }
                        }
                    }

                    fun takeConfiguredPicture() {
                        capture.takePicture(
                            output,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    val captured = BitmapFactory.decodeFile(file.absolutePath)
                                    if (captured == null) {
                                        restoreCameraAfterCapture()
                                        Toast.makeText(context, "Ошибка камеры", Toast.LENGTH_SHORT).show()
                                        return
                                    }

                                    // The temporary capture parameters are no longer needed.
                                    // Restore them immediately, including the selected torch/flash mode.
                                    restoreCameraAfterCapture()

                                    if (!analyzeDarkFrame) {
                                        onImageCaptured(captured)
                                        return
                                    }

                                    frozenFrame = captured
                                    processingFrame = true

                                    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                                        // Corner detection is the first processing stage needed by
                                        // the red-frame editor. The lamp continues until this is done.
                                        PerspectiveProcessor.detectCorners(captured)
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            processingFrame = false
                                            frozenFrame = null
                                            onImageCaptured(captured)
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    restoreCameraAfterCapture()
                                    processingFrame = false
                                    Toast.makeText(context, "Ошибка камеры", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }

                    // Manual ISO/exposure correction is used only when the light is OFF.
                    // AUTO, FLASH and TORCH must keep their normal CameraX exposure/lighting
                    // behaviour without overriding it with a Camera2 manual request.
                    if (analyzeDarkFrame && settings != null && lightMode == LightMode.OFF) {
                        val iso = (settings.iso * 1.8f).roundToInt().coerceIn(50, 51200)
                        val exposure = (settings.exposureNs * 0.65).toLong().coerceAtLeast(1000L)
                        val camera2Control = camera?.cameraControl?.let { Camera2CameraControl.from(it) }

                        if (camera2Control != null) {
                            val builder = CaptureRequestOptions.Builder()
                                .setCaptureRequestOption(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_OFF
                                )
                                .setCaptureRequestOption(
                                    CaptureRequest.SENSOR_SENSITIVITY,
                                    iso
                                )
                                .setCaptureRequestOption(
                                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                                    exposure
                                )
                                .setCaptureRequestOption(
                                    CaptureRequest.FLASH_MODE,
                                    CaptureRequest.FLASH_MODE_OFF
                                )

                            camera2Control.setCaptureRequestOptions(builder.build())
                                .addListener(
                                    { takeConfiguredPicture() },
                                    ContextCompat.getMainExecutor(context)
                                )
                        } else {
                            takeConfiguredPicture()
                        }
                    } else {
                        // AUTO / FLASH / TORCH: no manual ISO/exposure correction.
                        // The selected ImageCapture light mode remains in control.
                        takeConfiguredPicture()
                    }
                }
            ) { }
        }
    }
}

@Composable
private fun ScanAreaOverlay(
    modifier: Modifier,
    visible: Boolean
) {
    if (!visible) return
    BoxWithConstraints(modifier) {
        val width = maxWidth * 0.72f
        val height = width * 1.41f
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(width)
                .size(height = height, width = width)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val corner = min(size.width, size.height) * 0.07f
                val stroke = 5f
                val color = Color.White.copy(alpha = 0.95f)
                drawLine(color, Offset(0f, 0f), Offset(corner, 0f), stroke)
                drawLine(color, Offset(0f, 0f), Offset(0f, corner), stroke)
                drawLine(color, Offset(size.width, 0f), Offset(size.width - corner, 0f), stroke)
                drawLine(color, Offset(size.width, 0f), Offset(size.width, corner), stroke)
                drawLine(color, Offset(0f, size.height), Offset(corner, size.height), stroke)
                drawLine(color, Offset(0f, size.height), Offset(0f, size.height - corner), stroke)
                drawLine(color, Offset(size.width, size.height), Offset(size.width - corner, size.height), stroke)
                drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - corner), stroke)
            }
        }
    }
}

@Composable
private fun ScannerLampOverlay() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth * 0.72f
        val height = width * 1.41f
        val infinite = rememberInfiniteTransition(label = "scannerLamp")
        val position by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1300, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "lampPosition"
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(width)
                .size(height = height, width = width)
                .clip(RoundedCornerShape(2.dp))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height * position
                val glow = size.height * 0.045f
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.32f),
                            Color.White.copy(alpha = 0.9f),
                            Color.White.copy(alpha = 0.32f),
                            Color.White.copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        startY = y - glow,
                        endY = y + glow
                    ),
                    topLeft = Offset(0f, y - glow),
                    size = androidx.compose.ui.geometry.Size(size.width, glow * 2f)
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.92f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 3.5f
                )
            }
        }
    }
}

@Composable
private fun LightButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(width = 58.dp, height = 34.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(if (selected) "✓ $text" else text, fontSize = 11.sp)
    }
}
