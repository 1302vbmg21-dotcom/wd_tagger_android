package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.documentfile.provider.DocumentFile
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.math.BigDecimal
import java.math.RoundingMode
import java.io.BufferedReader

class MainActivity : AppCompatActivity() {
    private data class BatchFile(val file: DocumentFile, val relativePath: String)
    companion object {
        private const val PREFS_NAME = "wd14_prefs"
        private const val KEY_RESOURCES_URI = "resources_uri"
        // 1x1 png base64 (тестовое изображение-заглушка для бенчмарка старта).
        private const val STARTUP_TEST_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7Zx1QAAAAASUVORK5CYII="
    }
    private lateinit var btnSelectImage: Button
    private lateinit var imagePreview: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvRating: TextView
    private lateinit var tvGeneralTags: TextView
    private lateinit var tvCharacterTags: TextView

    private var tagger: WD14Tagger? = null
    private var resourcesUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            imagePreview.setImageURI(it)
            processImage(it)
        } ?: Toast.makeText(this, "Изображение не выбрано", Toast.LENGTH_SHORT).show()
    }

    private val pickResourcesFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            Toast.makeText(this, "Папка с ресурсами не выбрана", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_RESOURCES_URI, uri.toString())
            .apply()
        resourcesUri = uri
        initializeTagger()
    }

    private val pickBatchFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) runBatchMode(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSelectImage = findViewById(R.id.btnSelectImage)
        imagePreview = findViewById(R.id.imagePreview)
        progressBar = findViewById(R.id.progressBar)
        tvRating = findViewById(R.id.tvRating)
        tvGeneralTags = findViewById(R.id.tvGeneralTags)
        tvCharacterTags = findViewById(R.id.tvCharacterTags)

        btnSelectImage.setOnClickListener {
            checkPermissionAndPickImage()
        }
        btnSelectImage.setOnLongClickListener {
            pickBatchFolderLauncher.launch(null)
            true
        }

        resourcesUri = loadSavedResourcesUri()
        if (resourcesUri == null) {
            Toast.makeText(this, "Выберите папку с model.onnx и selected_tags.csv", Toast.LENGTH_LONG).show()
            pickResourcesFolderLauncher.launch(null)
        } else {
            initializeTagger()
        }
    }

    private fun runBatchMode(batchRootUri: Uri) {
        val modelName = "WD14 moat tagger v2"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(this@MainActivity, batchRootUri) ?: return@withContext
                val allFiles = mutableListOf<BatchFile>()
                collectImagesRecursive(root, "", allFiles)
                var processed = 0
                val ratingOut = linkedMapOf(
                    "general" to mutableListOf<Double>(),
                    "sensitive" to mutableListOf<Double>(),
                    "questionable" to mutableListOf<Double>(),
                    "explicit" to mutableListOf<Double>()
                )
                val tagOut = linkedMapOf<String, MutableList<Double>>()
                val queryOut = linkedMapOf<String, List<Any>>()
                val alreadyDonePaths = mutableSetOf<String>()
                val existingTailToId = mutableMapOf<String, Int>()
                loadExistingDb(root, ratingOut, tagOut, queryOut, alreadyDonePaths, existingTailToId)
                var nextImageId = (queryOut.values.mapNotNull { (it.getOrNull(1) as? Number)?.toInt() }.maxOrNull() ?: -1) + 1
                val newFilesTotal = allFiles.count { bf ->
                    val tail = tailFromAuthorPath(bf.relativePath.replace("/", "\\"))
                    !existingTailToId.containsKey(tail)
                }
                var processedNew = 0

                for (bf in allFiles) {
                    val key = sha256((bf.file.uri.toString() + modelName).toByteArray()) + modelName
                    val fakePath = "X:\\" + bf.relativePath.replace("/", "\\")
                    val tail = tailFromAuthorPath(fakePath.removePrefix("X:\\"))
                    val existingId = existingTailToId[tail]
                    if (existingId != null) {
                        queryOut[key] = listOf(fakePath, existingId)
                        continue
                    }
                    val raw = tagger?.predictRaw(bf.file.uri) ?: continue
                    val imageId = nextImageId++
                    queryOut[key] = listOf(fakePath, imageId)
                    alreadyDonePaths.add(fakePath)

                    raw.rating.forEach { (name, score) ->
                        ratingOut[name]?.apply {
                            add(packImageScore(imageId, score.toDouble()))
                        }
                    }
                    raw.tags.forEach { (tag, score) ->
                        if (score < 0.005f) return@forEach
                        val arr = tagOut.getOrPut(tag) { mutableListOf() }
                        arr.add(packImageScore(imageId, score.toDouble()))
                    }
                    processed++
                    processedNew++
                    withContext(Dispatchers.Main) {
                        btnSelectImage.text = "Batch: $processedNew/$newFilesTotal (${allFiles.size})"
                    }
                    if (processed % 500 == 0) {
                        writeDbWithBackup(root, ratingOut, tagOut, queryOut)
                    }
                }
                writeDbWithBackup(root, ratingOut, tagOut, queryOut)
                withContext(Dispatchers.Main) {
                    btnSelectImage.text = "Выбрать изображение"
                    Toast.makeText(this@MainActivity, "Batch завершен: $processed файлов", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun collectImagesRecursive(dir: DocumentFile, rel: String, out: MutableList<BatchFile>) {
        dir.listFiles().forEach {
            val name = it.name ?: return@forEach
            val nextRel = if (rel.isEmpty()) name else "$rel/$name"
            if (it.isDirectory) collectImagesRecursive(it, nextRel, out)
            else if (it.isFile && (name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".png") || name.lowercase().endsWith(".jpeg"))) {
                out.add(BatchFile(it, nextRel))
            }
        }
    }

    private fun packImageScore(imageId: Int, score: Double): Double {
        val truncated = BigDecimal(score).setScale(15, RoundingMode.DOWN)
        return BigDecimal(imageId).add(truncated).toDouble()
    }

    private fun writeJsonChunk(root: DocumentFile, json: String, name: String) {
        val f = root.createFile("application/json", name) ?: return
        contentResolver.openOutputStream(f.uri)?.bufferedWriter()?.use { it.write(json) }
    }

    private fun writeDbWithBackup(
        root: DocumentFile,
        ratingOut: LinkedHashMap<String, MutableList<Double>>,
        tagOut: LinkedHashMap<String, MutableList<Double>>,
        queryOut: LinkedHashMap<String, List<Any>>
    ) {
        root.findFile("db.bak")?.delete()
        root.findFile("db.json")?.let { old ->
            val bak = root.createFile("application/json", "db.bak")
            if (bak != null) {
                contentResolver.openInputStream(old.uri)?.use { i ->
                    contentResolver.openOutputStream(bak.uri)?.use { o -> i.copyTo(o) }
                }
            }
            old.delete()
        }
        val finalJson = linkedMapOf("rating" to ratingOut, "tag" to tagOut, "query" to queryOut)
        val gson = GsonBuilder().disableHtmlEscaping().create()
        writeJsonChunk(root, gson.toJson(finalJson), "db.json")
    }

    private fun loadExistingDb(
        root: DocumentFile,
        ratingOut: LinkedHashMap<String, MutableList<Double>>,
        tagOut: LinkedHashMap<String, MutableList<Double>>,
        queryOut: LinkedHashMap<String, List<Any>>,
        donePaths: MutableSet<String>,
        tailToId: MutableMap<String, Int>
    ) {
        val db = root.findFile("db.json") ?: return
        val text = contentResolver.openInputStream(db.uri)?.bufferedReader()?.use(BufferedReader::readText) ?: return
        val map = GsonBuilder().create().fromJson(text, Map::class.java) as? Map<*, *> ?: return
        val rating = map["rating"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val tag = map["tag"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val query = map["query"] as? Map<*, *> ?: emptyMap<Any, Any>()
        rating.forEach { (k, v) -> ratingOut[k.toString()] = (v as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.toMutableList() ?: mutableListOf() }
        tag.forEach { (k, v) -> tagOut[k.toString()] = (v as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.toMutableList() ?: mutableListOf() }
        query.forEach { (k, v) ->
            val arr = v as? List<*> ?: return@forEach
            if (arr.size >= 2) {
                queryOut[k.toString()] = listOf(arr[0].toString(), (arr[1] as Number).toInt())
                donePaths.add(arr[0].toString())
                val p = arr[0].toString().removePrefix("X:\\").removePrefix("K:\\")
                tailToId[tailFromAuthorPath(p)] = (arr[1] as Number).toInt()
            }
        }
    }

    private fun tailFromAuthorPath(path: String): String {
        val parts = path.split("\\").filter { it.isNotBlank() }
        return if (parts.size >= 2) parts.takeLast(2).joinToString("\\") else path
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun loadSavedResourcesUri(): Uri? {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_RESOURCES_URI, null) ?: return null
        val uri = Uri.parse(raw)
        val root = DocumentFile.fromTreeUri(this, uri)
        return if (root != null && root.exists()) uri else null
    }

    private fun initializeTagger() {
        progressBar.visibility = ProgressBar.VISIBLE
        btnSelectImage.isEnabled = false
        btnSelectImage.text = "Загрузка модели..."
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    tagger = WD14Tagger(this@MainActivity, resourcesUri)
                    tagger?.initialize()
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = ProgressBar.GONE
                        btnSelectImage.isEnabled = true
                        btnSelectImage.text = "Выбрать изображение"
                        Toast.makeText(this@MainActivity, "Модель загружена", Toast.LENGTH_SHORT).show()
                        runStartupBenchmark()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = ProgressBar.GONE
                        btnSelectImage.isEnabled = true
                        btnSelectImage.text = "Выбрать изображение"
                        Toast.makeText(this@MainActivity, "Ошибка загрузки модели: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun runStartupBenchmark() {
        val bytes = Base64.decode(STARTUP_TEST_BASE64, Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        imagePreview.setImageBitmap(bmp)
        lifecycleScope.launch {
            val runtime = Runtime.getRuntime()
            runtime.gc()
            val memBefore = runtime.totalMemory() - runtime.freeMemory()
            val t0 = System.nanoTime()
            val result = withContext(Dispatchers.IO) { tagger?.predictBitmap(bmp) }
            val dtMs = (System.nanoTime() - t0) / 1_000_000
            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val deltaMb = (memAfter - memBefore) / (1024.0 * 1024.0)
            if (result != null) {
                displayResults(result)
                tvCharacterTags.append("\nBenchmark: ${dtMs} ms, ΔRAM: ${"%.2f".format(deltaMb)} MB")
            }
        }
    }

    private fun checkPermissionAndPickImage() {
        when {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED) {
                    pickImageLauncher.launch("image/*")
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                }
            }
            else -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                    pickImageLauncher.launch("image/*")
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) pickImageLauncher.launch("image/*")
        else Toast.makeText(this, "Нет разрешения на чтение галереи", Toast.LENGTH_SHORT).show()
    }

    private fun processImage(uri: Uri) {
        if (tagger == null) {
            Toast.makeText(this, "Модель ещё не загружена", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            progressBar.visibility = ProgressBar.VISIBLE
            var predictionError: Exception? = null
            val result = withContext(Dispatchers.IO) {
                try {
                    tagger?.predict(uri)
                } catch (e: Exception) {
                    predictionError = e
                    null
                }
            }
            progressBar.visibility = ProgressBar.GONE

            if (result != null) {
                displayResults(result)
            } else {
                val details = predictionError?.message?.take(120) ?: "неизвестная причина"
                Toast.makeText(this@MainActivity, "Ошибка обработки изображения: $details", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayResults(result: PredictionResult) {
        // Rating
        val ratingText = result.rating.entries.joinToString(", ") {
            "${it.key}: ${String.format("%.3f", it.value)}"
        }
        tvRating.text = "Rating: $ratingText"

        // General tags (топ-30)
        val generalText = result.general.entries
            .sortedByDescending { it.value }
            .take(30)
            .joinToString(", ") {
                "${it.key} (${String.format("%.2f", it.value)})"
            }
        tvGeneralTags.text = if (generalText.isNotEmpty()) generalText else "—"

        // Character tags
        val characterText = result.character.entries
            .sortedByDescending { it.value }
            .joinToString(", ") {
                "${it.key} (${String.format("%.2f", it.value)})"
            }
        tvCharacterTags.text = if (characterText.isNotEmpty()) characterText else "—"
    }

    override fun onDestroy() {
        super.onDestroy()
        tagger?.close()
    }
}
