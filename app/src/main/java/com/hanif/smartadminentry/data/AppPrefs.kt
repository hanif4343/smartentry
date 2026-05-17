package com.hanif.smartadminentry.data

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val PREF_NAME = "smart_entry_prefs"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var geminiApiKey: String
        get() = _ctx?.let { prefs(it).getString("gemini_key", "") ?: "" } ?: ""
        set(value) { _ctx?.let { prefs(it).edit().putString("gemini_key", value).apply() } }

    var scriptUrl: String
        get() = _ctx?.let { prefs(it).getString("script_url", "") ?: "" } ?: ""
        set(value) { _ctx?.let { prefs(it).edit().putString("script_url", value).apply() } }

    var defaultSubject: String
        get() = _ctx?.let { prefs(it).getString("default_subject", "") ?: "" } ?: ""
        set(value) { _ctx?.let { prefs(it).edit().putString("default_subject", value).apply() } }

    var defaultSubTopic: String
        get() = _ctx?.let { prefs(it).getString("default_subtopic", "") ?: "" } ?: ""
        set(value) { _ctx?.let { prefs(it).edit().putString("default_subtopic", value).apply() } }

    var defaultSheet: String
        get() = _ctx?.let { prefs(it).getString("default_sheet", "Quiz") ?: "Quiz" } ?: "Quiz"
        set(value) { _ctx?.let { prefs(it).edit().putString("default_sheet", value).apply() } }

    var lastImportCount: Int
        get() = _ctx?.let { prefs(it).getInt("last_import_count", 0) } ?: 0
        set(value) { _ctx?.let { prefs(it).edit().putInt("last_import_count", value).apply() } }

    private var _ctx: Context? = null
    fun init(ctx: Context) { _ctx = ctx.applicationContext }
}
