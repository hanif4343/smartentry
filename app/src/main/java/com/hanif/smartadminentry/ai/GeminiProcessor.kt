package com.hanif.smartadminentry.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object GeminiProcessor {

    private const val TAG = "GeminiProcessor"
    private const val SCRIPT_URL = "https://script.google.com/macros/s/AKfycbyjF7iFX0H_rFuJgMJYo70DC7KRX1lBXU7m7NoZCwf6VTJfRm6Iyw6hOcN2q_UKbxxgQg/exec"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun buildPrompt(ocrText: String, targetSheet: String, subject: String, subTopic: String): String {
        return """
Exam question formatter. OCR text analyze kore auto-detect koro content type, then format koro.

Subject: ${subject.ifBlank { "—" }} | Sub-Topic: ${subTopic.ifBlank { "—" }} | Sheet: $targetSheet

DETECT TYPE:
A=STUDY: question+answer ache, kono k/kh/g/gh option nei.
B=MCQ: k. kh. g. gh. option + "u." answer ache.
C=WRITTEN: boro question, sub-questions ache.

OUTPUT FORMAT (each line):
TYPE A: question;answer
TYPE B: question;opt1;opt2;opt3;opt4;answer_text
TYPE C: full_question;answer

RULES:
- Remove serial numbers, "উত্তর:" prefix, footer, page ref
- No semicolon inside fields, use pipe(|) instead
- MCQ answer: write option TEXT not "ক"/"খ"

OCR:
$ocrText
""".trimIndent()
    }

    suspend fun process(
        apiKey: String,
        ocrText: String,
        targetSheet: String = "Quiz",
        subject: String = "",
        subTopic: String = ""
    ): GeminiResult = withContext(Dispatchers.IO) {

        if (ocrText.isBlank()) return@withContext GeminiResult.Error("OCR text খালি")

        val prompt = buildPrompt(ocrText, targetSheet, subject, subTopic)

        try {
            val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
            val url = "$SCRIPT_URL?action=getAI&prompt=$encodedPrompt"

            Log.d(TAG, "URL length: ${url.length}")

            val req = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(req).execute()
            val body = response.body?.string() ?: ""

            Log.d(TAG, "Response code: ${response.code}")
            Log.d(TAG, "Body (300): ${body.take(300)}")

            if (!response.isSuccessful) {
                return@withContext GeminiResult.Error("Script Error ${response.code}")
            }

            // Parse Gemini JSON returned by Apps Script getAI
            val rawText = try {
                val json = JSONObject(body)
                // Check for error in response
                if (json.has("error")) {
                    val errMsg = json.getString("error")
                    Log.e(TAG, "Gemini error: $errMsg")
                    return@withContext GeminiResult.Error("AI Error: $errMsg")
                }
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            } catch (e: Exception) {
                Log.e(TAG, "Parse error: ${e.message} | body: ${body.take(300)}")
                return@withContext GeminiResult.Error("Response parse error: ${e.message}\n\nBody: ${body.take(200)}")
            }

            val lines = rawText.lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotBlank()
                    && !line.startsWith("```")
                    && !line.startsWith("TYPE")
                    && !line.startsWith("OUTPUT")
                    && !line.startsWith("DETECT")
                    && !line.startsWith("RULES")
                    && !line.startsWith("OCR")
                    && line.contains(";")
                }

            if (lines.isEmpty()) {
                return@withContext GeminiResult.Error("AI থেকে কোনো প্রশ্ন parse হয়নি।\n\nRaw: ${rawText.take(200)}")
            }

            Log.d(TAG, "Parsed ${lines.size} questions OK")
            GeminiResult.Success(lines)

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            GeminiResult.Error("নেটওয়ার্ক সমস্যা: ${e.message}")
        }
    }

    sealed class GeminiResult {
        data class Success(val lines: List<String>) : GeminiResult()
        data class Error(val message: String) : GeminiResult()
    }
}
