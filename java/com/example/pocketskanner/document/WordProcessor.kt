package com.example.pocketskanner.document

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.InputStream

object WordProcessor {

    fun isWord(
        context: Context,
        uri: Uri
    ): Boolean {
        val mime =
            context.contentResolver
                .getType(uri)
                ?.lowercase()
                .orEmpty()

        val value =
            uri.toString()
                .lowercase()

        return mime == "application/msword" ||
                mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                value.endsWith(".doc") ||
                value.endsWith(".docx") ||
                value.contains("openxmlformats-officedocument")
    }

    fun convert(
        context: Context,
        uri: Uri
    ): List<android.graphics.Bitmap> {

        return context.contentResolver
            .openInputStream(uri)
            ?.use { input ->

                if (isDocx(context, uri)) {
                    convertDocx(input)
                } else {
                    convertDoc(input)
                }
            }
            ?: emptyList()
    }

    fun toEditableHtml(
        context: Context,
        uri: Uri
    ): String? {

        return context.contentResolver
            .openInputStream(uri)
            ?.use { input ->

                if (isDocx(context, uri)) {
                    docxToHtml(input)
                } else {
                    docToHtml(input)
                }
            }
    }

    private fun isDocx(
        context: Context,
        uri: Uri
    ): Boolean {

        val mime =
            context.contentResolver
                .getType(uri)
                ?.lowercase()
                .orEmpty()

        val value =
            uri.toString()
                .lowercase()

        return mime ==
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                value.contains(".docx") ||
                value.contains("openxmlformats-officedocument")
    }

    /*
     * =====================================================
     * DOCX → HTML
     * =====================================================
     *
     * Используется для редактора Word.
     *
     * Важно:
     * - текст сохраняется;
     * - изображения сохраняются;
     * - таблицы сохраняются;
     * - базовое форматирование сохраняется.
     */
    private fun docxToHtml(
        input: InputStream
    ): String {

        XWPFDocument(input).use { doc ->

            val html =
                StringBuilder(
                    baseHtml()
                )

            html.append(
                "<div class='page' contenteditable='true'>"
            )

            for (body in doc.bodyElements) {

                when (body) {

                    is org.apache.poi.xwpf.usermodel.XWPFParagraph -> {

                        appendParagraph(
                            html,
                            body
                        )
                    }

                    is org.apache.poi.xwpf.usermodel.XWPFTable -> {

                        appendTable(
                            html,
                            body
                        )
                    }
                }
            }

            html.append(
                "</div></body></html>"
            )

            return html.toString()
        }
    }

    /*
     * =====================================================
     * ПАРАГРАФ DOCX
     * =====================================================
     */
    private fun appendParagraph(
        html: StringBuilder,
        paragraph: org.apache.poi.xwpf.usermodel.XWPFParagraph
    ) {

        val alignment =
            when (
                paragraph.alignment
                    ?.toString()
                    ?.lowercase()
            ) {

                "center" ->
                    "center"

                "right" ->
                    "right"

                "both",
                "distribute" ->
                    "justify"

                else ->
                    "left"
            }

        html.append(
            "<p style='text-align:$alignment'>"
        )

        for (run in paragraph.runs) {

            val text =
                escape(
                    run.text() ?: ""
                )

            val weight =
                if (run.isBold) {
                    "font-weight:bold;"
                } else {
                    ""
                }

            val italic =
                if (run.isItalic) {
                    "font-style:italic;"
                } else {
                    ""
                }

            val underline =
                if (
                    run.underline !=
                    org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE
                ) {
                    "text-decoration:underline;"
                } else {
                    ""
                }

            html.append(
                "<span style='$weight$italic$underline'>"
            )

            html.append(
                text
            )

            html.append(
                "</span>"
            )

            /*
             * Изображения внутри Word run.
             */
            for (
                picture
                in run.embeddedPictures
            ) {

                val data =
                    picture.getPictureData().data

                val mime =
                    when (
                        picture.getPictureData().pictureType
                    ) {

                        6 ->
                            "image/jpeg"

                        7 ->
                            "image/png"

                        8 ->
                            "image/gif"

                        else ->
                            "image/png"
                    }

                html.append(
                    "<img class='doc-image' src='data:$mime;base64,"
                )

                html.append(
                    Base64.encodeToString(
                        data,
                        Base64.NO_WRAP
                    )
                )

                html.append(
                    "'/>"
                )
            }
        }

        html.append(
            "</p>"
        )
    }

    /*
     * =====================================================
     * ТАБЛИЦА DOCX
     * =====================================================
     */
    private fun appendTable(
        html: StringBuilder,
        table: org.apache.poi.xwpf.usermodel.XWPFTable
    ) {

        html.append(
            "<table><tbody>"
        )

        for (
            row
            in table.rows
        ) {

            html.append(
                "<tr>"
            )

            for (
                cell
                in row.tableCells
            ) {

                html.append(
                    "<td>"
                )

                for (
                    paragraph
                    in cell.paragraphs
                ) {

                    html.append(
                        escape(
                            paragraph.text
                        )
                    )

                    html.append(
                        "<br>"
                    )
                }

                html.append(
                    "</td>"
                )
            }

            html.append(
                "</tr>"
            )
        }

        html.append(
            "</tbody></table>"
        )
    }

    /*
     * =====================================================
     * DOC → HTML
     * =====================================================
     */
    private fun docToHtml(
        input: InputStream
    ): String {

        HWPFDocument(input).use { doc ->

            val html =
                StringBuilder(
                    baseHtml()
                )

            html.append(
                "<div class='page' contenteditable='true'>"
            )

            val range =
                doc.range

            for (
                i
                in 0 until range.numParagraphs()
            ) {

                html.append(
                    "<p>"
                )

                html.append(
                    escape(
                        range
                            .getParagraph(i)
                            .text()
                            .trimEnd()
                    )
                )

                html.append(
                    "</p>"
                )
            }

            html.append(
                "</div></body></html>"
            )

            return html.toString()
        }
    }

    /*
     * =====================================================
     * HTML ОСНОВА
     * =====================================================
     */
    private fun baseHtml(): String {

        return """
            <!doctype html>
            <html>
            <head>

            <meta
                name='viewport'
                content='width=1240, initial-scale=1.0'
            >

            <style>

            * {
                box-sizing: border-box;
            }

            body {
                margin: 0;
                background: #ddd;
                font-family: serif;
                color: #111;
            }

            .page {
                width: 794px;
                min-height: 1123px;

                margin: 20px auto;

                background: white;

                padding: 70px;

                font-size: 18px;

                line-height: 1.35;
                overflow-wrap: anywhere;

                outline: none;
            }

            p {
                margin:
                    0
                    0
                    18px
                    0;
            }

            table {
                border-collapse: collapse;

                width: 100%;

                margin:
                    18px
                    0;
            }

            td,
            th {
                border:
                    1px
                    solid
                    #555;

                padding: 8px;

                vertical-align: top;
            }

            .doc-image {
                max-width: 100%;

                height: auto;

                display: block;

                margin:
                    10px
                    auto;
            }

            @media print {

                body {
                    background: white;
                }

                .page {
                    margin: 0;
                }
            }

            </style>

            </head>
            <body>
        """.trimIndent()
    }

    /*
     * =====================================================
     * HTML ESCAPE
     * =====================================================
     */
    private fun escape(
        text: String
    ): String {

        return text
            .replace(
                "&",
                "&amp;"
            )
            .replace(
                "<",
                "&lt;"
            )
            .replace(
                ">",
                "&gt;"
            )
            .replace(
                "\"",
                "&quot;"
            )
    }

    /*
     * =====================================================
     * DOCX → BITMAP
     * =====================================================
     */
    private fun convertDocx(
        input: InputStream
    ): List<android.graphics.Bitmap> {

        return renderPlainTextPages(
            XWPFDocument(input).use { doc ->

                doc.paragraphs.map {
                    it.text
                }
            }
        )
    }

    /*
     * =====================================================
     * DOC → BITMAP
     * =====================================================
     */
    private fun convertDoc(
        input: InputStream
    ): List<android.graphics.Bitmap> {

        return HWPFDocument(input).use { doc ->

            val range =
                doc.range

            val lines =
                (0 until range.numParagraphs())
                    .map {
                        range
                            .getParagraph(it)
                            .text()
                    }

            renderPlainTextPages(
                lines
            )
        }
    }

    /*
     * =====================================================
     * РЕНДЕР ТЕКСТА В СТРАНИЦЫ
     * =====================================================
     */
    private fun renderPlainTextPages(
        lines: List<String>
    ): List<android.graphics.Bitmap> {

        val width =
            1240

        val height =
            1754

        val margin =
            100f

        val paint =
            android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    android.graphics.Color.BLACK

                textSize =
                    30f
            }

        val lineHeight =
            41f

        val maxLines =
            (
                (height - 2 * margin) /
                    lineHeight
                ).toInt()

        val wrapped =
            mutableListOf<String>()

        for (
            raw
            in lines
        ) {

            var rest =
                raw
                    .replace(
                        '\t',
                        ' '
                    )
                    .trimEnd()

            if (
                rest.isEmpty()
            ) {

                wrapped += ""

                continue
            }

            while (
                rest.isNotEmpty()
            ) {

                var n =
                    paint
                        .breakText(
                            rest,
                            true,
                            width - 2 * margin,
                            null
                        )
                        .coerceAtLeast(1)

                if (
                    n < rest.length
                ) {

                    val space =
                        rest
                            .substring(
                                0,
                                n
                            )
                            .lastIndexOf(
                                ' '
                            )

                    if (
                        space > 0
                    ) {

                        n =
                            space
                    }
                }

                wrapped +=
                    rest
                        .substring(
                            0,
                            n
                        )
                        .trimEnd()

                rest =
                    rest
                        .substring(n)
                        .trimStart()
            }
        }

        val result =
            mutableListOf<android.graphics.Bitmap>()

        var current =
            mutableListOf<String>()

        fun finish() {

            if (
                current.isEmpty()
            ) {
                return
            }

            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    width,
                    height,
                    android.graphics.Bitmap.Config.ARGB_8888
                )

            val canvas =
                android.graphics.Canvas(
                    bitmap
                )

            canvas.drawColor(
                android.graphics.Color.WHITE
            )

            var y =
                margin + paint.textSize

            current.forEach { line ->

                if (
                    line.isNotEmpty()
                ) {

                    canvas.drawText(
                        line,
                        margin,
                        y,
                        paint
                    )
                }

                y +=
                    lineHeight
            }

            result +=
                bitmap

            current =
                mutableListOf()
        }

        wrapped.forEach { line ->

            if (
                current.size >= maxLines
            ) {

                finish()
            }

            current +=
                line
        }

        finish()

        return result
    }
}
