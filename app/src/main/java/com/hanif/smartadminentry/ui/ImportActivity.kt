package com.hanif.smartadminentry.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
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
    private var allOcrText = StringBuilder()
    private var isProcessing = false
    private var processingJob: Job? = null

    // ── Image pickers ─────────────────────────────────────────────────────────
    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) addImages(uris) }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>("captured_image_uri")
            uri?.let { addImages(listOf(it)) }
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
        // Image buttons
        binding.btnCamera.setOnClickListener {
            cameraLauncher.launch(Intent(this, CameraActivity::class.java))
        }
        binding.btnGallery.setOnClickListener {
            galleryPicker.launch("image/*")
        }
        binding.btnClearImages.setOnClickListener { clearAll() }

        // Sheet selector
        binding.spinnerSheet.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Quiz", "QBank", "Study")
        )
        val savedSheet = AppPrefs.defaultSheet
        val sheetIdx = listOf("Quiz", "QBank", "Study").indexOf(savedSheet)
        if (sheetIdx >= 0) binding.spinnerSheet.setSelection(sheetIdx)

        binding.etSubject.setText(AppPrefs.defaultSubject)
        binding.etSubTopic.setText(AppPrefs.defaultSubTopic)

        // Step buttons
        binding.btnRunOcr.setOnClickListener { runOcrAll() }
        binding.btnRunAi.setOnClickListener { runAiOnOcr() }
        binding.btnSendToBulk.setOnClickListener { sendToBulk() }

        // Direct OCR text edit
        binding.btnEditOcr.setOnClickListener { showOcrEditDialog() }

        updateImageCount()
    }

    private fun addImages(uris: List<Uri>) {
        uris.forEach { images.add(ImportImage(it)) }
        updateImageCount()
        renderImageGrid()
        setStep(1)
    }

    private fun clearAll() {
        images.clear()
        parsedQuestions.clear()
        allOcrText.clear()
        binding.tvOcrResult.text = ""
        binding.tvParsedCount.text = ""
        binding.layoutOcrResult.visibility = View.GONE
        binding.layoutParsed.visibility = View.GONE
        renderImageGrid()
        updateImageCount()
        setStep(0)
    }

    private fun updateImageCount() {
        binding.tvImageCount.text = if (images.isEmpty()) "কোনো ছবি নেই"
        else "${images.size} টি ছবি যোগ হয়েছে"
        binding.btnRunOcr.isEnabled = images.isNotEmpty() && !isProcessing
        binding.btnClearImages.isEnabled = images.isNotEmpty()
    }

    private fun renderImageGrid() {
        binding.imageGrid.removeAllViews()
        val dp48 = (48 * resources.displayMetrics.density).toInt()
        images.forEachIndexed { idx, img ->
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp48, dp48).apply {
                    marginEnd = (4 * resources.displayMetrics.density).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#E8EDF5"))
                setPadding(2, 2, 2, 2)
            }
            Glide.with(this).load(img.uri).into(iv)
            iv.setOnLongClickListener {
                images.removeAt(idx)
                renderImageGrid()
                updateImageCount()
                true
            }
            binding.imageGrid.addView(iv)
        }
    }

    // ── STEP 1: OCR ───────────────────────────────────────────────────────────
    private fun runOcrAll() {
        if (images.isEmpty()) return
        isProcessing = true
        allOcrText.clear()
        setStep(1)
        showProgress("OCR চলছে... 0/${images.size}")
        binding.btnRunOcr.isEnabled = false

        processingJob = lifecycleScope.launch {
            images.forEachIndexed { idx, img ->
                showProgress("OCR চলছে... ${idx + 1}/${images.size}")
                img.status = ImportImage.Status.OCR_RUNNING
                try {
                    val text = OcrProcessor.processImage(this@ImportActivity, img.uri)
                    img.ocrText = text
                    img.status = ImportImage.Status.OCR_DONE
                    allOcrText.append("--- পৃষ্ঠা ${idx + 1} ---\n")
                    allOcrText.append(text).append("\n\n")
                } catch (e: Exception) {
                    img.status = ImportImage.Status.ERROR
                    img.errorMsg = e.message ?: "Error"
                }
            }

            val combined = allOcrText.toString().trim()
            binding.tvOcrResult.text = combined
            binding.layoutOcrResult.visibility = View.VISIBLE
            binding.tvOcrStats.text = "OCR: ${combined.length} characters, ${combined.lines().size} lines"
            hideProgress()
            isProcessing = false
            binding.btnRunOcr.isEnabled = true
            setStep(2)
            Toast.makeText(this@ImportActivity, "✅ OCR সম্পন্ন — AI দিয়ে format করুন", Toast.LENGTH_LONG).show()
        }
    }

    // ── STEP 2: AI Format ────────────────────────────────────────────────────
    private fun runAiOnOcr() {
        val ocrText = binding.tvOcrResult.text.toString().trim()
        if (ocrText.isBlank()) {
            Toast.makeText(this, "আগে OCR চালান", Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = AppPrefs.geminiApiKey
        if (apiKey.isBlank()) {
            showApiKeyDialog()
            return
        }

        val sheet = binding.spinnerSheet.selectedItem.toString()
        val subject = binding.etSubject.text.toString().trim()
        val subTopic = binding.etSubTopic.text.toString().trim()

        // Save defaults
        AppPrefs.defaultSubject = subject
        AppPrefs.defaultSubTopic = subTopic
        AppPrefs.defaultSheet = sheet

        isProcessing = true
        showProgress("Gemini AI format করছে...")
        binding.btnRunAi.isEnabled = false

        lifecycleScope.launch {
            val result = GeminiProcessor.process(apiKey, ocrText, sheet, subject, subTopic)
            isProcessing = false
            binding.btnRunAi.isEnabled = true
            hideProgress()

            when (result) {
                is GeminiProcessor.GeminiResult.Success -> {
                    parsedQuestions.clear()
                    result.lines.forEach { line ->
                        if (line.contains(";")) {
                            parsedQuestions.add(ParsedQuestion.parse(line))
                        }
                    }
                    showParsedPreview()
                    setStep(3)
                }
                is GeminiProcessor.GeminiResult.Error -> {
                    Toast.makeText(this@ImportActivity, "❌ ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showParsedPreview() {
        parsedQuestions.also { qs ->
            binding.tvParsedCount.text = "${qs.size} টি প্রশ্ন পাওয়া গেছে"
            val mcqCount = qs.count { it.qType == "MCQ" }
            val writtenCount = qs.count { it.qType == "Written" }
            binding.tvParsedBreakdown.text = "MCQ: $mcqCount | Written: $writtenCount"
            binding.layoutParsed.visibility = View.VISIBLE

            // Show preview in scrollable text
            val sb = StringBuilder()
            qs.forEachIndexed { idx, q ->
                sb.append("${idx + 1}. [${q.qType}] ${q.question.take(80)}")
                if (q.question.length > 80) sb.append("...")
                sb.append("\n")
                if (q.opt1.isNotBlank()) sb.append("   ক. ${q.opt1.take(40)}\n")
                if (q.correct.isNotBlank()) sb.append("   ✅ ${q.correct.take(50)}\n")
                sb.append("\n")
            }
            binding.tvParsedPreview.text = sb.toString()
        }
    }

    // ── OCR Edit ─────────────────────────────────────────────────────────────
    private fun showOcrEditDialog() {
        val current = binding.tvOcrResult.text.toString()
        val et = EditText(this).apply {
            setText(current)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(24, 16, 24, 16)
            minLines = 10
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("OCR Text Edit করুন")
            .setView(ScrollView(this).apply { addView(et) })
            .setPositiveButton("Save") { _, _ ->
                allOcrText.clear()
                allOcrText.append(et.text.toString())
                binding.tvOcrResult.text = et.text.toString()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── STEP 3: Send to Bulk ─────────────────────────────────────────────────
    private fun sendToBulk() {
        if (parsedQuestions.isEmpty()) {
            // No AI parse, use raw OCR text as-is? Or ask user
            val ocrText = binding.tvOcrResult.text.toString()
            if (ocrText.isBlank()) { Toast.makeText(this, "কোনো প্রশ্ন নেই", Toast.LENGTH_SHORT).show(); return }
            // Send raw lines
            returnBulkText(ocrText)
            return
        }

        val selected = parsedQuestions.filter { it.selected }
        if (selected.isEmpty()) {
            Toast.makeText(this, "কোনো প্রশ্ন select করা নেই", Toast.LENGTH_SHORT).show()
            return
        }

        val bulkText = selected.joinToString("\n") { it.editedRaw }
        returnBulkText(bulkText)
    }

    private fun returnBulkText(text: String) {
        val sheet = binding.spinnerSheet.selectedItem.toString()
        val subject = binding.etSubject.text.toString().trim()
        val subTopic = binding.etSubTopic.text.toString().trim()

        val intent = Intent().apply {
            putExtra("bulk_text", text)
            putExtra("target_sheet", sheet)
            putExtra("subject", subject)
            putExtra("sub_topic", subTopic)
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    // ── API Key Dialog ────────────────────────────────────────────────────────
    private fun showApiKeyDialog() {
        val et = EditText(this).apply {
            hint = "AIza..."
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("🔑 Gemini API Key")
            .setMessage("Settings → Gemini API Key দিন। Google AI Studio থেকে ফ্রি পাবেন।")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val key = et.text.toString().trim()
                if (key.isNotBlank()) {
                    AppPrefs.geminiApiKey = key
                    runAiOnOcr()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── UI Helpers ────────────────────────────────────────────────────────────
    private fun setStep(step: Int) {
        // step 0: no images, 1: images added, 2: OCR done, 3: AI done
        binding.btnRunOcr.alpha = if (step >= 1) 1f else 0.4f
        binding.btnRunAi.alpha = if (step >= 2) 1f else 0.4f
        binding.btnSendToBulk.alpha = if (step >= 2) 1f else 0.4f
        binding.btnRunAi.isEnabled = step >= 2 && !isProcessing
        binding.btnSendToBulk.isEnabled = step >= 2
    }

    private fun showProgress(msg: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        binding.tvProgress.text = msg
    }

    private fun hideProgress() {
        binding.progressBar.visibility = View.GONE
        binding.tvProgress.visibility = View.GONE
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        processingJob?.cancel()
        super.onDestroy()
    }
}
