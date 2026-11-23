package com.tvplayer.app
import android.content.Context
import android.os.StatFs
import java.io.File

object CacheManager {
    private const val PREFS = "cache_manager"
    private const val KEY_LAST_CLEANUP = "last_cleanup"
    private const val MIN_INTERVAL_MS = 3 * 60 * 60 * 1000L
    private const val FORCE_CLEANUP_THRESHOLD = 15 * 1024 * 1024

    fun maybeCleanup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CLEANUP, 0L)
        val now = System.currentTimeMillis()

        var currentCacheSize = 0L
        context.cacheDir.walkTopDown().forEach { if (it.isFile) currentCacheSize += it.length() }

        if (now - last < MIN_INTERVAL_MS && currentCacheSize < FORCE_CLEANUP_THRESHOLD) return
        prefs.edit().putLong(KEY_LAST_CLEANUP, now).apply()
        Thread { performCleanup(context.applicationContext) }
            .apply { name = "CacheCleanup"; isDaemon = true }.start()
    }

    private fun performCleanup(context: Context) {
        try {
            val updateApk = File(context.cacheDir, "update.apk")
            if (updateApk.exists()) {
                val sizeMb = updateApk.length() / 1024 / 1024
                updateApk.delete()
                CrashLogger.info("Cleanup", "Удалён update.apk ($sizeMb МБ)")
            }

            StreamCache.cleanup(context)
            ThumbCache.evictIfNeeded(context)

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

            var cacheSize = 0L
            context.cacheDir.walkTopDown().forEach { if (it.isFile) cacheSize += it.length() }
            if (cacheSize > 10 * 1024 * 1024) {
                val mb = cacheSize / 1024 / 1024
                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                CrashLogger.info("Cleanup", "cacheDir очищен ($mb МБ)")
                cacheSize = 0L
            }

            var filesSize = 0L
            context.filesDir.walkTopDown().forEach { if (it.isFile) filesSize += it.length() }
            val filesMb = filesSize / 1024 / 1024
            if (filesSize > 30 * 1024 * 1024) {
                CrashLogger.error("Cleanup", "⚠️ filesDir=${filesMb}МБ > 30МБ! Аварийная очистка")
                ChannelCache.clear(context)
                CrashLogger.clear(context)
                filesSize = 0L
                context.filesDir.walkTopDown().forEach { if (it.isFile) filesSize += it.length() }
            } else if (filesSize > 25 * 1024 * 1024) {
                CrashLogger.info("Cleanup", "⚠️ filesDir=${filesMb}МБ > 25МБ (аномалия)")
            }

            val stat = StatFs(context.filesDir.path)
            val freeMb = stat.availableBytes / 1024 / 1024
            if (freeMb < 100) {
                CrashLogger.info("Cleanup", "⚠️ Свободно ${freeMb}МБ (<100МБ)")
            }

            CrashLogger.info("Cleanup", "Размеры: files=${filesSize/1024}КБ, cache=${cacheSize/1024}КБ, free=${freeMb}МБ")
        } catch (e: Exception) {
            CrashLogger.error("Cleanup", "Ошибка автоочистки", e)
        }
    }
}
