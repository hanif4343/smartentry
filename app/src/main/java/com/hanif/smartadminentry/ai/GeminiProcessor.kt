package com.hanif.smartadminentry.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
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
তুমি একটি পরীক্ষার প্রশ্নপত্র formatter। নিচের OCR text বিশ্লেষণ করে প্রথমে content type detect করো, তারপর সেই অনুযায়ী format এ output দাও।

Subject: ${subject.ifBlank { "—" }} | Sub-Topic: ${subTopic.ifBlank { "—" }} | Sheet: $targetSheet

CONTENT TYPE AUTO-DETECT:

TYPE A — STUDY: প্রশ্ন + উত্তর আছে, কিন্তু ক/খ/গ/ঘ option নেই।
TYPE B — MCQ: ক. খ. গ. ঘ. option + "উ." দিয়ে উত্তর আছে।
TYPE C — WRITTEN: বড় প্রশ্ন, sub-question আছে, বিস্তারিত উত্তর আছে।

FORMAT RULES:

TYPE A output (প্রতি line): প্রশ্ন;উত্তর
উদাহরণ:
'The Laws' গ্রন্থের রচয়িতা কে?;প্লেটো
রাষ্ট্রবিজ্ঞানের জনক কাকে বলা হয়?;এরিস্টটল

TYPE B output (প্রতি line): প্রশ্ন;অপশন১;অপশন২;অপশন৩;অপশন৪;উত্তর_text
ANSWER RULE: "উ. ক" মানে ক এর text। "উ. ঘ" মানে ঘ এর text। "ক"/"খ" লিখবে না।

TYPE C output (প্রতি line): পুরো প্রশ্ন;উত্তর

নিয়ম:
- Serial number বাদ দাও
- "উত্তর:" prefix বাদ দাও, শুধু উত্তর রাখো
- field এর ভেতরে semicolon নয়, pipe (|) দিয়ে আলাদা করো
- footer, page reference, website বাদ দাও

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
            // POST as form data to avoid URL length limit
            val formBody = FormBody.Builder()
                .add("action", "getAI")
                .add("prompt", prompt)
                .build()

            val req = Request.Builder()
                .url(SCRIPT_URL)
                .post(formBody)
                .build()

            val response = client.newCall(req).execute()
            val body = response.body?.string() ?: ""

            Log.d(TAG, "Script response code: ${response.code}")
            Log.d(TAG, "Body preview: ${body.take(300)}")

            if (!response.isSuccessful) {
                return@withContext GeminiResult.Error("Script Error ${response.code}")
            }

            // Apps Script getAI returns raw Gemini JSON
            val rawText = try {
                JSONObject(body)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            } catch (e: Exception) {
                Log.e(TAG, "JSON parse failed: ${e.message} | body: ${body.take(200)}")
                return@withContext GeminiResult.Error("Response parse error: ${e.message}")
            }

            val lines = rawText.lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotBlank()
                    && !line.startsWith("```")
                    && !line.startsWith("TYPE")
                    && !line.startsWith("FORMAT")
                    && !line.startsWith("OCR")
                    && !line.startsWith("উদাহরণ")
                    && line.contains(";")
                }

            if (lines.isEmpty()) {
                Log.w(TAG, "No lines parsed. Raw text: $rawText")
                return@withContext GeminiResult.Error("AI থেকে কোনো প্রশ্ন parse হয়নি।")
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
