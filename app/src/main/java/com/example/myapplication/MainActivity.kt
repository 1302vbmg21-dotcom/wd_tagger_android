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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class MainActivity : AppCompatActivity() {
    private lateinit var btnSelectImage: Button
    private lateinit var imagePreview: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvRating: TextView
    private lateinit var tvGeneralTags: TextView
    private lateinit var tvCharacterTags: TextView

    private var tagger: WD14Tagger? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            imagePreview.setImageURI(it)
            processImage(it)
        } ?: Toast.makeText(this, "Изображение не выбрано", Toast.LENGTH_SHORT).show()
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

        // Инициализируем теггер в фоне
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    tagger = WD14Tagger(this@MainActivity)
                    tagger?.initialize()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Модель загружена", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
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
            val result = withContext(Dispatchers.IO) {
                try {
                    tagger?.predict(uri)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            progressBar.visibility = ProgressBar.GONE

            if (result != null) {
                displayResults(result)
            } else {
                Toast.makeText(this@MainActivity, "Ошибка обработки изображения", Toast.LENGTH_SHORT).show()
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