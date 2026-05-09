package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Base64
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {
    private data class BatchFile(val file: DocumentFile, val relativePath: String)

    companion object {
        private const val PREFS_NAME = "wd14_prefs"
        private const val KEY_RESOURCES_URI = "resources_uri"
        private const val STARTUP_TEST_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7Zx1QAAAAASUVORK5CYII="
    }

    private lateinit var btnSelectImage: Button
    private lateinit var imagePreview: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvRating: TextView
    private lateinit var tvGeneralTags: TextView
    private lateinit var tvCharacterTags: TextView

    private var partialBatchSize = 500
    private var virtualDrive = "X"
    private var probRound = 4

    private var tagger: WD14Tagger? = null
    private var resourcesUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            imagePreview.setImageURI(it)
            processImage(it)
        } ?: Toast.makeText(this, "Изображение не выбрано", Toast.LENGTH_SHORT).show()
    }

    private val pickResourcesFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            Toast.makeText(this, "Папка с ресурсами не выбрана", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_RESOURCES_URI, uri.toString()).apply()
        resourcesUri = uri
        initializeTagger()
    }

    private val pickBatchFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
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

        btnSelectImage.setOnClickListener { checkPermissionAndPickImage() }
        btnSelectImage.setOnLongClickListener { pickBatchFolderLauncher.launch(null); true }

        resourcesUri = loadSavedResourcesUri()
        if (resourcesUri == null) {
            Toast.makeText(this, "Выберите папку с model.onnx и selected_tags.csv", Toast.LENGTH_LONG).show()
            pickResourcesFolderLauncher.launch(null)
        } else {
            initializeTagger()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "⋮").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            showConfigDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showConfigDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        val inputBatch = EditText(this).apply { hint = "Partial batch size"; setText(partialBatchSize.toString()) }
        val inputDrive = EditText(this).apply { hint = "Virtual drive"; setText(virtualDrive) }
        val inputRound = EditText(this).apply { hint = "Prob.round"; setText(probRound.toString()) }
        layout.addView(inputBatch)
        layout.addView(inputDrive)
        layout.addView(inputRound)

        AlertDialog.Builder(this)
            .setTitle("Config menu")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                partialBatchSize = inputBatch.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: partialBatchSize
                virtualDrive = inputDrive.text.toString().ifBlank { "X" }.first().uppercase()
                probRound = inputRound.text.toString().toIntOrNull()?.coerceIn(1, 6) ?: probRound
            }
            .setNeutralButton("Default") { _, _ ->
                partialBatchSize = 500
                virtualDrive = "X"
                probRound = 4
            }
            .show()
    }

    private fun runBatchMode(batchRootUri: Uri) {
        val modelName = "WD14 moat tagger v2"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(this@MainActivity, batchRootUri) ?: return@withContext
                val allFiles = mutableListOf<BatchFile>()
                collectImagesRecursive(root, "", allFiles)

                val existingTailToId = mutableMapOf<String, Int>()
                val maxId = loadExistingDbIndex(root, existingTailToId)
                var nextImageId = maxId + 1
                val newFilesTotal = allFiles.count { !existingTailToId.containsKey(tailFromAuthorPath(it.relativePath.replace("/", "\\"))) }
                var processedNew = 0
                var processed = 0

                val chunkRating = linkedMapOf(
                    "general" to mutableListOf<Double>(),
                    "sensitive" to mutableListOf<Double>(),
                    "questionable" to mutableListOf<Double>(),
                    "explicit" to mutableListOf<Double>()
                )
                val chunkTag = linkedMapOf<String, MutableList<Double>>()
                val chunkQuery = linkedMapOf<String, List<Any>>()

                for (bf in allFiles) {
                    val key = sha256((bf.file.uri.toString() + modelName).toByteArray()) + modelName
                    val fakePath = "$virtualDrive:\\" + bf.relativePath.replace("/", "\\")
                    val tail = tailFromAuthorPath(fakePath.removePrefix("$virtualDrive:\\"))
                    val existingId = existingTailToId[tail]

                    if (existingId != null) {
                        chunkQuery[key] = listOf(fakePath, existingId)
                    } else {
                        val raw = tagger?.predictRaw(bf.file.uri) ?: continue
                        val imageId = nextImageId++
                        chunkQuery[key] = listOf(fakePath, imageId)
                        raw.rating.forEach { (name, score) -> chunkRating[name]?.add(packImageScore(imageId, score.toDouble())) }
                        raw.tags.forEach { (tag, score) -> if (score >= 0.005f) chunkTag.getOrPut(tag) { mutableListOf() }.add(packImageScore(imageId, score.toDouble())) }
                        processedNew++
                        processed++
                    }

                    withContext(Dispatchers.Main) {
                        btnSelectImage.text = "Batch: $processedNew/$newFilesTotal (${allFiles.size})"
                    }

                    if (processed > 0 && processed % partialBatchSize == 0) {
                        tagger?.close()
                        mergeDbChunkWithBackup(root, chunkRating, chunkTag, chunkQuery)
                        clearChunk(chunkRating, chunkTag, chunkQuery)
                        tagger = WD14Tagger(this@MainActivity, resourcesUri)
                        tagger?.initialize()
                    }
                }

                tagger?.close()
                mergeDbChunkWithBackup(root, chunkRating, chunkTag, chunkQuery)
                clearChunk(chunkRating, chunkTag, chunkQuery)
                tagger = WD14Tagger(this@MainActivity, resourcesUri)
                tagger?.initialize()

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
            else if (it.isFile && (name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") || name.lowercase().endsWith(".png"))) {
                out.add(BatchFile(it, nextRel))
            }
        }
    }

    private fun packImageScore(imageId: Int, score: Double): Double {
        val rounded = BigDecimal(score).setScale(probRound, RoundingMode.HALF_UP)
        return BigDecimal(imageId).add(rounded).toDouble()
    }

    private fun normalizePacked(value: Double): Double {
        val idPart = kotlin.math.floor(value).toInt()
        val frac = value - idPart
        return idPart + BigDecimal(frac).setScale(probRound, RoundingMode.HALF_UP).toDouble()
    }

    private fun loadExistingDbIndex(root: DocumentFile, tailToId: MutableMap<String, Int>): Int {
        val db = root.findFile("db.json") ?: return -1
        var maxId = -1
        contentResolver.openInputStream(db.uri)?.use { input ->
            JsonReader(InputStreamReader(input)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "query" -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                reader.nextName()
                                reader.beginArray()
                                val path = if (reader.hasNext()) reader.nextString() else ""
                                val id = if (reader.hasNext()) reader.nextInt() else -1
                                while (reader.hasNext()) reader.skipValue()
                                reader.endArray()
                                if (id >= 0) {
                                    maxId = maxOf(maxId, id)
                                    val p = path.removePrefix("X:\\").removePrefix("K:\\")
                                    tailToId[tailFromAuthorPath(p)] = id
                                }
                            }
                            reader.endObject()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        return maxId
    }

    private fun mergeDbChunkWithBackup(
        root: DocumentFile,
        chunkRating: LinkedHashMap<String, MutableList<Double>>,
        chunkTag: LinkedHashMap<String, MutableList<Double>>,
        chunkQuery: LinkedHashMap<String, List<Any>>
    ) {
        if (chunkRating.values.all { it.isEmpty() } && chunkTag.isEmpty() && chunkQuery.isEmpty()) return

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

        val outFile = root.createFile("application/json", "db.json") ?: return
        contentResolver.openOutputStream(outFile.uri)?.use { os ->
            JsonWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { writer ->
                writer.beginObject()
                writer.name("rating")
                writer.beginObject()
                writeMergedNumberSection(root, "rating", writer, chunkRating)
                writer.endObject()

                writer.name("tag")
                writer.beginObject()
                writeMergedNumberSection(root, "tag", writer, chunkTag)
                writer.endObject()

                writer.name("query")
                writer.beginObject()
                writeMergedQuerySection(root, writer, chunkQuery)
                writer.endObject()
                writer.endObject()
            }
        }
    }

    private fun writeMergedNumberSection(
        root: DocumentFile,
        section: String,
        writer: JsonWriter,
        chunk: LinkedHashMap<String, MutableList<Double>>
    ) {
        val consumed = mutableSetOf<String>()
        root.findFile("db.bak")?.let { bak ->
            contentResolver.openInputStream(bak.uri)?.use { input ->
                JsonReader(InputStreamReader(input)).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == section) {
                            r.beginObject()
                            while (r.hasNext()) {
                                val name = r.nextName()
                                consumed.add(name)
                                writer.name(name)
                                writer.beginArray()
                                r.beginArray()
                                while (r.hasNext()) writer.value(normalizePacked(r.nextDouble()))
                                r.endArray()
                                chunk[name]?.forEach { writer.value(it) }
                                writer.endArray()
                            }
                            r.endObject()
                        } else r.skipValue()
                    }
                    r.endObject()
                }
            }
        }
        chunk.forEach { (name, arr) ->
            if (name !in consumed && arr.isNotEmpty()) {
                writer.name(name)
                writer.beginArray()
                arr.forEach { writer.value(it) }
                writer.endArray()
            }
        }
    }

    private fun writeMergedQuerySection(root: DocumentFile, writer: JsonWriter, chunkQuery: LinkedHashMap<String, List<Any>>) {
        val consumed = mutableSetOf<String>()
        root.findFile("db.bak")?.let { bak ->
            contentResolver.openInputStream(bak.uri)?.use { input ->
                JsonReader(InputStreamReader(input)).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "query") {
                            r.beginObject()
                            while (r.hasNext()) {
                                val key = r.nextName()
                                consumed.add(key)
                                if (chunkQuery.containsKey(key)) {
                                    r.skipValue()
                                    val v = chunkQuery.getValue(key)
                                    writer.name(key)
                                    writer.beginArray()
                                    writer.value(v[0].toString())
                                    writer.value((v[1] as Number).toInt())
                                    writer.endArray()
                                } else {
                                    writer.name(key)
                                    writer.beginArray()
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        when (r.peek()) {
                                            JsonToken.STRING -> writer.value(r.nextString())
                                            JsonToken.NUMBER -> writer.value(r.nextInt())
                                            else -> r.skipValue()
                                        }
                                    }
                                    r.endArray()
                                    writer.endArray()
                                }
                            }
                            r.endObject()
                        } else r.skipValue()
                    }
                    r.endObject()
                }
            }
        }

        chunkQuery.forEach { (k, v) ->
            if (k !in consumed) {
                writer.name(k)
                writer.beginArray()
                writer.value(v[0].toString())
                writer.value((v[1] as Number).toInt())
                writer.endArray()
            }
        }
    }

    private fun clearChunk(
        chunkRating: LinkedHashMap<String, MutableList<Double>>,
        chunkTag: LinkedHashMap<String, MutableList<Double>>,
        chunkQuery: LinkedHashMap<String, List<Any>>
    ) {
        chunkRating.values.forEach { it.clear() }
        chunkTag.clear()
        chunkQuery.clear()
    }

    private fun tailFromAuthorPath(path: String): String {
        val parts = path.split("\\").filter { it.isNotBlank() }
        return if (parts.size >= 2) parts.takeLast(2).joinToString("\\") else path
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun loadSavedResourcesUri(): Uri? {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_RESOURCES_URI, null) ?: return null
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
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                    pickImageLauncher.launch("image/*")
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                }
            }
            else -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    pickImageLauncher.launch("image/*")
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
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
        val ratingText = result.rating.entries.joinToString(", ") { "${it.key}: ${String.format("%.3f", it.value)}" }
        tvRating.text = "Rating: $ratingText"

        val generalText = result.general.entries.sortedByDescending { it.value }.take(30)
            .joinToString(", ") { "${it.key} (${String.format("%.2f", it.value)})" }
        tvGeneralTags.text = if (generalText.isNotEmpty()) generalText else "—"

        val characterText = result.character.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} (${String.format("%.2f", it.value)})" }
        tvCharacterTags.text = if (characterText.isNotEmpty()) characterText else "—"
    }

    override fun onDestroy() {
        super.onDestroy()
        tagger?.close()
    }
}
