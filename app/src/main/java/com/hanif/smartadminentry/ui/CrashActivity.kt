package com.hanif.smartadminentry.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val msg: String = intent.getStringExtra("crash_msg") ?: "Unknown error"

        val layout: LinearLayout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(0xFF1A1A1A.toInt())

        val copyBtn: Button = Button(this)
        copyBtn.text = "Copy Error"
        copyBtn.setOnClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData = ClipData.newPlainText("crash", msg)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this@CrashActivity, "Copied!", Toast.LENGTH_SHORT).show()
        }

        val errorText: TextView = TextView(this)
        errorText.text = msg
        errorText.textSize = 12f
        errorText.setPadding(24, 24, 24, 24)
        errorText.setTextColor(0xFFFF4444.toInt())
        errorText.isTextSelectable = true

        val scrollView: ScrollView = ScrollView(this)
        scrollView.addView(errorText)

        layout.addView(copyBtn)
        layout.addView(scrollView)

        setContentView(layout)
    }
}
