package com.jarvis.edge

import android.app.Application
import com.jarvis.edge.core.CrashLog
import java.io.File

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(File(filesDir, CrashLog.FILE_NAME))
    }
}
