package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "wd14_prefs"
        private const val KEY_RESOURCES_URI = "resources_uri"
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
                val allFiles = mutableListOf<DocumentFile>()
                collectImagesRecursive(root, allFiles)
                var processed = 0
                var chunk = mutableMapOf<String, Any>()
                var chunkNo = 0
                val gson = GsonBuilder().setPrettyPrinting().create()

                for (file in allFiles) {
                    val raw = tagger?.predictRaw(file.uri) ?: continue
                    val key = sha256((file.uri.toString() + modelName).toByteArray()) + modelName
                    val fakePath = "X:\\\\seldir\\\\subdir1\\\\" + (file.name ?: "unknown")
                    chunk[key] = mapOf(
                        "path" to fakePath,
                        "rating" to raw.rating,
                        "tag" to raw.tags
                    )
                    processed++
                    withContext(Dispatchers.Main) {
                        btnSelectImage.text = "Batch: $processed/${allFiles.size}"
                    }
                    if (processed % 100 == 0) {
                        writeJsonChunk(root, gson.toJson(chunk), "batch_${chunkNo}.json")
                        chunkNo++
                        chunk = mutableMapOf()
                    }
                }
                if (chunk.isNotEmpty()) writeJsonChunk(root, gson.toJson(chunk), "batch_${chunkNo}.json")
                withContext(Dispatchers.Main) {
                    btnSelectImage.text = "Выбрать изображение"
                    Toast.makeText(this@MainActivity, "Batch завершен: $processed файлов", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun collectImagesRecursive(dir: DocumentFile, out: MutableList<DocumentFile>) {
        dir.listFiles().forEach {
            if (it.isDirectory) collectImagesRecursive(it, out)
            else if (it.isFile && (it.name?.lowercase()?.endsWith(".jpg") == true || it.name?.lowercase()?.endsWith(".png") == true || it.name?.lowercase()?.endsWith(".jpeg") == true)) out.add(it)
        }
    }

    private fun writeJsonChunk(root: DocumentFile, json: String, name: String) {
        val f = root.createFile("application/json", name) ?: return
        contentResolver.openOutputStream(f.uri)?.bufferedWriter()?.use { it.write(json) }
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
