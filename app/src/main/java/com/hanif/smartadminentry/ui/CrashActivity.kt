package com.hanif.smartadminentry.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val msg = intent.getStringExtra("crash_msg") ?: "Unknown error"

        val tv = TextView(this).apply {
            text = msg
            textSize = 12f
            setPadding(24, 24, 24, 24)
            setTextColor(0xFFFF4444.toInt())
            setBackgroundColor(0xFF1A1A1A.toInt())
            isTextSelectable = true
        }

        val btn = Button(this).apply {
            text = "📋 Copy Error"
            setOnClickListener {
                val clip = ClipboardManager::class.java
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", msg))
                Toast.makeText(this@CrashActivity, "Copied!", Toast.LENGTH_SHORT).show()
            }
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            addView(btn)
            addView(ScrollView(this@CrashActivity).apply { addView(tv) })
        }

        setContentView(layout)
    }
}
