package com.tvplayer.app
import android.content.Context

object StreamCache {
    private const val PREFS = "stream_cache"
    private const val TTL_MS = 2 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 50

    fun get(context: Context, channelUrl: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entry = prefs.getString(channelUrl, null) ?: return null
        val parts = entry.split("|")
        if (parts.size != 2 || System.currentTimeMillis() - (parts[1].toLongOrNull() ?: 0L) > TTL_MS) return null
        return parts[0]
    }

    fun put(context: Context, channelUrl: String, m3u8: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val allEntries = prefs.all
        val editor = prefs.edit()
        if (allEntries.size >= MAX_ENTRIES) {
            val oldest = allEntries.entries
                .mapNotNull { entry ->
                    val parts = (entry.value as? String)?.split("|")
                    val time = parts?.getOrNull(1)?.toLongOrNull() ?: 0L
                    entry.key to time
                }
                .minByOrNull { it.second }
            oldest?.let { editor.remove(it.first) }
        }
        editor.putString(channelUrl, "$m3u8|${System.currentTimeMillis()}").apply()
    }

    fun clearAll(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
}
