package com.tvplayer.app
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import coil.Coil
import com.tvplayer.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivitySettingsBinding.inflate(layoutInflater); setContentView(b.root)
        b.txtVersion.text = "TV Player v${BuildConfig.VERSION_CODE}"
        b.txtAbout.text = """
            Лёгкое и быстрое приложение для просмотра телеканалов VK Live.
            
            🎯 ВОЗМОЖНОСТИ:
            • Просмотр VK Live в высоком качестве
            • Автоподгрузка списка каналов
            • Выбор качества и формата экрана
            • Избранное (долгое нажатие ОК)
            • Переключение пультом вверх/вниз
            • Панель каналов в плеере (ОК)
            • Таймер сна (5м – 5ч)
            • Ручное обновление (⬇️ в шапке)
            • Умное кэширование + автоочистка
            • DNS-over-HTTPS (AdGuard) с фолбэком
            • Подробный лог
            
            ⚙️ ТЕХНОЛОГИИ:
            • Резолвер: yt-dlp (Python + Chaquopy)
            • Плеер: Media3 (ExoPlayer)
            • Сборка: GitHub Actions
            
            ❤️ Сделано для семьи и друзей.
        """.trimIndent()
        updateCacheInfo()
        b.btnBack.setOnClickListener { finish() }
        b.btnCheckUpdate.setOnClickListener { checkUpdate() }
        b.btnClearCache.setOnClickListener {
            val imageLoader = Coil.imageLoader(this); imageLoader.diskCache?.clear(); imageLoader.memoryCache?.clear()
            StreamCache.clearAll(this); ChannelCache.clear(this); cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            Toast.makeText(this, "Кэш очищен", Toast.LENGTH_SHORT).show(); updateCacheInfo(); CrashLogger.info("Settings", "Кэш очищен вручную")
        }
        b.btnFullReset.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Полный сброс")
                .setMessage("Будут удалены:\n\n• Избранное\n• Последний канал\n• Все кэши\n• Лог\n\nПриложение не удалится. Продолжить?")
                .setPositiveButton("Сбросить") { _, _ -> performFullReset() }.setNegativeButton("Отмена", null).show()
        }
        b.btnOpenLog.setOnClickListener {
            val log = CrashLogger.readAll(this)
            AlertDialog.Builder(this).setTitle("Лог приложения").setMessage(log.takeLast(3000))
                .setPositiveButton("Скопировать") { _, _ -> val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clipboard.setPrimaryClip(ClipData.newPlainText("log", log)); Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Закрыть", null).show()
        }
        b.btnShareLog.setOnClickListener { CrashLogger.share(this) }
        b.btnExitApp.setOnClickListener { CrashLogger.info("Settings", "Полный выход"); finishAffinity(); android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    private fun performFullReset() {
        try {
            getSharedPreferences("tvplayer", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("stream_cache", MODE_PRIVATE).edit().clear().apply()
            ChannelCache.clear(this); CrashLogger.clear(this)
            val il = Coil.imageLoader(this); il.diskCache?.clear(); il.memoryCache?.clear()
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            Toast.makeText(this, "Полный сброс выполнен", Toast.LENGTH_SHORT).show(); CrashLogger.info("Settings", "Полный сброс: всё очищено"); updateCacheInfo()
        } catch (e: Exception) { CrashLogger.error("Settings", "Ошибка полного сброса", e); Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun checkUpdate() {
        b.txtStatus.text = "⏳ Проверка обновлений..."; b.txtStatus.setTextColor(Color.parseColor("#FF8A8D93"))
        lifecycleScope.launch {
            val info = UpdateChecker.checkForUpdate(this@SettingsActivity)
            if (info == null) { b.txtStatus.text = "✅ У вас актуальная версия"; b.txtStatus.setTextColor(Color.parseColor("#FF4ADE80")) }
            else { b.txtStatus.text = "🎉 Доступно обновление v${info.versionCode}!"; b.txtStatus.setTextColor(Color.parseColor("#FFFACC15")); UpdateDialog.show(this@SettingsActivity, info.versionCode, info.changelog) { startUpdateDownload(info.downloadUrl) } }
        }
    }

    private fun updateCacheInfo() {
        try {
            val imageCacheSize = File(cacheDir, "image_cache").walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val channelsCacheFile = File(filesDir, "channels_cache.json"); val channelsCacheSize = if (channelsCacheFile.exists()) channelsCacheFile.length() else 0L
            val logFile = File(filesDir, "app.log"); val logSize = if (logFile.exists()) logFile.length() else 0L
            val streamPrefs = getSharedPreferences("stream_cache", MODE_PRIVATE); val streamSize = streamPrefs.all.values.sumOf { ((it as? String)?.length ?: 0).toLong() }
            val totalCache = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val totalFiles = filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val pythonSize = totalFiles - channelsCacheSize - logSize
            val stats = buildString {
                appendLine("🖼 Кэш картинок: ${formatSize(imageCacheSize)}")
                appendLine("📋 Кэш списка: ${formatSize(channelsCacheSize)}")
                appendLine("🔗 Кэш ссылок: ${formatSize(streamSize)}")
                appendLine("📝 Лог: ${formatSize(logSize)}")
                appendLine("🐍 Python: ${formatSize(pythonSize)}")
                appendLine("📦 Cache всего: ${formatSize(totalCache)}")
                append("💾 Files всего: ${formatSize(totalFiles)}")
            }
            b.txtCacheStats.text = stats; b.btnClearCache.text = String.format("🗑 Очистить кэш (%s)", formatSize(totalCache))
        } catch (e: Exception) { b.txtCacheStats.text = "Не удалось посчитать: ${e.message}" }
    }

    private fun formatSize(bytes: Long): String = when { bytes < 1024 -> "$bytes Б"; bytes < 1024 * 1024 -> "${bytes / 1024} КБ"; else -> String.format("%.1f МБ", bytes / 1024f / 1024f) }

    private fun startUpdateDownload(url: String) {
        Toast.makeText(this, "Загрузка обновления...", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val file = UpdateChecker.downloadApk(this@SettingsActivity, url)
            if (file == null) { Toast.makeText(this@SettingsActivity, "Не удалось скачать", Toast.LENGTH_LONG).show(); return@launch }
            installApk(file)
        }
    }

    private fun installApk(file: File) {
        if (!packageManager.canRequestPackageInstalls()) { Toast.makeText(this, "Разрешите установку", Toast.LENGTH_LONG).show(); try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}; return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent); CrashLogger.info("OTA", "Установка запущена (update.apk удалится при старте новой версии)")
        } catch (e: Exception) { CrashLogger.error("OTA", "Ошибка установки", e); Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}
