package com.tvplayer.app
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {
    data class UpdateInfo(val downloadUrl: String, val versionCode: Int, val changelog: String)

    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val repo = BuildConfig.GITHUB_REPO
            if (repo.isBlank() || repo.contains("user/")) return@withContext null
            CrashLogger.info("OTA", "→ Проверка обновлений (repo=$repo, текущая v${BuildConfig.VERSION_CODE})")
            val url = "https://api.github.com/repos/$repo/releases/latest"
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TVPlayer").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    CrashLogger.error("OTA", "GitHub API вернул HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v")
                val newVersion = tag.toIntOrNull() ?: 0
                if (newVersion <= BuildConfig.VERSION_CODE) {
                    CrashLogger.info("OTA", "✓ Установлена последняя версия (v${BuildConfig.VERSION_CODE})")
                    return@withContext null
                }
                val assets = json.optJSONArray("assets") ?: return@withContext null
                if (assets.length() == 0) return@withContext null
                val apkUrl = assets.getJSONObject(0).optString("browser_download_url")
                if (apkUrl.isBlank()) return@withContext null
                CrashLogger.info("OTA", "🎉 Доступно обновление v$newVersion")
                UpdateInfo(apkUrl, newVersion, json.optString("body", "Новая версия приложения"))
            }
        } catch (e: Exception) {
            CrashLogger.error("OTA", "Ошибка проверки обновлений", e)
            null
        }
    }

    suspend fun downloadApk(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        try {
            CrashLogger.info("OTA", "→ Скачивание APK: $url")
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    CrashLogger.error("OTA", "Скачивание провалено: HTTP ${resp.code}")
                    return@withContext null
                }
                val file = File(context.cacheDir, "update.apk")
                resp.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                CrashLogger.info("OTA", "✓ APK скачан: ${file.length() / 1024 / 1024} МБ")
                file
            }
        } catch (e: Exception) {
            CrashLogger.error("OTA", "Ошибка скачивания APK", e)
            null
        }
    }
}
