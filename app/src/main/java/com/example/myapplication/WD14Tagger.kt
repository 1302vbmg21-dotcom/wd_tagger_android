package com.example.myapplication

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

data class TagInfo(val id: Long, val name: String, val category: Int, val count: Long)
data class PredictionResult(
    val rating: Map<String, Float>,
    val general: Map<String, Float>,
    val character: Map<String, Float>
)

class WD14Tagger(private val context: Context, private val resourcesTreeUri: Uri? = null) {
    private lateinit var session: OrtSession
    private lateinit var env: OrtEnvironment
    private lateinit var tagsList: List<TagInfo>
    private val targetSize = 448

    private lateinit var ratingIndices: List<Int>
    private lateinit var generalIndices: List<Int>
    private lateinit var characterIndices: List<Int>
    private lateinit var inputName: String

    suspend fun initialize() = withContext(Dispatchers.IO) {
        env = OrtEnvironment.getEnvironment()
        val modelFile = materializeResourceToFile("model.onnx")
        session = env.createSession(modelFile.absolutePath)
        inputName = session.inputNames.firstOrNull()
            ?: throw IllegalStateException("У модели отсутствуют входные тензоры")
        tagsList = loadTagsFromCsv(openResourceStream("selected_tags.csv"))

        ratingIndices = tagsList.indices.filter { tagsList[it].category == 9 }
        generalIndices = tagsList.indices.filter { tagsList[it].category == 0 }
        characterIndices = tagsList.indices.filter { tagsList[it].category == 4 }
    }

    // Keep only one resource-opening helper to avoid merge-time duplicate overloads.
    private fun openResourceStream(fileName: String): InputStream {
        val treeUri = resourcesTreeUri
        if (treeUri != null) {
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("Не удалось открыть выбранную папку с ресурсами")
            val file = pickedDir.findFile(fileName)
                ?: throw IllegalStateException("В выбранной папке отсутствует файл: $fileName")
            return context.contentResolver.openInputStream(file.uri)
                ?: throw IllegalStateException("Не удалось прочитать файл: $fileName")
        }
        return context.assets.open(fileName)
    }

    private fun materializeResourceToFile(fileName: String): File {
        val outFile = File(context.filesDir, fileName)
        if (outFile.exists() && outFile.length() > 0L) return outFile

        openResourceStream(fileName).use { input: InputStream ->
            outFile.outputStream().use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        }
        return outFile
    }

    private fun loadTagsFromCsv(input: InputStream): List<TagInfo> {
        val result = mutableListOf<TagInfo>()
        input.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(",")
                if (parts.size >= 4) {
                    val id = parts[0].toLongOrNull()
                    val category = parts[2].toIntOrNull()
                    val count = parts[3].toLongOrNull()
                    // Пропускаем заголовки и повреждённые строки CSV.
                    if (id != null && category != null && count != null) {
                        result.add(TagInfo(
                            id = id,
                            name = parts[1],
                            category = category,
                            count = count
                        ))
                    }
                }
            }
        }
        return result
    }

    suspend fun predict(uri: Uri): PredictionResult? = withContext(Dispatchers.IO) {
            val bitmap = loadBitmapFromUri(uri) ?: return@withContext null
            val inputTensor = preprocessBitmap(bitmap)
            val output = session.run(mapOf(inputName to inputTensor))

            // Получаем выходные данные
            val probsArray = output[0].value
            val probs = when (probsArray) {
                is FloatArray -> probsArray
                is Array<*> -> (probsArray[0] as? FloatArray) ?: return@withContext null
                else -> return@withContext null
            }

            output.close()
            extractTags(probs)
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }

    private fun preprocessBitmap(original: Bitmap): OnnxTensor {
        val maxDim = maxOf(original.width, original.height)
        val padded = Bitmap.createBitmap(maxDim, maxDim, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(padded)
        canvas.drawColor(android.graphics.Color.WHITE)
        val left = (maxDim - original.width) / 2f
        val top = (maxDim - original.height) / 2f
        canvas.drawBitmap(original, left, top, null)

        val resized = Bitmap.createScaledBitmap(padded, targetSize, targetSize, true)
        padded.recycle()

        val pixels = FloatArray(1 * targetSize * targetSize * 3)
        for (y in 0 until targetSize) {
            for (x in 0 until targetSize) {
                val pixel = resized.getPixel(x, y)
                val r = (pixel shr 16 and 0xFF).toFloat()
                val g = (pixel shr 8 and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()
                val idx = (y * targetSize + x) * 3
                pixels[idx] = b
                pixels[idx + 1] = g
                pixels[idx + 2] = r
            }
        }
        resized.recycle()
        val shape = longArrayOf(1, targetSize.toLong(), targetSize.toLong(), 3)
        return OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(pixels), shape)
    }

    private fun extractTags(probs: FloatArray): PredictionResult {
        val usableSize = minOf(probs.size, tagsList.size)
        if (usableSize == 0) {
            return PredictionResult(emptyMap(), emptyMap(), emptyMap())
        }

        // Для wd-swinv2-tagger-v3 выходы уже в вероятностях [0..1].
        // Повторный sigmoid искажает распределение (например 0.97 -> 0.72).
        val probabilities = probs

        val rating = ratingIndices.filter { it < usableSize }.associate { i ->
            tagsList[i].name to probabilities[i]
        }

        val generalThreshold = 0.35f
        val general = generalIndices.filter { it < usableSize }.mapNotNull { i ->
            val prob = probabilities[i]
            if (prob > generalThreshold) tagsList[i].name to prob else null
        }.toMap()

        val characterThreshold = 0.85f
        val character = characterIndices.filter { it < usableSize }.mapNotNull { i ->
            val prob = probabilities[i]
            if (prob > characterThreshold) tagsList[i].name to prob else null
        }.toMap()

        return PredictionResult(rating, general, character)
    }

    fun close() {
        try {
            session.close()
            env.close()
        } catch (e: Exception) { }
    }
}
