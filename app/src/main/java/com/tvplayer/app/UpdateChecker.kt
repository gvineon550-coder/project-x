package com.tvplayer.app
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

object UpdateChecker {
    data class UpdateInfo(val downloadUrl: String, val versionCode: Int, val changelog: String)

    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val repo = BuildConfig.GITHUB_REPO
            if (repo.isBlank() || repo.contains("user/")) return@withContext null
            val client = OkHttpClient.Builder().dns(DohProvider.createWithFallback(context)).build()
            client.newCall(Request.Builder().url("https://api.github.com/repos/$repo/releases/latest").header("User-Agent", "TVPlayer").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val newVersion = json.optString("tag_name").removePrefix("v").toIntOrNull() ?: 0
                if (newVersion <= BuildConfig.VERSION_CODE) return@withContext null
                val assets = json.optJSONArray("assets") ?: return@withContext null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val url = asset.optString("browser_download_url")
                    if (url.endsWith(".apk", ignoreCase = true)) {
                        return@withContext UpdateInfo(url, newVersion, json.optString("body", ""))
                    }
                }
                null
            }
        } catch (_: Exception) { null }
    }

    suspend fun downloadApk(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder().dns(DohProvider.createWithFallback(context)).build()
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val file = File(context.cacheDir, "update.apk")
                resp.body?.byteStream()?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                file
            }
        } catch (_: Exception) { null }
    }
}
