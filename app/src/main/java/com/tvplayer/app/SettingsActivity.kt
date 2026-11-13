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
            Лёгкое и быстрое приложение для просмотра телеканалов VK Live на телефоне и ТВ-приставке.
            
            🎯 ВОЗМОЖНОСТИ:
            • Просмотр каналов VK Live в высоком качестве
            • Автоматическая подгрузка списка каналов
            • Выбор качества видео и формата экрана
            • Избранное (долгое нажатие ОК)
            • Переключение каналов пультом вверх/вниз
            • Панель каналов прямо в плеере (нажатие ОК)
            • Таймер сна (от 5 минут до 5 часов)
            • Авто-обновление приложения
            • Умное кэширование ссылок и очистка кэша
            • Подробный лог всех операций
            
            ⚙️ ТЕХНОЛОГИИ:
            • Резолвер: yt-dlp (Python + Chaquopy)
            • Плеер: AndroidX Media3 (ExoPlayer)
            • Сборка: GitHub Actions (CI/CD)
            
            🔄 Обновления выходят автоматически. При запуске приложение само проверяет наличие новой версии и предлагает обновиться.
            
            ❤️ Сделано с любовью для семьи и друзей.
        """.trimIndent()

        updateCacheInfo()

        b.btnBack.setOnClickListener { finish() }
        checkUpdate(silent = true)
        b.btnCheckUpdate.setOnClickListener { checkUpdate(silent = false) }

        b.btnClearCache.setOnClickListener {
            val imageLoader = Coil.imageLoader(this)
            imageLoader.diskCache?.clear()
            imageLoader.memoryCache?.clear()
            StreamCache.clearAll(this)
            ChannelCache.clear(this)
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            Toast.makeText(this, "Кэш очищен", Toast.LENGTH_SHORT).show()
            updateCacheInfo()
            CrashLogger.info("Settings", "Кэш очищен вручную")
        }

        b.btnOpenLog.setOnClickListener {
            val log = CrashLogger.readAll(this)
            AlertDialog.Builder(this)
                .setTitle("Лог приложения")
                .setMessage(log.takeLast(3000))
                .setPositiveButton("Скопировать") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("log", log))
                    Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }

        b.btnShareLog.setOnClickListener {
            CrashLogger.share(this)
        }

        b.btnExitApp.setOnClickListener {
            CrashLogger.info("Settings", "Полный выход из настроек")
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun checkUpdate(silent: Boolean) {
        b.txtStatus.text = "⏳ Проверка обновлений..."
        b.txtStatus.setTextColor(Color.parseColor("#FF8A8D93"))
        lifecycleScope.launch {
            val info = UpdateChecker.checkForUpdate(this@SettingsActivity)
            if (info == null) {
                b.txtStatus.text = "✅ У вас актуальная версия"
                b.txtStatus.setTextColor(Color.parseColor("#FF4ADE80"))
                if (!silent) Toast.makeText(this@SettingsActivity, "Обновлений нет", Toast.LENGTH_SHORT).show()
            } else {
                b.txtStatus.text = "🎉 Доступно обновление v${info.versionCode}!"
                b.txtStatus.setTextColor(Color.parseColor("#FFFACC15"))
                UpdateDialog.show(
                    this@SettingsActivity,
                    info.versionCode,
                    info.changelog,
                ) { startUpdateDownload(info.downloadUrl) }
            }
        }
    }

    private fun updateCacheInfo() {
        var totalSize = 0L
        cacheDir.walkTopDown().forEach { if (it.isFile) totalSize += it.length() }
        val sizeMb = totalSize / 1024f / 1024f
        b.btnClearCache.text = String.format("🗑 Очистить кэш (%.1f МБ)", sizeMb)
    }

    private fun startUpdateDownload(url: String) {
        Toast.makeText(this, "Загрузка обновления...", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val file = UpdateChecker.downloadApk(this@SettingsActivity, url)
            if (file == null) {
                Toast.makeText(this@SettingsActivity, "Не удалось скачать", Toast.LENGTH_LONG).show()
                return@launch
            }
            installApk(file)
        }
    }

    private fun installApk(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Разрешите установку из этого приложения", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (_: Exception) {}
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            CrashLogger.error("OTA", "Ошибка установки", e)
            Toast.makeText(this, "Ошибка установки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
