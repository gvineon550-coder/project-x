package com.tvplayer.app
import android.content.Context

object StreamCache {
    private const val PREFS = "stream_cache"
    private const val TTL_MS = 4 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 120

    fun get(context: Context, channelUrl: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entry = prefs.getString(channelUrl, null) ?: return null
        val parts = entry.split("|")
        if (parts.size != 2) return null
        val url = parts[0]
        val ts = parts[1].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - ts > TTL_MS) {
            prefs.edit().remove(channelUrl).apply()
            return null
        }
        return url
    }

    fun put(context: Context, channelUrl: String, m3u8: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = prefs.all
        if (all.size >= MAX_ENTRIES) {
            val entries = all.entries
                .filter { it.value is String }
                .map { e ->
                    val parts = (e.value as String).split("|")
                    val ts = if (parts.size == 2) parts[1].toLongOrNull() ?: 0L else 0L
                    e.key to ts
                }
                .sortedBy { it.second }
                .take(maxOf(1, all.size - MAX_ENTRIES + 1))
            val editor = prefs.edit()
            entries.forEach { editor.remove(it.first) }
            editor.apply()
            if (entries.isNotEmpty()) {
                CrashLogger.info("StreamCache", "Эвикция ${entries.size} старых (лимит $MAX_ENTRIES)")
            }
        }
        prefs.edit().putString(channelUrl, "$m3u8|${System.currentTimeMillis()}").apply()
    }

    fun invalidate(context: Context, channelUrl: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(channelUrl).apply()
    }

    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    fun cleanup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = prefs.all
        if (all.isEmpty()) return
        val editor = prefs.edit()
        var removed = 0
        val now = System.currentTimeMillis()
        for ((key, value) in all) {
            if (value is String) {
                val parts = value.split("|")
                if (parts.size != 2) {
                    editor.remove(key); removed++
                } else {
                    val ts = parts[1].toLongOrNull() ?: 0L
                    if (now - ts > TTL_MS) {
                        editor.remove(key); removed++
                    }
                }
            } else {
                editor.remove(key); removed++
            }
        }
        editor.apply()
        if (removed > 0) CrashLogger.info("StreamCache", "Очищено $removed просроченных")
    }
}
