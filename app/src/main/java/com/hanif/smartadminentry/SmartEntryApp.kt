package com.hanif.smartadminentry

import android.app.Application
import com.hanif.smartadminentry.data.AppPrefs

class SmartEntryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
    }
}
