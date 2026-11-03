package com.example.img2vid

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var images: List<Img> = emptyList()
    private var job: Job? = null
    private var audioUri: Uri? = null

    private lateinit var tvAudio: TextView
    private lateinit var btnAudio: Button
    private lateinit var btnAudioClear: Button

    private lateinit var tvSelected: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnCancel: Button
    private lateinit var btnFolder: Button
    private lateinit var btnPick: Button

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) loadFolder(uri)
    }
    private val pickImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) loadUris(uris)
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "audio file"
            tvAudio.text = "Music: $name"
            btnAudioClear.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSelected = findViewById(R.id.tvSelected)
        tvStatus = findViewById(R.id.tvStatus)
        progress = findViewById(R.id.progress)
        btnStart = findViewById(R.id.btnStart)
        btnCancel = findViewById(R.id.btnCancel)
        btnFolder = findViewById(R.id.btnFolder)
        btnPick = findViewById(R.id.btnPick)
        tvAudio = findViewById(R.id.tvAudio)
        btnAudio = findViewById(R.id.btnAudio)
        btnAudioClear = findViewById(R.id.btnAudioClear)

        btnAudio.setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        btnAudioClear.setOnClickListener {
            audioUri = null
            tvAudio.text = "No music selected"
            btnAudioClear.visibility = View.GONE
        }

        btnFolder.setOnClickListener { pickFolder.launch(null) }
        btnPick.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        btnStart.setOnClickListener { startConversion() }
        btnCancel.setOnClickListener { job?.cancel() }
    }

    private fun loadFolder(tree: Uri) {
        lifecycleScope.launch {
            tvSelected.text = "Scanning folder…"
            val list = withContext(Dispatchers.IO) {
                DocumentFile.fromTreeUri(this@MainActivity, tree)
                    ?.listFiles()
                    ?.filter { it.isFile && it.type?.startsWith("image/") == true }
                    ?.map { Img(it.uri, it.name ?: "") }
                    .orEmpty()
                    .sortedWith(compareBy(naturalOrder) { it.name })
            }
            setImages(list)
        }
    }

    private fun loadUris(uris: List<Uri>) {
        lifecycleScope.launch {
            tvSelected.text = "Reading selection…"
            val list = withContext(Dispatchers.IO) {
                uris.map { Img(it, DocumentFile.fromSingleUri(this@MainActivity, it)?.name ?: it.toString()) }
                    .sortedWith(compareBy(naturalOrder) { it.name })
            }
            setImages(list)
        }
    }

    private fun setImages(list: List<Img>) {
        images = list
        tvSelected.text = if (list.isEmpty()) "No images found" else "${list.size} images selected (sorted by name)"
    }

    private fun startConversion() {
        if (images.isEmpty()) {
            Toast.makeText(this, "Choose images first", Toast.LENGTH_SHORT).show()
            return
        }
        val seconds = findViewById<EditText>(R.id.etSeconds).text.toString().toFloatOrNull()
        val gifWidth = findViewById<EditText>(R.id.etGifWidth).text.toString().toIntOrNull()
        val gifStep = findViewById<EditText>(R.id.etGifStep).text.toString().toIntOrNull()
        val makeVideo = findViewById<CheckBox>(R.id.cbVideo).isChecked
        val makeGif = findViewById<CheckBox>(R.id.cbGif).isChecked
        if (seconds == null || seconds < 0.02f || gifWidth == null || gifWidth < 16 || gifStep == null || gifStep < 1) {
            Toast.makeText(this, "Check the numbers (seconds ≥ 0.02, GIF width ≥ 16, step ≥ 1)", Toast.LENGTH_LONG).show()
            return
        }
        if (!makeVideo && !makeGif) {
            Toast.makeText(this, "Pick GIF, video or both", Toast.LENGTH_SHORT).show()
            return
        }

        val short = when (findViewById<RadioGroup>(R.id.rgSize).checkedRadioButtonId) {
            R.id.rb480 -> 480
            R.id.rb1080 -> 1080
            else -> 720
        }
        val long = (short * 16 / 9) / 2 * 2
        val (w, h) = when (findViewById<RadioGroup>(R.id.rgAspect).checkedRadioButtonId) {
            R.id.rbPortrait -> short to long
            R.id.rbSquare -> short to short
            else -> long to short
        }
        val music = if (makeVideo) audioUri else null
        val options = Options(seconds, w, h, makeVideo, makeGif, gifWidth, gifStep, music)

        setRunning(true)
        progress.max = images.size
        progress.progress = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        job = lifecycleScope.launch {
            try {
                val result = Converter(applicationContext).run(images, options) { done, total ->
                    runOnUiThread {
                        progress.progress = done
                        tvStatus.text = "Processing $done / $total"
                    }
                }
                val lines = buildList {
                    add("Done: ${result.used} images used" + if (result.skipped > 0) ", ${result.skipped} skipped" else "")
                    if (result.videoUri != null) {
                        val soundNote = when {
                            music == null -> ""
                            result.audioError != null -> " (without music: ${result.audioError})"
                            else -> " with music"
                        }
                        add("Video$soundNote saved in Movies/ImageToGifVideo")
                    }
                    if (result.gifUri != null) add("GIF saved in Pictures/ImageToGifVideo")
                }
                tvStatus.text = lines.joinToString("\n")
            } catch (e: CancellationException) {
                tvStatus.text = "Cancelled"
            } catch (e: Exception) {
                tvStatus.text = "Failed: ${e.message}"
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setRunning(false)
            }
        }
    }

    private fun setRunning(running: Boolean) {
        btnStart.isEnabled = !running
        btnFolder.isEnabled = !running
        btnPick.isEnabled = !running
        btnAudio.isEnabled = !running
        btnAudioClear.isEnabled = !running
        btnCancel.visibility = if (running) View.VISIBLE else View.GONE
    }
}
