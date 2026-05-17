package com.hanif.smartadminentry.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hanif.smartadminentry.R
import com.hanif.smartadminentry.data.AppPrefs
import com.hanif.smartadminentry.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val bulk    = result.data?.getStringExtra("bulk_text") ?: return@registerForActivityResult
            val sheet   = result.data?.getStringExtra("target_sheet") ?: "Quiz"
            val subject = result.data?.getStringExtra("subject") ?: ""
            val subTopic= result.data?.getStringExtra("sub_topic") ?: ""
            injectBulkText(bulk, sheet, subject, subTopic)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.title = "Smart Entry"
            requestPerms()
            setupWebView()
            setupFab()
            setupBackPress()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate crash: ${e.message}", e)
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack()
                else finish()
            }
        })
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        else
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/entry.html")
        }
    }

    private fun setupFab() {
        binding.fabImport.setOnClickListener { openImport() }
    }

    private fun openImport() {
        importLauncher.launch(Intent(this, ImportActivity::class.java))
    }

    private fun injectBulkText(bulk: String, sheet: String, subject: String, subTopic: String) {
        val escaped = bulk
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

        val js = """
            (function(){
                try {
                    if(typeof switchMode==='function') switchMode('Bulk');
                    var s=document.getElementById('bulk_target');
                    if(s) s.value='$sheet';
                    var st=document.getElementById('bulk_sub_topic');
                    if(st) st.value='$subTopic';
                    var ta=document.getElementById('bulk_data');
                    if(ta){ ta.value='$escaped'; }
                    return 'ok';
                } catch(e){ return 'err:'+e.message; }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js) { res ->
            val count = bulk.lines().count { it.contains(";") }
            Toast.makeText(this, "✅ $count টি প্রশ্ন Bulk এ লোড হয়েছে!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_import   -> { openImport(); true }
        R.id.action_settings -> { showSettings(); true }
        R.id.action_reload   -> { binding.webView.reload(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showSettings() {
        val view    = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etGemini= view.findViewById<android.widget.EditText>(R.id.et_gemini_key)
        val etScript= view.findViewById<android.widget.EditText>(R.id.et_script_url)
        etGemini.setText(AppPrefs.geminiApiKey)
        etScript.setText(AppPrefs.scriptUrl)
        AlertDialog.Builder(this)
            .setTitle("⚙️ Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                AppPrefs.geminiApiKey = etGemini.text.toString().trim()
                AppPrefs.scriptUrl    = etScript.text.toString().trim()
                Toast.makeText(this, "✅ Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
