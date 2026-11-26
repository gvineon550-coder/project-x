package com.tvplayer.app
import android.content.Context
import java.io.File

object CacheManager {
    private const val PREFS = "cache_manager"
    private const val KEY_LAST_CLEANUP = "last_cleanup"
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 часов

    fun maybeCleanup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CLEANUP, 0L)
        val now = System.currentTimeMillis()
        if (now - last < MIN_INTERVAL_MS) return
        
        prefs.edit().putLong(KEY_LAST_CLEANUP, now).apply()
        Thread { performCleanup(context.applicationContext) }
            .apply { name = "CacheCleanup"; isDaemon = true }.start()
    }

    fun cleanYtdlpTemp(context: Context) {
        try {
            val dir = File(context.cacheDir, "ytdlp_tmp")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    private fun performCleanup(context: Context) {
        try {
            // 1. Удаляем строго определенные папки (без ExoPlayer)
            File(context.cacheDir, "image_cache").deleteRecursively()
            File(context.cacheDir, "doh_cache").deleteRecursively()
            File(context.cacheDir, "ytdlp_tmp").deleteRecursively()
            File(context.cacheDir, "update.apk").delete()

            StreamCache.cleanup(context)
            ThumbCache.evictIfNeeded(context)

            // 2. Удаляем старые файлы в cacheDir (>7 дней)
            val sevenDays = 7L * 24 * 60 * 60 * 1000
            val now = System.currentTimeMillis()
            var oldRemoved = 0
            var oldBytes = 0L
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && (now - f.lastModified()) > sevenDays) {
                    oldBytes += f.length()
                    if (f.delete()) oldRemoved++
                } else if (f.isDirectory) {
                    f.listFiles()?.forEach { inner ->
                        if (inner.isFile && (now - inner.lastModified()) > sevenDays) {
                            oldBytes += inner.length()
                            if (inner.delete()) oldRemoved++
                        }
                    }
                }
            }
            if (oldRemoved > 0) {
                CrashLogger.info("Cleanup", "Удалено $oldRemoved старых файлов (${oldBytes/1024}КБ)")
            }

            // 3. Считаем итоговые размеры
            var cacheSize = 0L
            context.cacheDir.walkTopDown().forEach { if (it.isFile) cacheSize += it.length() }
            
            var filesSize = 0L
            context.filesDir.walkTopDown().forEach { if (it.isFile) filesSize += it.length() }
            val filesMb = filesSize / 1024 / 1024
            
            if (filesSize > 30 * 1024 * 1024) {
                CrashLogger.error("Cleanup", "⚠️ filesDir=${filesMb}МБ > 30МБ! Аварийная очистка")
                ChannelCache.clear(context)
                CrashLogger.clear(context)
                filesSize = 0L
                context.filesDir.walkTopDown().forEach { if (it.isFile) filesSize += it.length() }
            }

            CrashLogger.info("Cleanup", "Размеры: files=${filesSize/1024}КБ, cache=${cacheSize/1024}КБ")
        } catch (e: Exception) {
            CrashLogger.error("Cleanup", "Ошибка автоочистки", e)
        }
    }
}
