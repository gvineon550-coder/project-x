package com.tvplayer.app
import android.content.Context

object ThumbCache {
    private const val PREFS = "thumb_cache"
    private const val MAX_ENTRIES = 30

    fun get(context: Context, url: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(url, null)

    fun put(context: Context, url: String, thumb: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (prefs.all.size >= MAX_ENTRIES) {
            val toRemove = prefs.all.keys.take(prefs.all.size - MAX_ENTRIES + 1)
            toRemove.forEach { editor.remove(it) }
        }
        editor.putString(url, thumb).apply()
        CrashLogger.info("ThumbCache", "Сохранено: ${url.takeLast(30)}")
    }

    fun evictIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.all.size <= MAX_ENTRIES) return
        val editor = prefs.edit()
        prefs.all.keys.take(prefs.all.size - MAX_ENTRIES).forEach { editor.remove(it) }
        editor.apply()
        CrashLogger.info("ThumbCache", "Эвикция до $MAX_ENTRIES записей")
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
