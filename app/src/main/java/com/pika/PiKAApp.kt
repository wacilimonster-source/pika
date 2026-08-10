package com.pika

import android.app.Application
import com.pika.data.SourcePrefs
import com.pika.core.source.SourceManager

class PiKAApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SourcePrefs.init(this)
        SourceManager.init()
    }
}