package com.kalibox.app.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private var logFile: File? = null
    private var crashFlag: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "kalibox.log").also { if (it.length() > 2_000_000) it.writeText("") }
        crashFlag = File(dir, "crash.flag")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = stack(e)
                write("FATAL", "crash", "Uncaught exception in ${t.name}", text)
                crashFlag?.writeText((e.message ?: e.javaClass.simpleName) + "\n\n" + text.take(1500))
            } catch (_: Throwable) { }
            previous?.uncaughtException(t, e)
        }
    }

    /** Returns the text of a crash from the previous run (once), or null. */
    fun consumeCrash(): String? {
        val f = crashFlag ?: return null
        if (!f.exists()) return null
        val text = try { f.readText() } catch (_: Exception) { "Unknown crash" }
        f.delete()
        return text
    }

    fun tail(maxChars: Int = 6000): String = try {
        val t = logFile?.readText() ?: ""
        if (t.length > maxChars) t.takeLast(maxChars) else t.ifEmpty { "(log is empty)" }
    } catch (e: Exception) { "Cannot read log: ${e.message}" }

    fun i(tag: String, msg: String) { Log.i(tag, msg); write("I", tag, msg, null) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t); write("E", tag, msg, t?.let { stack(it) })
    }

    private fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String, extra: String?) {
        try {
            logFile?.appendText("${fmt.format(Date())} $level/$tag: $msg\n" + (extra?.let { it + "\n" } ?: ""))
        } catch (_: Throwable) { }
    }

    fun shareIntent(ctx: Context): Intent? {
        val f = logFile ?: return null
        if (!f.exists()) f.writeText("(empty log)\n")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "KaliBox logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share logs")
    }
}
