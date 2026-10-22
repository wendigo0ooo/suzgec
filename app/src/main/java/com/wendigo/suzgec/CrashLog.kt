package com.wendigo.suzgec

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLog {

    private const val FILE_NAME = "suzgec_log.txt"
    private const val MAX_BYTES = 256 * 1024
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                append(app, "ÇÖKME", Log.getStackTraceString(error))
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    fun append(context: Context, tag: String, message: String) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                val old = read(context)
                file.writeText(old.takeLast(MAX_BYTES / 2))
            }
            val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date())
            file.appendText("[$stamp] $tag: ${message.take(4000)}\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(context: Context): String = try {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.readText().takeLast(8000) else ""
    } catch (_: Exception) {
        ""
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }
}
