package com.hanif.smartadminentry.data

import android.net.Uri

data class ImportImage(
    val uri: Uri,
    var status: Status = Status.PENDING,
    var ocrText: String = "",
    var errorMsg: String = ""
) {
    enum class Status { PENDING, OCR_RUNNING, OCR_DONE, AI_RUNNING, AI_DONE, ERROR }
}

data class ParsedQuestion(
    val raw: String,           // full raw line from Gemini
    val question: String,
    val opt1: String = "",
    val opt2: String = "",
    val opt3: String = "",
    val opt4: String = "",
    val correct: String = "",
    val explanation: String = "",
    val qType: String = "MCQ", // MCQ or Written
    var selected: Boolean = true,
    var editedRaw: String = raw
) {
    companion object {
        fun parse(line: String): ParsedQuestion {
            val parts = line.split(";")
            return when {
                parts.size >= 6 -> ParsedQuestion(
                    raw = line,
                    question = parts[0].trim(),
                    opt1 = parts[1].trim(),
                    opt2 = parts[2].trim(),
                    opt3 = parts[3].trim(),
                    opt4 = parts[4].trim(),
                    correct = parts[5].trim(),
                    explanation = if (parts.size > 6) parts[6].trim() else "",
                    qType = "MCQ",
                    editedRaw = line
                )
                parts.size >= 2 -> ParsedQuestion(
                    raw = line,
                    question = parts[0].trim(),
                    correct = parts[1].trim(),
                    explanation = if (parts.size > 2) parts[2].trim() else "",
                    qType = "Written",
                    editedRaw = line
                )
                else -> ParsedQuestion(
                    raw = line,
                    question = line.trim(),
                    qType = "Written",
                    editedRaw = line
                )
            }
        }
    }
}
