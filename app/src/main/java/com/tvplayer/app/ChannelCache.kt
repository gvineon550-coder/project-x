package com.tvplayer.app
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ChannelCache {
    private const val FILE_NAME = "channels_cache.json"
    private const val FRESH_TTL_MS = 6 * 60 * 60 * 1000L
    private const val EXPIRE_TTL_MS = 24 * 60 * 60 * 1000L

    fun save(context: Context, channels: List<Channel>) {
        try {
            val arr = JSONArray()
            channels.forEach { ch ->
                val obj = JSONObject().put("name", ch.name).put("url", ch.url).put("logo", ch.logo).put("group", ch.group)
                arr.put(obj)
            }
            File(context.filesDir, FILE_NAME).writeText(JSONObject().put("timestamp", System.currentTimeMillis()).put("channels", arr).toString())
        } catch (_: Exception) {}
    }

    fun load(context: Context): List<Channel>? {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            if (System.currentTimeMillis() - root.optLong("timestamp", 0L) > EXPIRE_TTL_MS) return null
            val arr = root.optJSONArray("channels") ?: return null
            val result = mutableListOf<Channel>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                result.add(Channel(obj.optString("name"), obj.optString("url"), obj.optString("logo"), obj.optString("group", "VK Live")))
            }
            return result
        } catch (_: Exception) { return null }
    }

    fun isFresh(context: Context): Boolean = try {
        val file = File(context.filesDir, FILE_NAME)
        file.exists() && System.currentTimeMillis() - JSONObject(file.readText()).optLong("timestamp", 0L) < FRESH_TTL_MS
    } catch (_: Exception) { false }

    fun clear(context: Context) { try { File(context.filesDir, FILE_NAME).delete() } catch (_: Exception) {} }
}
