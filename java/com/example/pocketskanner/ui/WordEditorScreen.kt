package com.example.pocketskanner.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WordEditorScreen(
    html: String?,
    loading: Boolean,
    onBack: () -> Unit,
    onContinue: (List<Bitmap>) -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        if (loading || html == null) {

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Открытие документа…")
            }

        } else {

            AndroidView(
                factory = { context ->

                    WebView(context).apply {

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.defaultFontSize = 16

                        isVerticalScrollBarEnabled = true

                        setBackgroundColor(Color.rgb(221, 221, 221))

                        webViewClient = object : WebViewClient() {

                            override fun onPageFinished(
                                view: WebView,
                                url: String?
                            ) {
                                ready = true

                                view.evaluateJavascript(
                                    """
                                    document.body.style.zoom = '1';
                                    document.documentElement.scrollTop = 0;
                                    document.body.scrollTop = 0;
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }

                        loadDataWithBaseURL(
                            null,
                            html,
                            "text/html",
                            "UTF-8",
                            null
                        )

                        webView = this
                    }
                },

                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = 48.dp,
                        bottom = 70.dp
                    ),

                update = { view ->
                    webView = view
                }
            )
        }

        // Кнопка назад
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
        ) {
            Text("‹  Назад")
        }

        // Продолжить
        if (ready) {

            Button(
                onClick = {

                    val view = webView ?: return@Button

                    /*
                     * Убираем курсор, выделение и режим редактирования.
                     * После этого получаем реальную высоту всей страницы.
                     */
                    view.evaluateJavascript(
                        """
                        (function() {
                            document.activeElement &&
                                document.activeElement.blur();

                            if (window.getSelection) {
                                window.getSelection().removeAllRanges();
                            }

                            document.body.contentEditable = 'false';
                            document.body.style.caretColor = 'transparent';

                            document.querySelectorAll('[contenteditable]')
                                .forEach(function(e) {
                                    e.contentEditable = 'false';
                                });

                            document.documentElement.scrollTop = 0;
                            document.body.scrollTop = 0;

                            return Math.max(
                                document.body.scrollHeight,
                                document.documentElement.scrollHeight,
                                document.body.offsetHeight,
                                document.documentElement.offsetHeight
                            ).toString();
                        })();
                        """.trimIndent()
                    ) { value ->

                        val cssHeight =
                            value
                                .replace("\"", "")
                                .toFloatOrNull()
                                ?.toInt()
                                ?.coerceAtLeast(1123)
                                ?: 1123

                        view.post {

                            // A4 в CSS-пикселях при 96 DPI
                            val cssWidth = 794
                            val pageHeight = 1123

                            val totalPages =
                                ((cssHeight + pageHeight - 1) / pageHeight)
                                    .coerceAtLeast(1)

                            /*
                             * Рендерим ВСЮ страницу, а не только
                             * видимую область WebView.
                             */
                            val fullHeight =
                                totalPages * pageHeight

                            val fullBitmap = Bitmap.createBitmap(
                                cssWidth,
                                fullHeight,
                                Bitmap.Config.ARGB_8888
                            )

                            val fullCanvas = Canvas(fullBitmap)
                            fullCanvas.drawColor(Color.WHITE)

                            view.measure(
                                View.MeasureSpec.makeMeasureSpec(
                                    cssWidth,
                                    View.MeasureSpec.EXACTLY
                                ),
                                View.MeasureSpec.makeMeasureSpec(
                                    cssHeight,
                                    View.MeasureSpec.EXACTLY
                                )
                            )

                            view.layout(
                                0,
                                0,
                                cssWidth,
                                cssHeight
                            )

                            fullCanvas.save()

                            fullCanvas.clipRect(
                                0,
                                0,
                                cssWidth,
                                cssHeight
                            )

                            view.draw(fullCanvas)

                            fullCanvas.restore()

                            /*
                             * Разрезаем документ на страницы A4.
                             */
                            val pages = ArrayList<Bitmap>()

                            for (i in 0 until totalPages) {

                                val pageBitmap = Bitmap.createBitmap(
                                    cssWidth,
                                    pageHeight,
                                    Bitmap.Config.ARGB_8888
                                )

                                val pageCanvas = Canvas(pageBitmap)
                                pageCanvas.drawColor(Color.WHITE)

                                pageCanvas.drawBitmap(
                                    fullBitmap,
                                    0f,
                                    -(i * pageHeight).toFloat(),
                                    null
                                )

                                /*
                                 * Финальный размер страницы —
                                 * 1240 × 1754, примерно 150 DPI для A4.
                                 */
                                val outputPage =
                                    Bitmap.createScaledBitmap(
                                        pageBitmap,
                                        1240,
                                        1754,
                                        true
                                    )

                                pages += outputPage

                                pageBitmap.recycle()
                            }

                            fullBitmap.recycle()

                            onContinue(pages)
                        }
                    }

                },

                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            ) {
                Text("Продолжить")
            }
        }
    }
}