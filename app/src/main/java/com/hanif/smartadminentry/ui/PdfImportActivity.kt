package com.hanif.smartadminentry.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hanif.smartadminentry.ai.GeminiProcessor
import com.hanif.smartadminentry.data.AppPrefs
import com.hanif.smartadminentry.data.ExamPaper
import com.hanif.smartadminentry.util.PdfTextExtractor
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class PdfImportActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPickPdf: Button
    private lateinit var btnProcessAll: Button
    private lateinit var rv: RecyclerView
    private lateinit var adapter: PaperAdapter

    private val papers = mutableListOf<ExamPaper>()
    private var processJob: Job? = null
    private val http = OkHttpClient()

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF1F5F9.toInt())
        }

        // Toolbar
        val tb = androidx.appcompat.widget.Toolbar(this).apply {
            setBackgroundColor(0xFF4F46E5.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
            title = "📄 PDF Import"
        }
        setSupportActionBar(tb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        root.addView(tb)

        // Buttons row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 8)
        }
        btnPickPdf = Button(this).apply {
            text = "📄 PDF নাও"
            setBackgroundColor(0xFF1E293B.toInt()); setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f; setPadding(20, 10, 20, 10)
        }
        btnPickPdf.setOnClickListener { pdfPicker.launch("application/pdf") }

        btnProcessAll = Button(this).apply {
            text = "🤖 সব Process"
            setBackgroundColor(0xFF4F46E5.toInt()); setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f; setPadding(20, 10, 20, 10)
            isEnabled = false
        }
        btnProcessAll.setOnClickListener { processAllPapers() }

        val lp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = 8 }
        val lp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        btnRow.addView(btnPickPdf, lp1)
        btnRow.addView(btnProcessAll, lp2)
        root.addView(btnRow)

        // Progress
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE
        }
        tvProgress = TextView(this).apply {
            textSize = 11f; setTextColor(0xFF4F46E5.toInt())
            setPadding(16, 0, 16, 0); visibility = View.GONE
        }
        tvStatus = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF64748B.toInt())
            setPadding(16, 6, 16, 6)
            text = "PDF select করুন। প্রতিটা exam paper আলাদা card এ দেখাবে।"
        }
        root.addView(progressBar)
        root.addView(tvProgress)
        root.addView(tvStatus)

        // RecyclerView
        rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@PdfImportActivity) }
        adapter = PaperAdapter()
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    // ── Load PDF ──────────────────────────────────────────────────────────────
    private fun loadPdf(uri: Uri) {
        papers.clear(); adapter.notifyDataSetChanged()
        showProgress("PDF পড়ছি (OCR চলছে)...")

        lifecycleScope.launch {
            try {
                val pages = PdfTextExtractor.extractPages(context = this@PdfImportActivity, uri = uri) { cur, total ->
                    runOnUiThread { tvProgress.text = "OCR: পৃষ্ঠা $cur / $total" }
                }
                if (pages.isEmpty()) { hideProgress(); toast("PDF পড়তে পারেনি"); return@launch }

                tvProgress.text = "Section বের করছি..."
                val sections = withContext(Dispatchers.Default) {
                    PdfTextExtractor.splitIntoExamSections(pages)
                }

                sections.forEachIndexed { i, (title, text) ->
                    papers.add(ExamPaper(
                        id = "p$i", title = title,
                        rawText = text, sheet = AppPrefs.defaultSheet
                    ))
                }
                adapter.notifyDataSetChanged()
                hideProgress()
                tvStatus.text = "${papers.size} টি exam paper পাওয়া গেছে ✅"
                btnProcessAll.isEnabled = papers.isNotEmpty()
            } catch (e: Exception) {
                hideProgress(); toast("Error: ${e.message}")
            }
        }
    }

    // ── Process All ───────────────────────────────────────────────────────────
    private fun processAllPapers() {
        val apiKey = AppPrefs.geminiApiKey
        if (apiKey.isBlank()) { showApiKeyDialog(); return }

        btnProcessAll.isEnabled = false
        showProgress("AI processing শুরু হচ্ছে...")
        processJob = lifecycleScope.launch {
            val pending = papers.filter { it.status == ExamPaper.Status.PENDING }
            pending.forEachIndexed { i, paper ->
                runOnUiThread { tvProgress.text = "AI: ${i+1}/${pending.size} — ${paper.title.take(25)}..." }
                paper.status = ExamPaper.Status.AI_RUNNING
                notifyItem(paper)

                val result = GeminiProcessor.process(
                    apiKey = apiKey, ocrText = paper.rawText,
                    targetSheet = paper.sheet, subject = paper.subject, subTopic = paper.subTopic
                )
                when (result) {
                    is GeminiProcessor.GeminiResult.Success -> {
                        paper.formattedBulk = result.lines.joinToString("\n")
                        paper.status = ExamPaper.Status.DONE
                    }
                    is GeminiProcessor.GeminiResult.Error -> {
                        paper.errorMsg = result.message
                        paper.status = ExamPaper.Status.ERROR
                    }
                }
                notifyItem(paper)
                if (i < pending.size - 1) delay(2000) // rate limit
            }
            hideProgress()
            btnProcessAll.isEnabled = true
            tvStatus.text = "সম্পন্ন! Card গুলো review করে Upload করুন ✅"
        }
    }

    // ── Upload একটা paper ──────────────────────────────────────────────────
    fun uploadPaper(paper: ExamPaper) {
        val scriptUrl = AppPrefs.scriptUrl
        if (scriptUrl.isBlank()) { toast("Settings এ Script URL দিন"); return }

        paper.status = ExamPaper.Status.UPLOADING; notifyItem(paper)

        lifecycleScope.launch(Dispatchers.IO) {
            val lines = paper.formattedBulk.lines().filter { it.contains(";") }
            var done = 0; var failed = 0
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            lines.forEach { line ->
                val parts = line.split(";")
                try {
                    val body = JSONObject().apply {
                        put("targetTab", paper.sheet)
                        put("question", parts.getOrElse(0) { "" })
                        put("opt1",     parts.getOrElse(1) { "" })
                        put("opt2",     parts.getOrElse(2) { "" })
                        put("opt3",     parts.getOrElse(3) { "" })
                        put("opt4",     parts.getOrElse(4) { "" })
                        put("correct",  parts.getOrElse(5) { "" })
                        put("explanation", parts.getOrElse(6) { "" })
                        put("subject",  paper.subject)
                        put("sub_topic", paper.subTopic)
                        put("qType",    if (parts.size < 6) "Written" else "MCQ")
                        put("timestamp", sdf.format(Date()))
                        put("bulkMode", true)
                    }
                    val req = Request.Builder().url(scriptUrl)
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    if (http.newCall(req).execute().isSuccessful) done++ else failed++
                } catch (_: Exception) { failed++ }
            }

            withContext(Dispatchers.Main) {
                paper.status = ExamPaper.Status.UPLOADED
                paper.errorMsg = "✅ $done uploaded${if (failed > 0) ", $failed failed" else ""}"
                notifyItem(paper)
                toast("${paper.title.take(20)}: $done টি upload হয়েছে")
            }
        }
    }

    // ── Send to Entry App Bulk ─────────────────────────────────────────────
    fun sendToEntryApp(paper: ExamPaper) {
        val intent = Intent().apply {
            putExtra("bulk_text", paper.formattedBulk)
            putExtra("target_sheet", paper.sheet)
            putExtra("subject", paper.subject)
            putExtra("sub_topic", paper.subTopic)
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private fun notifyItem(paper: ExamPaper) {
        val idx = papers.indexOf(paper)
        if (idx >= 0) runOnUiThread { adapter.notifyItemChanged(idx) }
    }

    // ══ Adapter ══════════════════════════════════════════════════════════════
    inner class PaperAdapter : RecyclerView.Adapter<PaperAdapter.VH>() {

        inner class VH(val card: androidx.cardview.widget.CardView) : RecyclerView.ViewHolder(card)

        override fun getItemCount() = papers.size

        override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH {
            val card = androidx.cardview.widget.CardView(this@PdfImportActivity).apply {
                radius = 16f; cardElevation = 4f
                setContentPadding(16, 16, 16, 16)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(12, 6, 12, 6) }
            }
            return VH(card)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = papers[pos]
            h.card.removeAllViews()

            val inner = LinearLayout(this@PdfImportActivity).apply {
                orientation = LinearLayout.VERTICAL
            }

            // Title
            inner.addView(TextView(this@PdfImportActivity).apply {
                text = "📋 ${p.title.take(70)}"
                textSize = 13f; setTextColor(0xFF1E293B.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 6)
            })

            // Status badge
            val statusColor = when (p.status) {
                ExamPaper.Status.DONE, ExamPaper.Status.UPLOADED -> 0xFF10B981.toInt()
                ExamPaper.Status.ERROR -> 0xFFEF4444.toInt()
                ExamPaper.Status.AI_RUNNING, ExamPaper.Status.UPLOADING -> 0xFF4F46E5.toInt()
                else -> 0xFF94A3B8.toInt()
            }
            val statusText = when (p.status) {
                ExamPaper.Status.PENDING    -> "⏳ অপেক্ষায়"
                ExamPaper.Status.AI_RUNNING -> "🤖 AI চলছে..."
                ExamPaper.Status.DONE       -> "✅ Ready — ${p.questionCount} টি প্রশ্ন"
                ExamPaper.Status.UPLOADING  -> "⬆️ Upload হচ্ছে..."
                ExamPaper.Status.UPLOADED   -> p.errorMsg
                ExamPaper.Status.ERROR      -> "❌ ${p.errorMsg.take(50)}"
            }
            inner.addView(TextView(this@PdfImportActivity).apply {
                text = statusText; textSize = 11f; setTextColor(statusColor)
                setPadding(0, 0, 0, 8)
            })

            // Sheet/Subject row (editable when DONE)
            if (p.status == ExamPaper.Status.DONE || p.status == ExamPaper.Status.ERROR) {
                val metaRow = LinearLayout(this@PdfImportActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 8)
                }
                val dp = resources.displayMetrics.density.toInt()

                val sheetSpinner = Spinner(this@PdfImportActivity).apply {
                    adapter = ArrayAdapter(this@PdfImportActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf("QBank","Quiz","Study"))
                    val idx2 = listOf("QBank","Quiz","Study").indexOf(p.sheet)
                    if (idx2 >= 0) setSelection(idx2)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(a: AdapterView<*>?, v: View?, i: Int, id: Long) {
                            p.sheet = listOf("QBank","Quiz","Study")[i]
                        }
                        override fun onNothingSelected(a: AdapterView<*>?) {}
                    }
                }
                val etSubject = EditText(this@PdfImportActivity).apply {
                    hint = "Subject"; textSize = 11f; setText(p.subject)
                    setPadding(8,4,8,4)
                    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) p.subject = text.toString().trim() }
                }
                val etSubTopic = EditText(this@PdfImportActivity).apply {
                    hint = "Sub-Topic"; textSize = 11f; setText(p.subTopic)
                    setPadding(8,4,8,4)
                    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) p.subTopic = text.toString().trim() }
                }
                metaRow.addView(sheetSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = 4*dp })
                metaRow.addView(etSubject,   LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = 4*dp })
                metaRow.addView(etSubTopic,  LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                inner.addView(metaRow)

                // Bulk text preview + edit
                val etBulk = EditText(this@PdfImportActivity).apply {
                    setText(p.formattedBulk)
                    textSize = 10f; setTextColor(0xFF334155.toInt())
                    setBackgroundColor(0xFFF8FAFC.toInt())
                    setPadding(8, 8, 8, 8)
                    maxLines = 8; minLines = 3
                    gravity = android.view.Gravity.TOP
                    typeface = android.graphics.Typeface.MONOSPACE
                    setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) p.formattedBulk = text.toString().trim()
                    }
                }
                inner.addView(etBulk, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8*dp })

                // Action buttons
                val actRow = LinearLayout(this@PdfImportActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                }

                fun btn(text: String, bg: Int, action: () -> Unit) = Button(this@PdfImportActivity).apply {
                    this.text = text; textSize = 10f
                    setBackgroundColor(bg); setTextColor(0xFFFFFFFF.toInt())
                    setPadding(16, 6, 16, 6)
                    setOnClickListener { p.formattedBulk = etBulk.text.toString().trim(); action() }
                }

                val btnCopy = btn("📋 Copy", 0xFF0F172A.toInt()) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("bulk", p.formattedBulk))
                    toast("Copied! Entry app Bulk এ paste করুন")
                }
                val btnEntry = btn("📤 Entry App", 0xFF7C3AED.toInt()) { sendToEntryApp(p) }
                val btnUpload = btn("⬆️ Upload", 0xFF10B981.toInt()) {
                    p.subject = etSubject.text.toString().trim()
                    p.subTopic = etSubTopic.text.toString().trim()
                    AlertDialog.Builder(this@PdfImportActivity)
                        .setTitle("Upload করবো?")
                        .setMessage("${p.title}\n${p.questionCount} প্রশ্ন → ${p.sheet}")
                        .setPositiveButton("Upload") { _, _ -> uploadPaper(p) }
                        .setNegativeButton("Cancel", null).show()
                }

                val m = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = 6 }
                actRow.addView(btnCopy, m); actRow.addView(btnEntry, m); actRow.addView(btnUpload)
                inner.addView(actRow)
            }

            h.card.addView(inner)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun showProgress(msg: String) {
        progressBar.visibility = View.VISIBLE
        tvProgress.text = msg; tvProgress.visibility = View.VISIBLE
    }
    private fun hideProgress() {
        progressBar.visibility = View.GONE; tvProgress.visibility = View.GONE
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun showApiKeyDialog() {
        val et = EditText(this).apply { hint = "AIza..."; setPadding(32, 24, 32, 24) }
        AlertDialog.Builder(this).setTitle("🔑 Gemini API Key").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val k = et.text.toString().trim()
                if (k.isNotBlank()) { AppPrefs.geminiApiKey = k; processAllPapers() }
            }.setNegativeButton("Cancel", null).show()
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onDestroy() { processJob?.cancel(); super.onDestroy() }
}
