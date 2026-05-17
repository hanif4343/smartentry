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
        val msg = intent.getStringExtra("crash_msg") ?: "Unknown error"
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(0xFF1A1A1A.toInt())
        val btn = Button(this)
        btn.text = "Copy Error"
        btn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash", msg))
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        }
        val tv = TextView(this)
        tv.text = msg
        tv.textSize = 12f
        tv.setPadding(24, 24, 24, 24)
        tv.setTextColor(0xFFFF4444.toInt())
        tv.isTextSelectable = true
        val scroll = ScrollView(this)
        scroll.addView(tv)
        layout.addView(btn)
        layout.addView(scroll)
        setContentView(layout)
    }
}
