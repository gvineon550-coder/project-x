package com.tvplayer.app
import android.app.AlertDialog
import android.content.Intent
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
        b.txtAbout.text = "Приложение для просмотра VK Live.\nРезолвер: yt-dlp\nПлеер: Media3 (ExoPlayer)\nРепозиторий: ${BuildConfig.GITHUB_REPO}"

        updateCacheInfo()

        b.btnBack.setOnClickListener { finish() }

        b.btnCheckUpdate.setOnClickListener {
            b.txtStatus.text = "Проверка обновлений..."
            lifecycleScope.launch {
                val info = UpdateChecker.checkForUpdate(this@SettingsActivity)
                if (info == null) {
                    b.txtStatus.text = "✅ У вас последняя версия (v${BuildConfig.VERSION_CODE})"
                } else {
                    b.txtStatus.text = "🎉 Доступна версия v${info.versionCode}"
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Доступно обновление v${info.versionCode}")
                        .setMessage(info.changelog.take(400))
                        .setPositiveButton("Скачать и установить") { _, _ -> startUpdateDownload(info.downloadUrl) }
                        .setNegativeButton("Позже", null)
                        .show()
                }
            }
        }

        b.btnClearCache.setOnClickListener {
            val imageLoader = Coil.imageLoader(this)
            imageLoader.diskCache?.clear()
            imageLoader.memoryCache?.clear()
            StreamCache.clearAll(this)
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            Toast.makeText(this, "Кэш очищен", Toast.LENGTH_SHORT).show()
            updateCacheInfo()
        }

        b.btnExitApp.setOnClickListener {
            finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
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
            Toast.makeText(this, "Ошибка установки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
