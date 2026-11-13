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
                val obj = JSONObject()
                obj.put("name", ch.name)
                obj.put("url", ch.url)
                obj.put("logo", ch.logo)
                obj.put("group", ch.group)
                arr.put(obj)
            }
            val root = JSONObject()
            root.put("timestamp", System.currentTimeMillis())
            root.put("channels", arr)
            File(context.filesDir, FILE_NAME).writeText(root.toString())
            CrashLogger.info("ChannelCache", "Сохранено ${channels.size} каналов")
        } catch (e: Exception) {
            CrashLogger.error("ChannelCache", "Ошибка сохранения", e)
        }
    }

    fun load(context: Context): List<Channel>? {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val ts = root.optLong("timestamp", 0L)
            if (System.currentTimeMillis() - ts > EXPIRE_TTL_MS) {
                CrashLogger.info("ChannelCache", "Кэш устарел (>24ч)")
                return null
            }
            val arr = root.optJSONArray("channels") ?: return null
            val result = mutableListOf<Channel>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                result.add(Channel(obj.optString("name"), obj.optString("url"), obj.optString("logo"), obj.optString("group", "VK Live")))
            }
            CrashLogger.info("ChannelCache", "Загружено ${result.size} каналов из кэша")
            return result
        } catch (e: Exception) {
            CrashLogger.error("ChannelCache", "Ошибка чтения", e)
            return null
        }
    }

    fun isFresh(context: Context): Boolean {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return false
            val root = JSONObject(file.readText())
            val ts = root.optLong("timestamp", 0L)
            return System.currentTimeMillis() - ts < FRESH_TTL_MS
        } catch (_: Exception) { return false }
    }

    fun clear(context: Context) {
        try { File(context.filesDir, FILE_NAME).delete() } catch (_: Exception) {}
    }
}
