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

    private fun buildPrompt(ocrText: String, targetSheet: String, subject: String, subTopic: String, qType: String = "MCQ"): String {
        val formatRule = when (qType) {
            "Study" -> """Wrap EVERY entry in curly braces: {question;answer}
Example output:
{রাষ্ট্রবিজ্ঞানের জনক কাকে বলা হয়?;এরিস্টটল}
{'The Laws' গ্রন্থের রচয়িতা কে?;প্লেটো}
{Man is born free — উক্তিটি কার?;জ্যাঁ জ্যাক রুশো}"""
            "Written" -> """Wrap EVERY entry in curly braces: {question;answer}
Same category sub-questions go in ONE entry.
Example output:
{এক কথায় প্রকাশ: ক. হরিণের চামড়া খ. উপকারীর অপকার করে যে গ. আগে জন্মেছে যে;অজীন | কৃতঘ্ন | অগ্রজ}
{সন্ধি বিচ্ছেদ: ক. সঞ্চয় খ. গ্রন্থাগার গ. প্রতীক্ষা;সম+চয় | গ্রন্থ+আগার | প্রতি+ঈক্ষা}
{নিচের প্রশ্নগুলোর উত্তর দিন: ক. বাল্মীকি প্রতিভার রচয়িতা কে? খ. ক্রিয়াপদের মূল অংশকে কী বলে?;রবীন্দ্রনাথ ঠাকুর | ধাতু}"""
            else -> """One line per MCQ: question;opt1;opt2;opt3;opt4;correct_answer_text
Answer field: write option TEXT not ক/খ/গ/ঘ
Example output:
Slow and steady ... the race.;win;wins;has won;won;wins
বাংলাদেশের রাজধানী?;ঢাকা;চট্টগ্রাম;খুলনা;রাজশাহী;ঢাকা"""
        }
        return """
Convert OCR text into structured question data. Type: $qType
Subject: ${subject.ifBlank { "—" }} | Sub-Topic: ${subTopic.ifBlank { "—" }}

OUTPUT FORMAT:
$formatRule

STRICT RULES:
- Output data ONLY — no labels, no headers, no explanations
- NO prefix like "A:", "B:", "১.", nothing before the data
- Remove serial numbers and "উত্তর:" prefix from answers
- No semicolon inside a field — use pipe(|) instead
- OCR এর সব প্রশ্ন দাও, কোনোটা বাদ দিও না

OCR TEXT:
$ocrText
""".trimIndent()
    }

    suspend fun process(
        apiKey: String,
        ocrText: String,
        targetSheet: String = "Quiz",
        subject: String = "",
        subTopic: String = "",
        qType: String = "MCQ"
    ): GeminiResult = withContext(Dispatchers.IO) {

        if (ocrText.isBlank()) return@withContext GeminiResult.Error("OCR text খালি")

        val prompt = buildPrompt(ocrText, targetSheet, subject, subTopic, qType)

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
