package com.tvplayer.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Channel(val name: String, val url: String, val logo: String, val group: String = "VK Live", var isFavorite: Boolean = false)

private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

suspend fun loadChannels(categoryId: String, manual: List<Channel>): List<Channel> =
    withContext(Dispatchers.IO) {
        val result = mutableListOf<Channel>()
        val startTime = System.currentTimeMillis()
        CrashLogger.info("VkApi", "→ Загрузка каналов, category=$categoryId")
        try {
            val url = "https://api.live.vkvideo.ru/v1/catalog/public_video_streams/category/$categoryId/stream/?limit=100"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA).header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://live.vkvideo.ru").header("Referer", "https://live.vkvideo.ru/")
                .header("X-App", "streams_web").header("X-From-Id", UUID.randomUUID().toString()).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                CrashLogger.info("VkApi", "← HTTP ${resp.code}, размер ответа: ${body.length} байт")
                if (resp.isSuccessful && body.isNotBlank()) {
                    val streams = JSONObject(body).optJSONObject("data")?.optJSONArray("streamBlogs")
                    if (streams != null) for (i in 0 until streams.length()) {
                        val item = streams.optJSONObject(i) ?: continue
                        if (!item.optBoolean("is_online", true)) continue
                        val blog = item.optJSONObject("blog") ?: continue
                        val blogUrl = blog.optString("blogUrl"); if (blogUrl.isBlank()) continue
                        val raw = blog.optString("title").ifBlank { blog.optJSONObject("owner")?.optString("displayName").orEmpty() }.ifBlank { blogUrl }
                        val name = if (raw.length > 60) raw.take(60) + "…" else raw
                        val logo = blog.optJSONObject("owner")?.optString("avatarUrl")?.ifBlank { null } ?: blog.optString("coverUrl")
                        result.add(Channel(name, "https://live.vkvideo.ru/$blogUrl", logo))
                    }
                } else {
                    CrashLogger.error("VkApi", "Неуспешный ответ HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            CrashLogger.error("VkApi", "Ошибка загрузки каналов", e)
        }
        val elapsed = System.currentTimeMillis() - startTime
        CrashLogger.info("VkApi", "✓ Загружено ${result.size} каналов за ${elapsed}мс (+ ${manual.size} ручных)")
        result + manual
    }
