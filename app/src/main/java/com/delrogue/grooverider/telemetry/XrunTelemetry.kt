package com.delrogue.grooverider.telemetry

import android.content.Context
import com.delrogue.grooverider.engine.EngineMeters
import java.io.File

/**
 * Local-only xRun telemetry (spec M8): appends a line only when the xRun or
 * buffer-growth count actually changes, so a clean session writes nothing.
 * Never leaves the device -- this is a diagnostic log for the person holding
 * the phone, not analytics.
 */
class XrunTelemetry(context: Context) {
    private val file = File(context.filesDir, "xrun_log.txt")
    private var lastXruns = -1
    private var lastBufferGrows = -1

    fun onMeters(meters: EngineMeters) {
        if (meters.xruns == lastXruns && meters.bufferGrows == lastBufferGrows) return
        lastXruns = meters.xruns
        lastBufferGrows = meters.bufferGrows
        file.appendText(
            "${System.currentTimeMillis()} xruns=${meters.xruns} bufferGrows=${meters.bufferGrows} " +
                "bufferFrames=${meters.bufferFrames} cpuLoad=${"%.2f".format(meters.cpuLoad)}\n"
        )
    }

    companion object {
        fun readLog(context: Context): String =
            File(context.filesDir, "xrun_log.txt").let { if (it.exists()) it.readText() else "" }
    }
}
