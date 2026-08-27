package com.delrogue.grooverider

import android.app.Application
import com.delrogue.grooverider.telemetry.CrashReporter

class GrooveriderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
