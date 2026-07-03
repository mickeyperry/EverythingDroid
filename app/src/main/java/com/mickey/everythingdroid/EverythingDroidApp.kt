package com.mickey.everythingdroid

import android.app.Application
import com.mickey.everythingdroid.data.StreamProxy

class EverythingDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Bind the stream relay at launch so its port is up before any Play tap
        // and stays diagnosable (GET /log) for the life of the process.
        StreamProxy.warmUp()
    }
}
