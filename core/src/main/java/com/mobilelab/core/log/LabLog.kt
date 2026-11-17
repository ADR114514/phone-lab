package com.mobilelab.core.log

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny in-app file logger. Every experiment writes a session.log next to its
 * CSV files, which makes failures auditable after the fact.
 */
object LabLog {

    @Volatile
    private var sink: File? = null

    @Volatile
    private var verbose = true

    private val lock = Any()

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun attach(file: File) {
        file.parentFile?.mkdirs()
        sink = file
        if (!file.exists()) file.writeText("")
    }

    fun detach() {
        sink = null
    }

    fun setVerbose(value: Boolean) {
        verbose = value
    }

    fun i(tag: String, message: String) {
        if (verbose) Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String) {
        if (verbose) Log.w(tag, message)
        append("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append("E", tag, message + (throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    private fun append(level: String, tag: String, message: String) {
        val file = sink ?: return
        runCatching {
            synchronized(lock) {
                file.appendText("${stamp.format(Date())} $level/$tag: $message\n")
            }
        }
    }

    fun contents(file: File): String = runCatching { file.readText() }.getOrDefault("")
}
