package com.proxdr.sdrconverter

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var imagePreview: ImageView
    private lateinit var sliderHighlight: Slider
    private lateinit var sliderShadow: Slider
    private lateinit var sliderContrast: Slider
    private lateinit var labelHighlight: TextView
    private lateinit var labelShadow: TextView
    private lateinit var labelContrast: TextView
    private lateinit var btnPick: Button
    private lateinit var btnSave: Button
    private lateinit var btnShareWhatsApp: Button

    private var originalFullBitmap: Bitmap? = null
    private var previewBaseBitmap: Bitmap? = null
    private var currentPreviewResult: Bitmap? = null
    private var renderJob: Job? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        imagePreview = findViewById(R.id.imagePreview)
        sliderHighlight = findViewById(R.id.sliderHighlight)
        sliderShadow = findViewById(R.id.sliderShadow)
        sliderContrast = findViewById(R.id.sliderContrast)
        labelHighlight = findViewById(R.id.labelHighlight)
        labelShadow = findViewById(R.id.labelShadow)
        labelContrast = findViewById(R.id.labelContrast)
        btnPick = findViewById(R.id.btnPick)
        btnSave = findViewById(R.id.btnSave)
        btnShareWhatsApp = findViewById(R.id.btnShareWhatsApp)

        val sliderListener = Slider.OnChangeListener { _, _, _ ->
            labelHighlight.text = "Gainmap Highlight Boost (Bright Spots): ${sliderHighlight.value.toInt()}%"
            labelShadow.text = "Shadow Crush (Darken Dark Spots): ${sliderShadow.value.toInt()}%"
            labelContrast.text = "S-Curve Contrast & Pop: ${sliderContrast.value.toInt()}%"
            triggerPreviewUpdate()
        }

        sliderHighlight.addOnChangeListener(sliderListener)
        sliderShadow.addOnChangeListener(sliderListener)
        sliderContrast.addOnChangeListener(sliderListener)

        btnPick.setOnClickListener {
            pickMedia.launch("image/*")
        }

        btnSave.setOnClickListener {
            saveFullResToGallery()
        }

        btnShareWhatsApp.setOnClickListener {
            shareFullResToWhatsApp()
        }

        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            uri?.let { loadUri(it) }
        }
    }

    private fun getParams(): ProXdrProcessor.Params {
        return ProXdrProcessor.Params(
            highlightBoost = sliderHighlight.value / 100f,
            shadowCrush = sliderShadow.value / 100f,
            sCurveContrast = sliderContrast.value / 100f
        )
    }

    private fun loadUri(uri: Uri) {
        lifecycleScope.launch {
            statusText.text = "Decoding ProXDR / Gainmap layer..."
            withContext(Dispatchers.IO) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val full = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
                originalFullBitmap = full

                // Create a fast 1080p-ish preview bitmap while preserving its Gainmap
                val scale = minOf(1.0f, 1080f / maxOf(full.width, full.height))
                val pW = (full.width * scale).toInt().coerceAtLeast(1)
                val pH = (full.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(full, pW, pH, true)
                full.gainmap?.let { gm ->
                    val scaledGmContents = Bitmap.createScaledBitmap(
                        gm.gainmapContents,
                        pW,
                        pH,
                        true
                    )
                    val newGm = android.graphics.Gainmap(scaledGmContents)
                    newGm.ratioMax = gm.ratioMax
                    newGm.ratioMin = gm.ratioMin
                    scaled.gainmap = newGm
                }
                previewBaseBitmap = scaled
            }

            val hasGainmap = originalFullBitmap?.hasGainmap() == true
            statusText.text = if (hasGainmap) {
                "✅ ProXDR / Ultra HDR Gainmap detected! Baking into SDR..."
            } else {
                "ℹ️ No HDR Gainmap found (standard photo). Using Luma SDR booster."
            }
            btnSave.isEnabled = true
            btnShareWhatsApp.isEnabled = true
            triggerPreviewUpdate()
        }
    }

    private fun triggerPreviewUpdate() {
        val base = previewBaseBitmap ?: return
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            delay(40)
            val params = getParams()
            val processed = withContext(Dispatchers.Default) {
                ProXdrProcessor.processToSdr(base, params)
            }
            currentPreviewResult = processed
            imagePreview.setImageBitmap(processed)
        }
    }

    private fun saveFullResToGallery() {
        val full = originalFullBitmap ?: return
        val params = getParams()
        btnSave.isEnabled = false
        statusText.text = "Processing full-resolution SDR image..."

        lifecycleScope.launch {
            val savedUri = withContext(Dispatchers.IO) {
                val result = ProXdrProcessor.processToSdr(full, params)
                val filename = "ProXDR_SDR_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProXDR_SDR")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        result.compress(Bitmap.CompressFormat.JPEG, 98, out)
                    }
                }
                uri
            }
            btnSave.isEnabled = true
            if (savedUri != null) {
                statusText.text = "✅ Saved to Pictures/ProXDR_SDR!"
                Toast.makeText(this@MainActivity, "Saved punchy SDR JPEG!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareFullResToWhatsApp() {
        val full = originalFullBitmap ?: return
        val params = getParams()
        btnShareWhatsApp.isEnabled = false
        statusText.text = "Preparing WhatsApp-optimized SDR image..."

        lifecycleScope.launch {
            val shareUri = withContext(Dispatchers.IO) {
                val result = ProXdrProcessor.processToSdr(full, params)
                // Downscale softly to 2560px max edge so WhatsApp Status doesn't muddy it
                val maxEdge = maxOf(result.width, result.height)
                val finalBmp = if (maxEdge > 2560) {
                    val s = 2560f / maxEdge
                    Bitmap.createScaledBitmap(
                        result,
                        (result.width * s).toInt(),
                        (result.height * s).toInt(),
                        true
                    )
                } else {
                    result
                }

                val sharedDir = File(cacheDir, "shared").apply { mkdirs() }
                val file = File(sharedDir, "whatsapp_sdr_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    finalBmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
                }
                FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    file
                )
            }
            btnShareWhatsApp.isEnabled = true
            statusText.text = "Ready to share!"

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "Share Punchy SDR Photo to WhatsApp"))
        }
    }
}
