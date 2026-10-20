package com.tapvpn.app

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashLog {
    private const val FILE = "last-crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write(app, error, thread.name)
            previous?.uncaughtException(thread, error)
        }
    }

    fun write(context: Context, error: Throwable, thread: String = Thread.currentThread().name) {
        try {
            val writer = StringWriter()
            error.printStackTrace(PrintWriter(writer))
            file(context).writeText(
                "${error.javaClass.name}: ${error.message}\nthread=$thread\n$writer",
            )
            Log.e("TapVPN", "crash", error)
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String? {
        val saved = file(context)
        if (!saved.exists() || saved.length() == 0L) return null
        return saved.readText().take(4000)
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context): File = File(context.filesDir, FILE)
}
