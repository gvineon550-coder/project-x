package com.tvplayer.app
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val LOG_FILE = "app.log"
    private const val MAX_SIZE = 500 * 1024
    private var appContext: Context? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("CRASH", "Uncaught exception in thread ${thread.name}: ${throwable.javaClass.simpleName}")
            log("CRASH", throwable.stackTraceToString())
            defaultHandler?.uncaughtException(thread, throwable)
        }
        log("INIT", "═══ App started ═══")
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, LOG_FILE)
            if (file.exists() && file.length() > MAX_SIZE) {
                val content = file.readText()
                val cut = content.length / 2
                val firstNewline = content.indexOf('\n', cut)
                val trimmed = if (firstNewline > 0) content.substring(firstNewline + 1) else content
                file.writeText(trimmed)
            }
            file.appendText("[${dateFormat.format(Date())}] [$tag] $message\n")
        } catch (_: Exception) {}
    }

    fun info(tag: String, message: String) = log(tag, message)
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log(tag, "❌ $message")
        throwable?.let { log(tag, it.stackTraceToString()) }
    }
    fun readAll(context: Context): String = try { File(context.filesDir, LOG_FILE).takeIf { it.exists() }?.readText() ?: "Пусто" } catch (_: Exception) { "Ошибка чтения" }
    fun clear(context: Context) { try { File(context.filesDir, LOG_FILE).delete() } catch (_: Exception) {} }
    fun share(context: Context) {
        try {
            val file = File(context.filesDir, LOG_FILE)
            if (!file.exists()) return
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Отправить лог"))
        } catch (_: Exception) {}
    }
}
