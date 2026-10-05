package com.example.pocketskanner.ui

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun StampReferenceEditorScreen(
    bitmap: Bitmap,
    initialPageCorners: List<PointF>,
    initialObjectCorners: List<PointF>,
    onBack: () -> Unit,
    onDone: (pageCorners: List<PointF>, objectCorners: List<PointF>) -> Unit
) {
    var pageCorners by remember(bitmap) {
        mutableStateOf(initialPageCorners.map { PointF(it.x, it.y) })
    }
    var objectRect by remember(bitmap) {
        mutableStateOf(cornersToRect(initialObjectCorners))
    }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var panX by remember(bitmap) { mutableFloatStateOf(0f) }
    var panY by remember(bitmap) { mutableFloatStateOf(0f) }
    var activeMode by remember { mutableStateOf(InteractionMode.NONE) }
    var activeRedCorner by remember { mutableStateOf(-1) }
    var activeGreenEdge by remember { mutableStateOf(GreenEdge.NONE) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val canvasWidth = constraints.maxWidth.toFloat()
        val canvasHeight = constraints.maxHeight.toFloat()
        val fitScale = min(
            canvasWidth / bitmap.width.toFloat(),
            canvasHeight / bitmap.height.toFloat()
        )
        val baseWidth = bitmap.width * fitScale
        val baseHeight = bitmap.height * fitScale
        val baseLeft = (canvasWidth - baseWidth) / 2f
        val baseTop = (canvasHeight - baseHeight) / 2f
        val center = Offset(canvasWidth / 2f, canvasHeight / 2f)
        val shownWidth = baseWidth * zoom
        val shownHeight = baseHeight * zoom
        val imageLeft = center.x - shownWidth / 2f + panX
        val imageTop = center.y - shownHeight / 2f + panY

        fun toScreen(point: PointF): Offset = Offset(
            imageLeft + point.x * shownWidth,
            imageTop + point.y * shownHeight
        )

        fun toNormalized(position: Offset): PointF = PointF(
            ((position.x - imageLeft) / shownWidth).coerceIn(0.001f, 0.999f),
            ((position.y - imageTop) / shownHeight).coerceIn(0.001f, 0.999f)
        )

        fun nearestRedCorner(position: Offset): Int {
            var best = -1
            var bestDistance = 70f * 70f
            pageCorners.forEachIndexed { index, point ->
                val p = toScreen(point)
                val dx = p.x - position.x
                val dy = p.y - position.y
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            return best
        }

        fun nearestGreenEdge(position: Offset): GreenEdge {
            val left = toScreen(PointF(objectRect.left, (objectRect.top + objectRect.bottom) / 2f))
            val right = toScreen(PointF(objectRect.right, (objectRect.top + objectRect.bottom) / 2f))
            val top = toScreen(PointF((objectRect.left + objectRect.right) / 2f, objectRect.top))
            val bottom = toScreen(PointF((objectRect.left + objectRect.right) / 2f, objectRect.bottom))
            val candidates = listOf(
                GreenEdge.LEFT to abs(position.x - left.x),
                GreenEdge.RIGHT to abs(position.x - right.x),
                GreenEdge.TOP to abs(position.y - top.y),
                GreenEdge.BOTTOM to abs(position.y - bottom.y)
            )
            val best = candidates.minByOrNull { it.second } ?: return GreenEdge.NONE
            return if (best.second <= 42f) best.first else GreenEdge.NONE
        }

        fun moveGreenEdge(edge: GreenEdge, position: Offset) {
            val p = toNormalized(position)
            val r = RectF(objectRect)
            when (edge) {
                GreenEdge.LEFT -> r.left = p.x.coerceIn(0.001f, r.right - 0.002f)
                GreenEdge.RIGHT -> r.right = p.x.coerceIn(r.left + 0.002f, 0.999f)
                GreenEdge.TOP -> r.top = p.y.coerceIn(0.001f, r.bottom - 0.002f)
                GreenEdge.BOTTOM -> r.bottom = p.y.coerceIn(r.top + 0.002f, 0.999f)
                GreenEdge.NONE -> Unit
            }
            objectRect = r
        }

        Box(Modifier.fillMaxSize()) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoom,
                        scaleY = zoom,
                        translationX = panX,
                        translationY = panY
                    ),
                contentScale = ContentScale.Fit
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap) {
                        awaitEachGesture {
                            val down = awaitPointerEvent()
                            val first = down.changes.firstOrNull() ?: return@awaitEachGesture
                            val start = first.position
                            val red = nearestRedCorner(start)
                            val green = nearestGreenEdge(start)
                            activeRedCorner = red
                            activeGreenEdge = green
                            activeMode = when {
                                red >= 0 -> InteractionMode.RED_CORNER
                                green != GreenEdge.NONE -> InteractionMode.GREEN_EDGE
                                else -> InteractionMode.PAN_ZOOM
                            }

                            var lastCentroid = start
                            var lastDistance = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val changes = event.changes
                                if (changes.none { it.pressed }) break

                                val pressed = changes.filter { it.pressed }
                                val centroid = Offset(
                                    pressed.map { it.position.x }.average().toFloat(),
                                    pressed.map { it.position.y }.average().toFloat()
                                )

                                if (pressed.size >= 2) {
                                    val a = pressed[0].position
                                    val b = pressed[1].position
                                    val distance = (a - b).getDistance()
                                    if (lastDistance > 0f) {
                                        val factor = (distance / lastDistance).coerceIn(0.7f, 1.4f)
                                        zoom = (zoom * factor).coerceIn(1f, 4f)
                                    }
                                    val pan = centroid - lastCentroid
                                    val maxPanX = ((baseWidth * zoom - canvasWidth) / 2f).coerceAtLeast(0f)
                                    val maxPanY = ((baseHeight * zoom - canvasHeight) / 2f).coerceAtLeast(0f)
                                    panX = (panX + pan.x).coerceIn(-maxPanX, maxPanX)
                                    panY = (panY + pan.y).coerceIn(-maxPanY, maxPanY)
                                    lastDistance = distance
                                    lastCentroid = centroid
                                    changes.forEach { it.consume() }
                                    continue
                                }

                                val change = pressed.first()
                                when (activeMode) {
                                    InteractionMode.RED_CORNER -> {
                                        if (activeRedCorner >= 0) {
                                            val p = toNormalized(change.position)
                                            val updated = pageCorners.map { PointF(it.x, it.y) }.toMutableList()
                                            updated[activeRedCorner] = p
                                            pageCorners = updated
                                            change.consume()
                                        }
                                    }
                                    InteractionMode.GREEN_EDGE -> {
                                        moveGreenEdge(activeGreenEdge, change.position)
                                        change.consume()
                                    }
                                    InteractionMode.PAN_ZOOM -> {
                                        val pan = change.position - lastCentroid
                                        val maxPanX = ((baseWidth * zoom - canvasWidth) / 2f).coerceAtLeast(0f)
                                        val maxPanY = ((baseHeight * zoom - canvasHeight) / 2f).coerceAtLeast(0f)
                                        panX = (panX + pan.x).coerceIn(-maxPanX, maxPanX)
                                        panY = (panY + pan.y).coerceIn(-maxPanY, maxPanY)
                                        change.consume()
                                    }
                                    InteractionMode.NONE -> Unit
                                }
                                lastCentroid = change.position
                            }
                            activeMode = InteractionMode.NONE
                            activeRedCorner = -1
                            activeGreenEdge = GreenEdge.NONE
                            lastDistance = 0f
                        }
                    }
            ) {
                drawQuadrilateral(pageCorners, Color.Red, ::toScreen)
                drawRectOutline(objectRect, Color.Green, ::toScreen)
            }

            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) { Text("‹  Назад") }

            Button(
                onClick = {
                    onDone(
                        pageCorners.map { PointF(it.x, it.y) },
                        rectToCorners(objectRect)
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
            ) { Text("Подтвердить") }
        }
    }
}

private enum class InteractionMode { NONE, RED_CORNER, GREEN_EDGE, PAN_ZOOM }
private enum class GreenEdge { NONE, LEFT, RIGHT, TOP, BOTTOM }

private fun cornersToRect(corners: List<PointF>): RectF {
    if (corners.size != 4) return RectF(.35f, .35f, .65f, .65f)
    return RectF(
        corners.minOf { it.x },
        corners.minOf { it.y },
        corners.maxOf { it.x },
        corners.maxOf { it.y }
    )
}

private fun rectToCorners(rect: RectF): List<PointF> = listOf(
    PointF(rect.left, rect.top),
    PointF(rect.right, rect.top),
    PointF(rect.right, rect.bottom),
    PointF(rect.left, rect.bottom)
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQuadrilateral(
    corners: List<PointF>,
    color: Color,
    toScreen: (PointF) -> Offset
) {
    if (corners.size != 4) return
    val points = corners.map(toScreen)
    for (i in points.indices) {
        drawLine(color, points[i], points[(i + 1) % points.size], strokeWidth = 5f)
    }
    points.forEach {
        drawCircle(Color.White, radius = 19f, center = it)
        drawCircle(color, radius = 14f, center = it)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRectOutline(
    rect: RectF,
    color: Color,
    toScreen: (PointF) -> Offset
) {
    val points = rectToCorners(rect).map(toScreen)
    for (i in points.indices) {
        drawLine(color, points[i], points[(i + 1) % points.size], strokeWidth = 6f)
    }
}
