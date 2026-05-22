package com.hanif.smartadminentry.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiProcessor {

    private const val TAG = "GeminiProcessor"
    private const val MODEL = "gemini-2.0-flash-001"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun buildPrompt(ocrText: String, targetSheet: String, subject: String, subTopic: String): String {
        return """
তুমি একটি পরীক্ষার প্রশ্নপত্র formatter। নিচের OCR text বিশ্লেষণ করে প্রথমে content type detect করো, তারপর সেই অনুযায়ী format এ output দাও।

Subject: ${subject.ifBlank { "—" }} | Sub-Topic: ${subTopic.ifBlank { "—" }} | Sheet: $targetSheet

════════════════════════
STEP 1 — CONTENT TYPE AUTO-DETECT:
════════════════════════

নিচের ৩টি type এর মধ্যে একটি বেছে নাও:

TYPE A — STUDY (Q&A / Short Answer):
চেনার উপায়: প্রশ্ন + উত্তর আছে, কিন্তু ক/খ/গ/ঘ option নেই।
যেমন: "'The Laws' গ্রন্থের রচয়িতা কে? — উত্তর: প্লেটো"
যেমন: "সদগুণই জ্ঞান — উক্তিটি কার? — সক্রেটিস"

TYPE B — MCQ:
চেনার উপায়: ক. খ. গ. ঘ. চারটি option আছে এবং "উ." দিয়ে উত্তর দেওয়া আছে।

TYPE C — WRITTEN (Descriptive/Sub-questions):
চেনার উপায়: বড় প্রশ্ন, ক) খ) গ) sub-question আছে, বিস্তারিত উত্তর আছে।

════════════════════════
STEP 2 — FORMAT RULES BY TYPE:
════════════════════════

TYPE A — STUDY format (প্রতিটা line):
প্রশ্ন;উত্তর

উদাহরণ output:
'The Laws' গ্রন্থের রচয়িতা কে?;প্লেটো
এরিস্টটলের মতে নিকৃষ্ট শাসন ব্যবস্থা কোনটি?;গণতন্ত্র
Man is born free but everywhere he is in chains — উক্তিটি কার?;জ্যাঁ জ্যাক রুশো
রাষ্ট্রবিজ্ঞানের জনক কাকে বলা হয়?;এরিস্টটল

TYPE B — MCQ format (প্রতিটা line):
প্রশ্ন;অপশন১;অপশন২;অপশন৩;অপশন৪;উত্তর_text

ANSWER RULE: "উ. ক" মানে ক. এর text টাই উত্তর। উত্তর field এ "ক"/"খ" লিখবে না — option এর আসল text লিখবে।

উদাহরণ output:
Slow and steady ... the race.;win;wins;has won;won;wins
Walk fast lest you ... miss the bus.;will;can;could;should;should

TYPE C — WRITTEN format (প্রতিটা line):
পুরো প্রশ্ন (সব sub-question সহ);পুরো উত্তর বা ব্যাখ্যা

উদাহরণ output:
এক কথায় প্রকাশ করুন- ক.হরিণের চামড়া খ.উপকারীর অপকার করে যে গ.আগে জন্মেছে যে;অজীন | কৃতঘ্ন | অগ্রজ

════════════════════════
২-কলাম layout (MCQ):
════════════════════════
OCR এ ২ কলামে প্রশ্ন থাকলে বাম ও ডান আলাদা ২টি প্রশ্ন হিসেবে নাও।

════════════════════════
বাদ দেবে:
════════════════════════
- Serial number (১., ২., Q1. ইত্যাদি)
- Page reference, footer, website, Facebook page
- "উত্তর:" prefix — শুধু উত্তরের text রাখো
- field এর ভেতরে ; ব্যবহার নয় — | দিয়ে আলাদা করো

════════════════════════
গণিতের নিয়ম:
════════════════════════
ভগ্নাংশ: ${'$'}\frac{a}{b}$ | ঘাত: ${'$'}x^{2}$ | মূল: ${'$'}\sqrt{x}$
চিহ্ন: ∴ △ ∠ × ÷ ± ≤ ≥ ≠

════════════════════════
OCR TEXT:
════════════════════════
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

        if (apiKey.isBlank()) return@withContext GeminiResult.Error("Gemini API Key নেই। Settings এ দিন।")
        if (ocrText.isBlank()) return@withContext GeminiResult.Error("OCR text খালি")

        val prompt = buildPrompt(ocrText, targetSheet, subject, subTopic)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey"

        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.05)
                put("maxOutputTokens", 8192)
            })
        }

        try {
            val req = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(req).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "API error ${response.code}: $body")
                return@withContext GeminiResult.Error("API Error ${response.code}")
            }

            val rawText = JSONObject(body)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()

            val lines = rawText.lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotBlank()
                    && !line.startsWith("```")
                    && !line.startsWith("═")
                    && !line.startsWith("OUTPUT")
                    && !line.startsWith("MCQ")
                    && !line.startsWith("Written")
                    && !line.startsWith("উদাহরণ")
                    && !line.startsWith("TYPE")
                    && !line.startsWith("STEP")
                    && line.contains(";")
                }

            Log.d(TAG, "Gemini OK: ${lines.size} questions")
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
