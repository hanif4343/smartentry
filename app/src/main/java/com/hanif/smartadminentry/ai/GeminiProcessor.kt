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
তুমি একটি পরীক্ষার প্রশ্নপত্র formatter। নিচের OCR text থেকে প্রশ্ন বের করে নির্দিষ্ট format এ দাও।

Subject: ${subject.ifBlank { "—" }} | Sub-Topic: ${subTopic.ifBlank { "—" }} | Sheet: ${'$'}targetSheet

════════════════════════
OUTPUT FORMAT (প্রতিটা প্রশ্ন = একটা line):
════════════════════════

MCQ:
প্রশ্ন;অপশন১;অপশন২;অপশন৩;অপশন৪;উত্তর

Written (sub-question সহ):
পুরো প্রশ্ন (ক+খ+গ সহ যদি থাকে না থাকলে শুধু প্রশ্ন);পুরো উত্তর | ব্যাখ্যা/সমাধান

════════════════════════
ANSWER RULE — সবচেয়ে গুরুত্বপূর্ণ:
════════════════════════

OCR এ উত্তর থাকে "উ. ক" / "উ. খ" / "উ. গ" / "উ. ঘ" আকারে।
"উ. ক" মানে ক. এর option text টাই উত্তর।
"উ. খ" মানে খ. এর option text টাই উত্তর।

উদাহরণ:
  প্রশ্ন: "Slow and steady ... the race."
  ক. win  খ. wins  গ. has won  ঘ. won   উ. খ
  → উত্তর = "won"  ✅  (ঘ এর text, কারণ উ. ঘ)

  প্রশ্ন: "Walk fast lest you ... miss the bus."
  ক. will  খ. can  গ. could  ঘ. should   উ. ঘ
  → উত্তর = "should"  ✅

উত্তর field এ শুধু plain text — "ক"/"খ"/"উ. ক" লিখবে না, option এর আসল text লিখবে।

════════════════════════
২-কলাম layout নিয়ম:
════════════════════════

OCR তে ২ কলামের প্রশ্ন একসাথে আসে। বাম ও ডান কলামকে আলাদা ২টি প্রশ্ন হিসেবে নাও।
এই pattern চেনো:
  "৫৮.Slow and steady ... the race.  ৬৮.What is the noun from of 'believe'?"
  "ক. win   খ. wins              ক. believe  খ. belief"
  "গ. has won  ঘ. won  উ. খ     গ. bnelievable  ঘ. believance  উ. খ"

→ এটা ২টা আলাদা প্রশ্ন।

════════════════════════
বাদ দেবে:
════════════════════════
- Serial number (৫৮., ৬৮., Q1. ইত্যাদি)
- Page reference: "পৃষ্ঠা: ৪০৬", "অগ্রদূত Competitive English; ৩য় সংস্করণ"
- Footer: "বিসিএস ও ব্যাংকসহ...", Facebook page, website
- field এর ভেতরে ; ব্যবহার নয় — | দিয়ে আলাদা করো

════════════════════════
গণিতের নিয়ম:
════════════════════════
ভগ্নাংশ: ${'$'}\frac{a}{b}$ | ঘাত: ${'$'}x^{2}$ | মূল: ${'$'}\sqrt{x}$
চিহ্ন: ∴ △ ∠ × ÷ ± ≤ ≥ ≠
Written গণিত explanation এ পুরো সমাধান:
∴ ক্রয়মূল্য = ${'$'}\frac{১০০ \times ৪৫০}{৯০}$ = **৫০০ টাকা**

════════════════════════
উদাহরণ OUTPUT:
════════════════════════
Slow and steady ... the race.;win;wins;has won;won;won
Walk fast lest you ... miss the bus.;will;can;could;should;should
What is the noun from of 'believe'?;believe;belief;believable;believance;believance
I look forward ... you.;to see;meeting;to hearing from;to meet;to meet
'Swan song' means-;First work;Last work;Middle work;Early work;Last work
Wriiten এর উদাহরন হল ocr text
এক কথায় প্রকাশ করুন-
ক. হরিণের চামড়া-
খ. উপকারীর অপকার করে যে-
গ. আগে জন্মেছে যে-
এখন আউটপুট হবে এরকম
এব কথায় প্রকাশ করুন-ক.হরিনের চামড়া-,উপকারীর অপকার করে যে-, আগে জন্মেছে যে-;অজীন,কৃতঘ্ন,অগ্রজ
════════════════════════
OCR TEXT:
════════════════════════
${'$'}ocrText
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
