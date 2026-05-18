package com.hanif.smartadminentry.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        try {
            if (uris.isNotEmpty()) addImages(uris)
        } catch (e: Exception) {
            toast("Gallery error: ${e.message}")
        }
    }

    private var cameraUri: Uri? = null
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        try {
            if (success && cameraUri != null) addImages(listOf(cameraUri!!))
        } catch (e: Exception) {
            toast("Camera error: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityImportBinding.inflate(layoutInflater)
            setContentView(binding.root)
            binding.toolbar.title = "📸 AI Import"
            binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
            binding.toolbar.setNavigationOnClickListener { finish() }
            setupUI()
        } catch (e: Exception) {
            toast("Init error: ${e.message}")
        }
    }

    private fun setupUI() {
        try {
            binding.btnCamera.setOnClickListener {
                try { openCamera() } catch (e: Exception) { toast("Cam: ${e.message}") }
            }
            binding.btnGallery.setOnClickListener {
                try { galleryPicker.launch("image/*") } catch (e: Exception) { toast("Gallery: ${e.message}") }
            }
            binding.btnClearImages.setOnClickListener { clearAll() }

            val sheets = listOf("Quiz", "QBank", "Study")
            binding.spinnerSheet.adapter = ArrayAdapter(this,
                android.R.layout.simple_spinner_dropdown_item, sheets)
            val idx = sheets.indexOf(AppPrefs.defaultSheet)
            if (idx >= 0) binding.spinnerSheet.setSelection(idx)

            binding.etSubject.setText(AppPrefs.defaultSubject)
            binding.etSubTopic.setText(AppPrefs.defaultSubTopic)

            binding.btnRunOcr.setOnClickListener { runOcrAll() }
            binding.btnRunAi.setOnClickListener { runAiOnOcr() }
            binding.btnCopyPrompt.setOnClickListener { copyPromptWithOcr() }
            binding.btnSendToBulk.setOnClickListener { sendToBulk() }
            binding.btnEditOcr.setOnClickListener { showOcrEditDialog() }

            setStep(0)
            updateImageCount()
        } catch (e: Exception) {
            toast("UI error: ${e.message}")
        }
    }

    private fun openCamera() {
        val file = java.io.File(cacheDir, "img_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.provider", file
        )
        cameraUri = uri
        cameraLauncher.launch(uri)
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
        binding.tvImageCount.text =
            if (images.isEmpty()) "কোনো ছবি নেই"
            else "${images.size} টি ছবি"
        binding.btnRunOcr.isEnabled = images.isNotEmpty() && !isProcessing
        binding.btnClearImages.isEnabled = images.isNotEmpty()
    }

    private fun renderImageGrid() {
        try {
            binding.imageGrid.removeAllViews()
            val dp = (50 * resources.displayMetrics.density).toInt()
            images.forEachIndexed { idx, img ->
                val iv = ImageView(this)
                iv.layoutParams = LinearLayout.LayoutParams(dp, dp).also { it.marginEnd = 6 }
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setBackgroundColor(0xFFCCCCCC.toInt())
                try {
                    val bmp = OcrProcessor.loadBitmap(this, img.uri)
                    if (bmp != null) iv.setImageBitmap(bmp)
                } catch (_: Exception) {}
                iv.setOnLongClickListener {
                    images.removeAt(idx)
                    renderImageGrid()
                    updateImageCount()
                    true
                }
                binding.imageGrid.addView(iv)
            }
        } catch (e: Exception) {
            toast("Grid error: ${e.message}")
        }
    }

    private fun runOcrAll() {
        if (images.isEmpty()) return
        isProcessing = true
        allOcrText.clear()
        showProgress("OCR চলছে...")
        binding.btnRunOcr.isEnabled = false

        processingJob = lifecycleScope.launch {
            images.forEachIndexed { idx, img ->
                showProgress("OCR: ${idx + 1}/${images.size}")
                try {
                    val text = OcrProcessor.processImage(this@ImportActivity, img.uri)
                    img.ocrText = text
                    img.status = ImportImage.Status.OCR_DONE
                    allOcrText.append("--- ছবি ${idx + 1} ---\n$text\n\n")
                } catch (e: Exception) {
                    img.status = ImportImage.Status.ERROR
                    allOcrText.append("--- ছবি ${idx + 1} ERROR: ${e.message} ---\n\n")
                }
            }
            val combined = allOcrText.toString().trim()
            binding.tvOcrResult.text = combined
            binding.tvOcrStats.text = "${combined.length} chars"
            binding.layoutOcrResult.visibility = View.VISIBLE
            hideProgress()
            isProcessing = false
            binding.btnRunOcr.isEnabled = true
            setStep(2)
            toast("✅ OCR সম্পন্ন!")
        }
    }

    private fun runAiOnOcr() {
        val ocrText = binding.tvOcrResult.text.toString().trim()
        if (ocrText.isBlank()) { toast("আগে OCR চালান"); return }
        val apiKey = AppPrefs.geminiApiKey
        if (apiKey.isBlank()) { showApiKeyDialog(); return }

        val sheet    = binding.spinnerSheet.selectedItem?.toString() ?: "Quiz"
        val subject  = binding.etSubject.text.toString().trim()
        val subTopic = binding.etSubTopic.text.toString().trim()
        AppPrefs.defaultSubject  = subject
        AppPrefs.defaultSubTopic = subTopic
        AppPrefs.defaultSheet    = sheet

        isProcessing = true
        showProgress("Gemini AI চলছে...")
        binding.btnRunAi.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = GeminiProcessor.process(apiKey, ocrText, sheet, subject, subTopic)
                isProcessing = false
                binding.btnRunAi.isEnabled = true
                hideProgress()
                when (result) {
                    is GeminiProcessor.GeminiResult.Success -> {
                        parsedQuestions.clear()
                        result.lines.forEach { parsedQuestions.add(ParsedQuestion.parse(it)) }
                        showParsedPreview()
                        setStep(3)
                    }
                    is GeminiProcessor.GeminiResult.Error ->
                        toast("❌ ${result.message}")
                }
            } catch (e: Exception) {
                isProcessing = false
                binding.btnRunAi.isEnabled = true
                hideProgress()
                toast("AI error: ${e.message}")
            }
        }
    }

    private fun showParsedPreview() {
        binding.tvParsedCount.text = "${parsedQuestions.size} টি প্রশ্ন"
        val mcq = parsedQuestions.count { it.qType == "MCQ" }
        val wr  = parsedQuestions.count { it.qType == "Written" }
        binding.tvParsedBreakdown.text = "MCQ: $mcq | Written: $wr"
        binding.layoutParsed.visibility = View.VISIBLE
        val sb = StringBuilder()
        parsedQuestions.take(10).forEachIndexed { i, q ->
            sb.append("${i + 1}. ${q.question.take(60)}\n")
            if (q.correct.isNotBlank()) sb.append("   ✅ ${q.correct.take(40)}\n")
            sb.append("\n")
        }
        if (parsedQuestions.size > 10) sb.append("... আরো ${parsedQuestions.size - 10} টি")
        binding.tvParsedPreview.text = sb.toString()
    }

    private fun showOcrEditDialog() {
        val et = EditText(this)
        et.setText(binding.tvOcrResult.text)
        et.textSize = 12f
        et.setPadding(24, 16, 24, 16)
        et.minLines = 8
        et.gravity = android.view.Gravity.TOP
        AlertDialog.Builder(this)
            .setTitle("OCR Edit")
            .setView(ScrollView(this).apply { addView(et) })
            .setPositiveButton("Save") { _, _ ->
                allOcrText.clear()
                allOcrText.append(et.text.toString())
                binding.tvOcrResult.text = et.text.toString()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendToBulk() {
        val text = if (parsedQuestions.isNotEmpty())
            parsedQuestions.joinToString("\n") { it.editedRaw }
        else
            binding.tvOcrResult.text.toString().trim()

        if (text.isBlank()) { toast("কোনো data নেই"); return }

        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra("bulk_text",    text)
            putExtra("target_sheet", binding.spinnerSheet.selectedItem?.toString() ?: "Quiz")
            putExtra("subject",      binding.etSubject.text.toString().trim())
            putExtra("sub_topic",    binding.etSubTopic.text.toString().trim())
        })
        finish()
    }

    private fun showApiKeyDialog() {
        val et = EditText(this)
        et.hint = "AIza..."
        et.setPadding(32, 24, 32, 24)
        AlertDialog.Builder(this)
            .setTitle("Gemini API Key")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val k = et.text.toString().trim()
                if (k.isNotBlank()) { AppPrefs.geminiApiKey = k; runAiOnOcr() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setStep(step: Int) {
        binding.btnRunOcr.alpha     = if (step >= 1) 1f else 0.4f
        binding.btnRunAi.alpha      = if (step >= 2) 1f else 0.4f
        binding.btnSendToBulk.alpha = if (step >= 2) 1f else 0.4f
        binding.btnRunAi.isEnabled      = step >= 2 && !isProcessing
        binding.btnSendToBulk.isEnabled = step >= 2
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

    private fun copyPromptWithOcr() {
        val ocrText = binding.tvOcrResult.text.toString().trim()
        if (ocrText.isBlank()) { toast("আগে OCR চালান"); return }
        val sheet    = binding.spinnerSheet.selectedItem?.toString() ?: "Quiz"
        val subject  = binding.etSubject.text.toString().trim()
        val subTopic = binding.etSubTopic.text.toString().trim()
        val prompt = buildString {
            append("তুমি একটি পরীক্ষার প্রশ্নপত্র formatter।\n")
            append("Subject: ${subject.ifBlank{"-"}} | Sub-Topic: ${subTopic.ifBlank{"-"}} | Sheet: $sheet\n\n")
            append("নিচের OCR text থেকে প্রশ্ন বের করে এই format এ দাও:\n")
            append("MCQ এর ফরমেট হবে-   প্রশ্ন;অপশন১;অপশন২;অপশন৩;অপশন৪;উত্তর\n")
            append("Written এর ফরমেট হবে-   প্রশ্ন;উত্তর;ব্যাখ্যা\n\n")
            append("RULES:\n")
            append("১. প্রতিটি প্রশ্ন আলাদা line এ\n")
            append("২. Serial number বাদ দাও\n")
            append("৩. field এর ভেতরে ; ব্যবহার করো না\n")
            append("৪. উত্তর = option এর আসল text (ক/খ নয়)\n")
            append("৫. গণিত: ভগ্নাংশ \$\\frac{a}{b}\$, ঘাত \$x^{2}\$, ∴ △ ∠ × চিহ্ন ব্যবহার করো\n")
            append("৬. Page number, footer, reference বাদ দাও\n\n")
            append("উদাহরণ output:\n")
            append("Slow and steady wins the race.;wins;lose;run;win;wins\n")
            append("বাংলাদেশের রাজধানী কোথায়?;ঢাকা;চট্টগ্রাম;খুলনা;রাজশাহী;ঢাকা\n\n")
            append("Wriiten এর উদাহরন হল এক কথায় প্রকাশ করুন- ক. হরিণের চামড়া- খ. উপকারীর অপকার করে যে- গ. আগে জন্মেছে যে- এখন আউটপুট হবে এরকম এক কথায় প্রকাশ করুন-ক.হরিনের চামড়া-,উপকারীর অপকার করে যে-, আগে জন্মেছে যে-;অজীন,কৃতঘ্ন,অগ্রজ\n\n")
            append("=== OCR TEXT ===\n")
            append(ocrText)
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("prompt", prompt))
        toast("✅ Prompt+OCR copied! Gemini এ paste করুন → result copy করে Bulk এ দিন")
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onDestroy() { processingJob?.cancel(); super.onDestroy() }
}
