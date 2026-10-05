package com.example.pocketskanner.ui

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.pocketskanner.image.ImageProcessor
import com.example.pocketskanner.image.PerspectiveProcessor
import kotlin.math.min

@Composable
fun PerspectiveEditorScreen(
    bitmap: Bitmap,
    onBack: () -> Unit,
    onDone: (Bitmap) -> Unit
) {

    var corners by remember(bitmap) {

        mutableStateOf(
            PerspectiveProcessor
                .detectCorners(bitmap)
        )
    }

    var activeCorner by remember {
        mutableIntStateOf(-1)
    }

    BoxWithConstraints(
        modifier =
            Modifier.fillMaxSize()
    ) {

        val canvasWidth =
            constraints.maxWidth.toFloat()

        val canvasHeight =
            constraints.maxHeight.toFloat()

        val scale =
            min(
                canvasWidth /
                    bitmap.width,

                canvasHeight /
                    bitmap.height
            )

        val imageWidth =
            bitmap.width * scale

        val imageHeight =
            bitmap.height * scale

        val imageLeft =
            (
                canvasWidth -
                    imageWidth
            ) / 2f

        val imageTop =
            (
                canvasHeight -
                    imageHeight
            ) / 2f

        Box(
            modifier =
                Modifier.fillMaxSize()
        ) {

            Image(
                bitmap =
                    bitmap.asImageBitmap(),

                contentDescription =
                    null,

                modifier =
                    Modifier.fillMaxSize(),

                contentScale =
                    ContentScale.Fit
            )

            Canvas(

                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap) {

                            detectDragGestures(

                                onDragStart = {
                                    position ->

                                    val nearest =
                                        corners.indices
                                            .minByOrNull { index ->

                                                val point =
                                                    corners[index]

                                                val x =
                                                    imageLeft +
                                                        point.x *
                                                        imageWidth

                                                val y =
                                                    imageTop +
                                                        point.y *
                                                        imageHeight

                                                (
                                                    x -
                                                        position.x
                                                ) *
                                                (
                                                    x -
                                                        position.x
                                                ) +
                                                (
                                                    y -
                                                        position.y
                                                ) *
                                                (
                                                    y -
                                                        position.y
                                                )
                                            }

                                    activeCorner =
                                        if (
                                            nearest != null
                                        ) {

                                            val point =
                                                corners[
                                                    nearest
                                                ]

                                            val x =
                                                imageLeft +
                                                    point.x *
                                                    imageWidth

                                            val y =
                                                imageTop +
                                                    point.y *
                                                    imageHeight

                                            val distance =
                                                (
                                                    x -
                                                        position.x
                                                ) *
                                                (
                                                    x -
                                                        position.x
                                                ) +
                                                (
                                                    y -
                                                        position.y
                                                ) *
                                                (
                                                    y -
                                                        position.y
                                                )

                                            if (
                                                distance <=
                                                    90f * 90f
                                            ) {
                                                nearest
                                            } else {
                                                -1
                                            }

                                        } else {
                                            -1
                                        }
                                },

                                onDragEnd = {
                                    activeCorner =
                                        -1
                                },

                                onDragCancel = {
                                    activeCorner =
                                        -1
                                },

                                onDrag = {
                                    change,
                                    _ ->

                                    if (
                                        activeCorner >= 0
                                    ) {

                                        val newX =
                                            (
                                                (
                                                    change.position.x -
                                                        imageLeft
                                                ) /
                                                    imageWidth
                                            )
                                                .coerceIn(
                                                    0.01f,
                                                    0.99f
                                                )

                                        val newY =
                                            (
                                                (
                                                    change.position.y -
                                                        imageTop
                                                ) /
                                                    imageHeight
                                            )
                                                .coerceIn(
                                                    0.01f,
                                                    0.99f
                                                )

                                        val updated =
                                            corners
                                                .toMutableList()

                                        updated[
                                            activeCorner
                                        ] =
                                            PointF(
                                                newX,
                                                newY
                                            )

                                        corners =
                                            updated

                                        change.consume()
                                    }
                                }
                            )
                        }

            ) {

                val points =
                    corners.map {

                        Offset(

                            imageLeft +
                                it.x *
                                imageWidth,

                            imageTop +
                                it.y *
                                imageHeight
                        )
                    }

                for (
                    index in
                    points.indices
                ) {

                    drawLine(

                        color =
                            Color.Red,

                        start =
                            points[index],

                        end =
                            points[
                                (
                                    index + 1
                                ) %
                                    points.size
                            ],

                        strokeWidth =
                            5f
                    )
                }

                points.forEach {

                    drawCircle(
                        color =
                            Color.White,

                        radius =
                            18f,

                        center =
                            it
                    )

                    drawCircle(
                        color =
                            Color.Red,

                        radius =
                            13f,

                        center =
                            it
                    )
                }
            }

            androidx.compose.material3.TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) { Text("‹  Назад") }

            Button(

                onClick = {

                    val corrected =
                        PerspectiveProcessor
                            .correctPerspective(
                                bitmap,
                                corners
                            )

                    onDone(
                        corrected
                    )
                },

                modifier =
                    Modifier
                        .align(
                            Alignment.BottomCenter
                        )
                        .padding(
                            bottom = 18.dp
                        )
            ) {

                Text(
                    "ОК"
                )
            }
        }
    }
}
