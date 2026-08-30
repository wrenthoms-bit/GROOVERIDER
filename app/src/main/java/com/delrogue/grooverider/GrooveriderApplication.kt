package com.delrogue.grooverider

import android.app.Application
import com.delrogue.grooverider.engine.GrooveriderEngine
import com.delrogue.grooverider.telemetry.CrashReporter

class GrooveriderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Construct the native engine object up front (cheap: no stream is
        // opened here) so a source picked on the Sources tab before the
        // audio service ever starts still lands in the grain engine instead
        // of silently no-op'ing against a null gEngine (nativeLoadSource
        // returns 0 frames when the engine hasn't been created yet).
        GrooveriderEngine.create()
    }
}
