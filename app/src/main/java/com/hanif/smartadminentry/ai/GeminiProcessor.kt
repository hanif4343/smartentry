package com.hanif.smartadminentry.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
Exam question formatter. Analyze OCR text, auto-detect type, output formatted lines.

Subject: ${subject.ifBlank { "—" }} | Sub-Topic: ${subTopic.ifBlank { "—" }} | Sheet: $targetSheet

TYPE DETECTION:
A=STUDY: question+answer, no k/kh/g/gh options.
B=MCQ: has k. kh. g. gh. options + "u." answer.
C=WRITTEN: long question with sub-parts.

OUTPUT (one line per question):
A: question;answer
B: question;opt1;opt2;opt3;opt4;answer_text (write option TEXT not "ক"/"খ")
C: question;answer

REMOVE: serial numbers, "উত্তর:" prefix, footer, page refs.
NO semicolon inside fields — use pipe(|) instead.

OCR TEXT:
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
            // POST JSON body — no URL length limit
            val jsonBody = JSONObject().apply {
                put("action", "getAI")
                put("prompt", prompt)
            }

            val req = Request.Builder()
                .url(SCRIPT_URL)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(req).execute()
            val body = response.body?.string() ?: ""

            Log.d(TAG, "Code: ${response.code} | Body: ${body.take(300)}")

            if (!response.isSuccessful) {
                return@withContext GeminiResult.Error("Script Error ${response.code}: ${body.take(100)}")
            }

            val rawText = try {
                val json = JSONObject(body)
                if (json.has("error")) {
                    return@withContext GeminiResult.Error("AI Error: ${json.getString("error")}")
                }
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            } catch (e: Exception) {
                Log.e(TAG, "Parse fail: ${e.message} | ${body.take(200)}")
                return@withContext GeminiResult.Error("Parse error: ${e.message} | ${body.take(150)}")
            }

            val lines = rawText.lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotBlank()
                    && !line.startsWith("```")
                    && !line.startsWith("TYPE")
                    && !line.startsWith("OUTPUT")
                    && !line.startsWith("DETECT")
                    && !line.startsWith("REMOVE")
                    && !line.startsWith("OCR")
                    && line.contains(";")
                }

            if (lines.isEmpty()) {
                return@withContext GeminiResult.Error("কোনো প্রশ্ন parse হয়নি।\nRaw: ${rawText.take(200)}")
            }

            Log.d(TAG, "OK: ${lines.size} questions")
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
