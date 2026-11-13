package com.tvplayer.app
import android.content.Context
object StreamCache {
    private const val PREFS = "stream_cache"
    private const val TTL_MS = 2 * 60 * 60 * 1000L
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
}
