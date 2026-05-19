package com.hanif.smartadminentry.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hanif.smartadminentry.ocr.OcrProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfTextExtractor {

    private const val TAG = "PdfTextExtractor"

    data class PdfPage(val pageNum: Int, val text: String)

    suspend fun extractPages(
        context: Context,
        uri: Uri,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<PdfPage> = withContext(Dispatchers.IO) {
        val pages = mutableListOf<PdfPage>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext pages
            renderer = PdfRenderer(pfd)
            val total = renderer.pageCount
            Log.d(TAG, "PDF pages: $total")
            for (i in 0 until total) {
                onProgress(i + 1, total)
                val page = renderer.openPage(i)
                try {
                    val scale = 2.5f
                    val w = (page.width * scale).toInt()
                    val h = (page.height * scale).toInt()
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val text = OcrProcessor.processBitmap(context, bmp)
                    bmp.recycle()
                    if (text.isNotBlank()) pages.add(PdfPage(i + 1, text))
                } finally { page.close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF extract error: ${e.message}")
        } finally {
            renderer?.close()
            pfd?.close()
        }
        pages
    }

    // Pages → exam sections (header দেখে আলাদা করো)
    fun splitIntoExamSections(pages: List<PdfPage>): List<Pair<String, String>> {
        // প্রতি পেজের text নম্বর সহ জোড়া করো
        val allLines = mutableListOf<Pair<Int, String>>() // (pageNum, line)
        pages.forEach { page ->
            page.text.lines().forEach { line ->
                allLines.add(Pair(page.pageNum, line))
            }
        }

        // Header চেনার keywords
        val headerPatterns = listOf(
            Regex("পদের নাম[ঃ:]"),
            Regex("পদের নামঃ"),
            Regex("Post[:\\s]"),
            Regex("MATRIX"),
            Regex("নিয়োগ পরীক্ষা[\\-–]\\d{4}"),
            Regex("পরীক্ষার তারিখ[ঃ:]"),
            Regex("[A-Z][a-z]+ ব্যাংক"),
            Regex("ব্যাংক হাসপাতাল"),
            Regex("কমিশন সচিবালয়"),
            Regex("অধিদপ্তর"),
            Regex("বিশ্ববিদ্যালয়"),
            Regex("সরকারি কর্মকমিশন"),
        )

        val sectionBreaks = mutableListOf<Int>() // line indices
        sectionBreaks.add(0)

        for (i in allLines.indices) {
            val line = allLines[i].second.trim()
            if (line.isBlank() || line.length > 100) continue
            val isHeader = headerPatterns.any { it.containsMatchIn(line) }
            if (isHeader && i > (sectionBreaks.lastOrNull() ?: 0) + 15) {
                sectionBreaks.add(i)
            }
        }
        sectionBreaks.add(allLines.size)

        // Section text বের করো
        val result = mutableListOf<Pair<String, String>>()
        for (s in 0 until sectionBreaks.size - 1) {
            val start = sectionBreaks[s]
            val end = sectionBreaks[s + 1]
            if (end - start < 10) continue

            // Title: header এর কাছের কয়েকটা non-blank line
            val titleLines = allLines.subList(start, minOf(start + 8, end))
                .map { it.second.trim() }
                .filter { it.isNotBlank() && it.length < 80 }
                .take(3)
            val title = titleLines.joinToString(" — ").take(100).ifBlank { "Section ${s + 1}" }

            val text = allLines.subList(start, end).joinToString("\n") { it.second }
            if (text.trim().length > 200) {
                result.add(Pair(title, text.trim()))
            }
        }
        return result.ifEmpty { listOf(Pair("Full PDF", allLines.joinToString("\n") { it.second })) }
    }
}
