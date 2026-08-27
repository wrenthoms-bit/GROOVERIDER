package com.delrogue.grooverider.telemetry

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash reporting (spec M8): no network, no third-party SDK --
 * just a plain-text log the user can attach to a bug report by hand. Chains
 * to the platform's default handler so crash behaviour (process death,
 * system dialog) is unchanged.
 */
object CrashReporter {
    private const val MAX_LOG_BYTES = 512 * 1024

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { logCrash(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        val file = logFile(context)
        if (file.length() > MAX_LOG_BYTES) file.delete()   // simple cap, no rotation machinery needed
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file.appendText("\n---- $timestamp on thread ${thread.name} ----\n$sw")
    }

    fun logFile(context: Context): File = File(context.filesDir, "crash_log.txt")

    fun readLog(context: Context): String =
        logFile(context).let { if (it.exists()) it.readText() else "" }
}
