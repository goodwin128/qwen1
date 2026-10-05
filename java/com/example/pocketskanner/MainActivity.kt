package com.example.pocketskanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.pocketskanner.document.ExportManager
import com.example.pocketskanner.ui.PocketScannerApp
import com.example.pocketskanner.ui.theme.PocketSkannerTheme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var incomingUri by mutableStateOf<Uri?>(null)

    private val executor =
        Executors.newSingleThreadExecutor()

    private val openFileLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }

                incomingUri = uri
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        /*
         * Файл, открытый через:
         *
         * "Открыть с помощью → PocketScanner"
         */
        incomingUri =
            intent?.data

        if (incomingUri == null) {

            incomingUri =
                intent?.getParcelableExtra(
                    Intent.EXTRA_STREAM
                )
        }

        setContent {

            PocketSkannerTheme {

                PocketScannerApp(

                    initialUri =
                        incomingUri,

                    onOpenFile = {

                        openFileLauncher.launch(arrayOf("*/*"))
                    },

                    onSharePdf = { pages, name ->

                        val uri =
                            ExportManager.savePdf(
                                this,
                                pages,
                                name
                            )

                        if (uri != null) {

                            ExportManager.share(
                                this,
                                uri,
                                "application/pdf"
                            )
                        }
                    },

                    onSharePng = { pages, name ->

                        val uris =
                            ExportManager.savePng(
                                this,
                                pages,
                                name
                            )

                        if (uris.isNotEmpty()) {

                            ExportManager.shareMultiple(
                                this,
                                uris,
                                "image/png"
                            )
                        }
                    },

                    onSavePdf = { pages, name ->
                        val ok = ExportManager.savePdfToDevice(this, pages, name)
                        Toast.makeText(this, if (ok) "PDF сохранён" else "Не удалось сохранить PDF", Toast.LENGTH_SHORT).show()
                    },

                    onSavePng = { pages, name ->
                        val ok = ExportManager.savePngToDevice(this, pages, name)
                        Toast.makeText(this, if (ok) "PNG сохранён" else "Не удалось сохранить PNG", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    override fun onDestroy() {

        executor.shutdown()

        super.onDestroy()
    }
}
