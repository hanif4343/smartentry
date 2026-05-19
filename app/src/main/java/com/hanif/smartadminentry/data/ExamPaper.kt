package com.hanif.smartadminentry.data

data class ExamPaper(
    val id: String,
    val title: String,
    val rawText: String,
    var formattedBulk: String = "",
    var status: Status = Status.PENDING,
    var errorMsg: String = "",
    var subject: String = "",
    var subTopic: String = "",
    var sheet: String = "QBank"
) {
    enum class Status { PENDING, AI_RUNNING, DONE, UPLOADING, UPLOADED, ERROR }

    val questionCount: Int get() =
        formattedBulk.lines().count { it.contains(";") }
}
