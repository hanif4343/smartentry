package com.hanif.smartadminentry

import android.app.Application
import android.content.Intent
import android.os.Build
import com.hanif.smartadminentry.data.AppPrefs
import com.hanif.smartadminentry.ui.CrashActivity

class SmartEntryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)

        // Global crash handler — screen এ error দেখাবে
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val msg = buildString {
                    append("❌ CRASH\n\n")
                    append("Error: ${throwable.javaClass.simpleName}\n")
                    append("Message: ${throwable.message}\n\n")
                    append("Stack:\n")
                    throwable.stackTrace.take(8).forEach { append("  $it\n") }
                    if (throwable.cause != null) {
                        append("\nCaused by: ${throwable.cause?.javaClass?.simpleName}\n")
                        append("${throwable.cause?.message}\n")
                    }
                }
                val intent = Intent(this, CrashActivity::class.java).apply {
                    putExtra("crash_msg", msg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
