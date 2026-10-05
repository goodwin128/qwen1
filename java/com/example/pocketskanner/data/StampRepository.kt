package com.example.pocketskanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.pocketskanner.model.StampItem
import com.example.pocketskanner.model.StampType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class StampRepository(
    private val context: Context
) {

    private val rootDirectory: File by lazy {

        File(
            context.filesDir,
            "stamp_library"
        ).apply {

            if (!exists()) {
                mkdirs()
            }
        }
    }

    private val databaseFile: File
        get() =
            File(
                rootDirectory,
                "stamps.json"
            )

    fun getAll(): List<StampItem> {

        if (!databaseFile.exists()) {
            return emptyList()
        }

        return try {

            val jsonText =
                databaseFile.readText()

            if (jsonText.isBlank()) {
                return emptyList()
            }

            val array =
                JSONArray(jsonText)

            val result =
                mutableListOf<StampItem>()

            for (
                index in
                0 until array.length()
            ) {

                val json =
                    array.getJSONObject(index)

                result += StampItem(

                    id =
                        json.getLong("id"),

                    name =
                        json.getString("name"),

                    type =
                        StampType.valueOf(
                            json.getString("type")
                        ),

                    imagePath =
                        json.getString(
                            "imagePath"
                        ),

                    widthMm = json.optDouble("widthMm", 40.0).toFloat(),

                    secondImagePath =
                        json.optString(
                            "secondImagePath",
                            null
                        ),

                    secondOffsetX =
                        json.optDouble(
                            "secondOffsetX",
                            0.0
                        ).toFloat(),

                    secondOffsetY =
                        json.optDouble(
                            "secondOffsetY",
                            0.0
                        ).toFloat(),

                    secondScale =
                        json.optDouble(
                            "secondScale",
                            1.0
                        ).toFloat()
                )
            }

            result

        } catch (
            _: Exception
        ) {

            emptyList()
        }
    }

    fun saveBitmap(
        bitmap: Bitmap,
        name: String,
        type: StampType = StampType.SIGNATURE,
        widthMm: Float = 40f
    ): StampItem {

        val id =
            System.currentTimeMillis()

        val file =
            createImageFile(id)

        FileOutputStream(file).use {

            bitmap.compress(
                Bitmap.CompressFormat.PNG,
                100,
                it
            )
        }

        val item =
            StampItem(
                id = id,
                name = name,
                type = type,
                imagePath =
                    file.absolutePath,
                widthMm = widthMm.coerceAtLeast(0.1f)
            )

        add(item)

        return item
    }

    fun add(
        item: StampItem
    ) {

        val current =
            getAll().toMutableList()

        current.removeAll {
            it.id == item.id
        }

        current += item

        saveAll(current)
    }

    fun delete(
        id: Long
    ) {

        val item =
            getAll()
                .find {
                    it.id == id
                }

        if (item != null) {

            try {
                File(
                    item.imagePath
                ).delete()
            } catch (_: Exception) {
            }

            item.secondImagePath?.let {

                try {
                    File(it).delete()
                } catch (_: Exception) {
                }
            }
        }

        val current =
            getAll()
                .filterNot {
                    it.id == id
                }

        saveAll(current)
    }

    fun rename(
        id: Long,
        newName: String
    ) {

        val current =
            getAll()
                .map {

                    if (it.id == id) {
                        it.copy(
                            name = newName
                        )
                    } else {
                        it
                    }
                }

        saveAll(current)
    }

    private fun saveAll(
        items: List<StampItem>
    ) {

        val array =
            JSONArray()

        items.forEach { item ->

            val json =
                JSONObject()

            json.put(
                "id",
                item.id
            )

            json.put(
                "name",
                item.name
            )

            json.put(
                "type",
                item.type.name
            )

            json.put(
                "imagePath",
                item.imagePath
            )

            json.put("widthMm", item.widthMm)

            if (
                item.secondImagePath != null
            ) {

                json.put(
                    "secondImagePath",
                    item.secondImagePath
                )
            }

            json.put(
                "secondOffsetX",
                item.secondOffsetX
            )

            json.put(
                "secondOffsetY",
                item.secondOffsetY
            )

            json.put(
                "secondScale",
                item.secondScale
            )

            array.put(json)
        }

        databaseFile.writeText(
            array.toString(2)
        )
    }

    fun createImageFile(
        id: Long
    ): File {

        return File(
            rootDirectory,
            "stamp_${id}.png"
        )
    }

    fun createSecondImageFile(
        id: Long
    ): File {

        return File(
            rootDirectory,
            "stamp_${id}_second.png"
        )
    }

    fun loadBitmap(item: StampItem): Bitmap? {
        return try {
            BitmapFactory.decodeFile(item.imagePath)
        } catch (_: Exception) {
            null
        }
    }

    fun getLibraryDirectory(): File {
        return rootDirectory
    }
}
