package com.hanif.smartadminentry.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hanif.smartadminentry.ai.GeminiProcessor
import com.hanif.smartadminentry.data.AppPrefs
import com.hanif.smartadminentry.data.ImportImage
import com.hanif.smartadminentry.data.ParsedQuestion
import com.hanif.smartadminentry.databinding.ActivityImportBinding
import com.hanif.smartadminentry.ocr.OcrProcessor
import kotlinx.coroutines.*

class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private val images = mutableListOf<ImportImage>()
    private val parsedQuestions = mutableListOf<ParsedQuestion>()
    private val allOcrText = StringBuilder()
    private var isProcessing = false
    private var processingJob: Job? = null

    // Gallery picker — multiple images
    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) addImages(uris) }

    // Camera — single photo via system camera
    private var cameraUri: Uri? = null
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { addImages(listOf(it)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "📸 AI Import"
        }
        setupUI()
    }

    private fun setupUI() {
        binding.btnCamera.setOnClickListener { openCamera() }
        binding.btnGallery.setOnClickListener { galleryPicker.launch("image/*") }
        binding.btnClearImages.setOnClickListener { clearAll() }

        // Sheet spinner
        binding.spinnerSheet.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Quiz", "QBank", "Study")
        )
        val idx = listOf("Quiz","QBank","Study").indexOf(AppPrefs.defaultSheet)
        if (idx >= 0) binding.spinnerSheet.setSelection(idx)

        binding.etSubject.setText(AppPrefs.defaultSubject)
        binding.etSubTopic.setText(AppPrefs.defaultSubTopic)

        binding.btnRunOcr.setOnClickListener { runOcrAll() }
        binding.btnRunAi.setOnClickListener { runAiOnOcr() }
        binding.btnSendToBulk.setOnClickListener { sendToBulk() }
        binding.btnEditOcr.setOnClickListener { showOcrEditDialog() }

        setStep(0)
        updateImageCount()
    }

    // ── Camera via system intent ──────────────────────────────────────────────
    private fun openCamera() {
        try {
            val file = java.io.File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.provider", file
            )
            cameraUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addImages(uris: List<Uri>) {
        uris.forEach { images.add(ImportImage(it)) }
        updateImageCount()
        renderImageGrid()
        setStep(1)
    }

    private fun clearAll() {
        images.clear(); parsedQuestions.clear(); allOcrText.clear()
        binding.tvOcrResult.text = ""
        binding.tvParsedCount.text = ""
        binding.layoutOcrResult.visibility = View.GONE
        binding.layoutParsed.visibility = View.GONE
        renderImageGrid(); updateImageCount(); setStep(0)
    }

    private fun updateImageCount() {
        binding.tvImageCount.text =
            if (images.isEmpty()) "কোনো ছবি নেই" else "${images.size} টি ছবি যোগ হয়েছে"
        binding.btnRunOcr.isEnabled = images.isNotEmpty() && !isProcessing
        binding.btnClearImages.isEnabled = images.isNotEmpty()
    }

    private fun renderImageGrid() {
        binding.imageGrid.removeAllViews()
        val dp = (48 * resources.displayMetrics.density).toInt()
        images.forEachIndexed { idx, img ->
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp, dp).apply {
                    marginEnd = (4 * resources.displayMetrics.density).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#E8EDF5"))
            }
            try {
                val bmp = OcrProcessor.loadBitmap(this, img.uri)
                if (bmp != null) iv.setImageBitmap(bmp)
                else iv.setBackgroundColor(Color.LTGRAY)
            } catch (_: Exception) { iv.setBackgroundColor(Color.LTGRAY) }
            iv.setOnLongClickListener { images.removeAt(idx); renderImageGrid(); updateImageCount(); true }
            binding.imageGrid.addView(iv)
        }
    }

    // ── STEP 1: OCR ───────────────────────────────────────────────────────────
    private fun runOcrAll() {
        if (images.isEmpty()) return
        isProcessing = true; allOcrText.clear()
        showProgress("OCR চলছে... 0/${images.size}")
        binding.btnRunOcr.isEnabled = false

        processingJob = lifecycleScope.launch {
            images.forEachIndexed { idx, img ->
                showProgress("OCR চলছে... ${idx+1}/${images.size}")
                img.status = ImportImage.Status.OCR_RUNNING
                try {
                    val text = OcrProcessor.processImage(this@ImportActivity, img.uri)
                    img.ocrText = text
                    img.status = ImportImage.Status.OCR_DONE
                    allOcrText.append("--- ছবি ${idx+1} ---\n$text\n\n")
                } catch (e: Exception) {
                    img.status = ImportImage.Status.ERROR
                    img.errorMsg = e.message ?: "Error"
                }
            }
            val combined = allOcrText.toString().trim()
            binding.tvOcrResult.text = combined
            binding.tvOcrStats.text = "${combined.length} chars | ${combined.lines().size} lines"
            binding.layoutOcrResult.visibility = View.VISIBLE
            hideProgress(); isProcessing = false
            binding.btnRunOcr.isEnabled = true
            setStep(2)
            Toast.makeText(this@ImportActivity, "✅ OCR সম্পন্ন!", Toast.LENGTH_SHORT).show()
        }
    }

    // ── STEP 2: AI ────────────────────────────────────────────────────────────
    private fun runAiOnOcr() {
        val ocrText = binding.tvOcrResult.text.toString().trim()
        if (ocrText.isBlank()) { Toast.makeText(this, "আগে OCR চালান", Toast.LENGTH_SHORT).show(); return }
        val apiKey = AppPrefs.geminiApiKey
        if (apiKey.isBlank()) { showApiKeyDialog(); return }

        val sheet    = binding.spinnerSheet.selectedItem.toString()
        val subject  = binding.etSubject.text.toString().trim()
        val subTopic = binding.etSubTopic.text.toString().trim()
        AppPrefs.defaultSubject = subject
        AppPrefs.defaultSubTopic = subTopic
        AppPrefs.defaultSheet = sheet

        isProcessing = true; showProgress("Gemini AI format করছে...")
        binding.btnRunAi.isEnabled = false

        lifecycleScope.launch {
            val result = GeminiProcessor.process(apiKey, ocrText, sheet, subject, subTopic)
            isProcessing = false; binding.btnRunAi.isEnabled = true; hideProgress()
            when (result) {
                is GeminiProcessor.GeminiResult.Success -> {
                    parsedQuestions.clear()
                    result.lines.forEach { parsedQuestions.add(ParsedQuestion.parse(it)) }
                    showParsedPreview(); setStep(3)
                }
                is GeminiProcessor.GeminiResult.Error ->
                    Toast.makeText(this@ImportActivity, "❌ ${result.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showParsedPreview() {
        binding.tvParsedCount.text = "${parsedQuestions.size} টি প্রশ্ন পাওয়া গেছে"
        val mcq = parsedQuestions.count { it.qType == "MCQ" }
        val wr  = parsedQuestions.count { it.qType == "Written" }
        binding.tvParsedBreakdown.text = "MCQ: $mcq | Written: $wr"
        binding.layoutParsed.visibility = View.VISIBLE
        val sb = StringBuilder()
        parsedQuestions.forEachIndexed { i, q ->
            sb.append("${i+1}. [${q.qType}] ${q.question.take(70)}")
            if (q.question.length > 70) sb.append("...")
            if (q.opt1.isNotBlank()) sb.append("\n   ক. ${q.opt1.take(35)}")
            if (q.correct.isNotBlank()) sb.append("\n   ✅ ${q.correct.take(40)}")
            sb.append("\n\n")
        }
        binding.tvParsedPreview.text = sb.toString()
    }

    private fun showOcrEditDialog() {
        val et = EditText(this).apply {
            setText(binding.tvOcrResult.text)
            textSize = 12f; setPadding(24,16,24,16); minLines = 10
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("OCR Text Edit করুন")
            .setView(ScrollView(this).apply { addView(et) })
            .setPositiveButton("Save") { _,_ ->
                allOcrText.clear(); allOcrText.append(et.text.toString())
                binding.tvOcrResult.text = et.text.toString()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── STEP 3: Send ─────────────────────────────────────────────────────────
    private fun sendToBulk() {
        val text = if (parsedQuestions.isNotEmpty())
            parsedQuestions.joinToString("\n") { it.editedRaw }
        else binding.tvOcrResult.text.toString().trim()

        if (text.isBlank()) { Toast.makeText(this, "কোনো প্রশ্ন নেই", Toast.LENGTH_SHORT).show(); return }

        val intent = Intent().apply {
            putExtra("bulk_text",    text)
            putExtra("target_sheet", binding.spinnerSheet.selectedItem.toString())
            putExtra("subject",      binding.etSubject.text.toString().trim())
            putExtra("sub_topic",    binding.etSubTopic.text.toString().trim())
        }
        setResult(Activity.RESULT_OK, intent); finish()
    }

    private fun showApiKeyDialog() {
        val et = EditText(this).apply { hint = "AIza..."; setPadding(32,24,32,24) }
        AlertDialog.Builder(this)
            .setTitle("🔑 Gemini API Key")
            .setMessage("Settings থেকে বা এখানে দিন।")
            .setView(et)
            .setPositiveButton("Save") { _,_ ->
                val k = et.text.toString().trim()
                if (k.isNotBlank()) { AppPrefs.geminiApiKey = k; runAiOnOcr() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    private fun setStep(step: Int) {
        binding.btnRunOcr.alpha    = if (step >= 1) 1f else 0.4f
        binding.btnRunAi.alpha     = if (step >= 2) 1f else 0.4f
        binding.btnSendToBulk.alpha= if (step >= 2) 1f else 0.4f
        binding.btnRunAi.isEnabled     = step >= 2 && !isProcessing
        binding.btnSendToBulk.isEnabled= step >= 2
    }

    private fun showProgress(msg: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility  = View.VISIBLE
        binding.tvProgress.text = msg
    }

    private fun hideProgress() {
        binding.progressBar.visibility = View.GONE
        binding.tvProgress.visibility  = View.GONE
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onDestroy() { processingJob?.cancel(); super.onDestroy() }
}
