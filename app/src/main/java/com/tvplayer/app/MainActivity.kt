package com.tvplayer.app
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.tvplayer.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), ImageLoaderFactory {
    private lateinit var b: ActivityMainBinding
    private var allChannels: List<Channel> = emptyList()
    private var showOnlyFavorites = false
    private val prefs by lazy { getSharedPreferences("tvplayer", MODE_PRIVATE) }
    private var backPressedTime: Long = 0
    private var needScrollOnResume = false

    override fun newImageLoader(): ImageLoader {
        val dohOkHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(DohProvider.createWithFallback(this))
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(dohOkHttp)
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(10L * 1024L * 1024L).build() }
            .build()
    }

    override fun onCreate(s: Bundle?) {
        installSplashScreen()
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        b.recycler.layoutManager = LinearLayoutManager(this)

        performStartupCleanup()

        b.btnRefresh.setOnClickListener { CrashLogger.info("UI", "Кнопка Обновить"); loadFromNetwork(forceRefresh = true) }
        b.btnFavFilter.setOnClickListener { showOnlyFavorites = !showOnlyFavorites; updateView(scrollToLast = false) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnCheckUpdates.setOnClickListener { manualCheckForUpdates() }

        b.btnFavFilter.nextFocusDownId = R.id.recycler
        b.btnRefresh.nextFocusDownId = R.id.recycler
        b.btnCheckUpdates.nextFocusDownId = R.id.recycler
        b.btnSettings.nextFocusDownId = R.id.recycler
        b.error.setOnClickListener { showLogDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (System.currentTimeMillis() - backPressedTime < 3000) {
                    CrashLogger.info("Lifecycle", "Полный выход (двойной BACK)")
                    exitApp()
                } else {
                    Toast.makeText(this@MainActivity, "Нажмите «Назад» ещё раз для выхода", Toast.LENGTH_SHORT).show()
                    backPressedTime = System.currentTimeMillis()
                }
            }
        })

        loadInitial()
    }

    override fun onResume() {
        super.onResume()
        if (needScrollOnResume && allChannels.isNotEmpty()) {
            needScrollOnResume = false
            b.recycler.postDelayed({ scrollToLastChannel() }, 200)
        }
    }

    private fun scrollToLastChannel() {
        val lastIndex = prefs.getInt("last_channel", 0)
        if (lastIndex in allChannels.indices) {
            (b.recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(lastIndex, 0)
            b.recycler.postDelayed({
                b.recycler.findViewHolderForAdapterPosition(lastIndex)?.itemView?.requestFocus()
            }, 100)
        }
    }

    private fun performStartupCleanup() {
        try {
            val updateApk = File(cacheDir, "update.apk")
            if (updateApk.exists()) {
                val sizeMb = updateApk.length() / 1024 / 1024
                updateApk.delete()
                CrashLogger.info("Cleanup", "Удалён update.apk ($sizeMb МБ)")
            }

            StreamCache.cleanup(this)

            val staleKeys = listOf("last_ota_check", "last_ota_version")
            val editor = prefs.edit()
            var removedKeys = 0
            staleKeys.forEach { k -> if (prefs.contains(k)) { editor.remove(k); removedKeys++ } }
            if (removedKeys > 0) {
                editor.apply()
                CrashLogger.info("Cleanup", "Удалено $removedKeys устаревших ключей prefs")
            }

            val sevenDays = 7L * 24 * 60 * 60 * 1000
            val now = System.currentTimeMillis()
            var oldRemoved = 0
            var oldBytes = 0L
            cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && (now - f.lastModified()) > sevenDays) {
                    oldBytes += f.length()
                    if (f.delete()) oldRemoved++
                } else if (f.isDirectory) {
                    f.listFiles()?.forEach { inner ->
                        if (inner.isFile && (now - inner.lastModified()) > sevenDays) {
                            oldBytes += inner.length()
                            if (inner.delete()) oldRemoved++
                        }
                    }
                }
            }
            if (oldRemoved > 0) {
                CrashLogger.info("Cleanup", "Удалено $oldRemoved старых файлов (${oldBytes/1024}КБ)")
            }

            var cacheSize = 0L
            cacheDir.walkTopDown().forEach { if (it.isFile) cacheSize += it.length() }
            if (cacheSize > 15 * 1024 * 1024) {
                val mb = cacheSize / 1024 / 1024
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                CrashLogger.info("Cleanup", "cacheDir очищен ($mb МБ)")
                cacheSize = 0L
            }

            var filesSize = 0L
            filesDir.walkTopDown().forEach { if (it.isFile) filesSize += it.length() }
            val filesMb = filesSize / 1024 / 1024
            if (filesSize > 50 * 1024 * 1024) {
                CrashLogger.error("Cleanup", "⚠️ filesDir=${filesMb}МБ > 50МБ! Аварийная очистка")
                ChannelCache.clear(this)
                CrashLogger.clear(this)
                filesSize = 0L
                filesDir.walkTopDown().forEach { if (it.isFile) filesSize += it.length() }
            } else if (filesSize > 30 * 1024 * 1024) {
                CrashLogger.info("Cleanup", "⚠️ filesDir=${filesMb}МБ > 30МБ (аномалия)")
            }

            val stat = StatFs(filesDir.path)
            val freeMb = stat.availableBytes / 1024 / 1024
            if (freeMb < 100) {
                CrashLogger.info("Cleanup", "⚠️ Свободно ${freeMb}МБ (<100МБ)")
            }

            CrashLogger.info("Cleanup", "Размеры: files=${filesSize/1024}КБ, cache=${cacheSize/1024}КБ, free=${freeMb}МБ")
        } catch (e: Exception) {
            CrashLogger.error("Cleanup", "Ошибка автоочистки", e)
        }
    }

    private fun loadInitial() {
        val cached = ChannelCache.load(this)
        if (cached != null && cached.isNotEmpty()) {
            allChannels = cached
            updateView(scrollToLast = true)
            if (ChannelCache.isFresh(this)) {
                CrashLogger.info("MainActivity", "Кэш свежий (<6ч), VK не дёргаем")
                return
            }
            CrashLogger.info("MainActivity", "Кэш устарел — обновление в фоне")
            loadFromNetwork(forceRefresh = false)
        } else {
            CrashLogger.info("MainActivity", "Кэш пуст — полная загрузка")
            b.progress.visibility = View.VISIBLE
            loadFromNetwork(forceRefresh = false)
        }
    }

    private fun loadFromNetwork(forceRefresh: Boolean) {
        if (!NetworkUtils.isOnline(this)) {
            CrashLogger.info("MainActivity", "Нет интернета — отмена")
            if (allChannels.isEmpty()) {
                b.progress.visibility = View.GONE
                b.recycler.visibility = View.GONE
                b.error.text = "Нет подключения к интернету.\n\nНажмите для подробностей."
                b.error.visibility = View.VISIBLE
            }
            return
        }
        lifecycleScope.launch {
            try {
                val (manual, cat) = readConfig()
                val manualWithLogos = manual.map { ch ->
                    if (ch.logo.isBlank()) {
                        val thumb = StreamResolver.getThumbnail(ch.url)
                        if (thumb.isNotBlank()) ch.copy(logo = thumb) else ch
                    } else ch
                }
                val fresh = loadChannels(this@MainActivity, cat, manualWithLogos)
                b.progress.visibility = View.GONE
                if (fresh.isNotEmpty()) {
                    val changed = fresh.size != allChannels.size || fresh.zip(allChannels).any { it.first.url != it.second.url }
                    allChannels = fresh
                    ChannelCache.save(this@MainActivity, fresh)

                    val currentFavs = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
                    val actualUrls = fresh.map { it.url }.toSet()
                    val before = currentFavs.size
                    currentFavs.retainAll(actualUrls)
                    if (currentFavs.size < before) {
                        prefs.edit().putStringSet("favorites", currentFavs).apply()
                        CrashLogger.info("Favorites", "Удалено ${before - currentFavs.size} мёртвых записей")
                    }

                    if (changed || forceRefresh || b.recycler.adapter == null) {
                        updateView(scrollToLast = forceRefresh)
                    }
                } else {
                    b.error.text = "Не удалось загрузить каналы.\n\nНажмите для подробностей."
                    b.error.visibility = View.VISIBLE
                    CrashLogger.error("MainActivity", "Пустой список каналов")
                }
            } catch (e: Exception) {
                b.progress.visibility = View.GONE
                b.error.text = "Ошибка загрузки:\n${e.javaClass.simpleName}: ${e.message}"
                b.error.visibility = View.VISIBLE
                CrashLogger.error("MainActivity", "Исключение при загрузке", e)
            }
        }
    }

    private fun manualCheckForUpdates() {
        CrashLogger.info("OTA", "Ручная проверка (кнопка ⬇️)")
        if (!NetworkUtils.isOnline(this)) {
            Toast.makeText(this, "Нет подключения к интернету", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Проверка обновлений...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val info = UpdateChecker.checkForUpdate(this@MainActivity)
            if (info == null) {
                Toast.makeText(this@MainActivity, "✅ Последняя версия (v${BuildConfig.VERSION_CODE})", Toast.LENGTH_LONG).show()
            } else {
                UpdateDialog.show(this@MainActivity, info.versionCode, info.changelog) { startUpdateDownload(info.downloadUrl) }
            }
        }
    }

    private fun showLogDialog() {
        val log = CrashLogger.readAll(this)
        AlertDialog.Builder(this)
            .setTitle("Лог приложения")
            .setMessage(log.takeLast(3000))
            .setPositiveButton("Отправить") { _, _ -> CrashLogger.share(this) }
            .setNegativeButton("Скопировать") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("log", log))
                Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Закрыть", null)
            .show()
    }

    private fun exitApp() {
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun startUpdateDownload(url: String) {
        Toast.makeText(this, "Загрузка обновления...", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val file = UpdateChecker.downloadApk(this@MainActivity, url)
            if (file == null) {
                Toast.makeText(this@MainActivity, "Не удалось скачать", Toast.LENGTH_LONG).show()
                return@launch
            }
            installApk(file)
        }
    }

    private fun installApk(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Разрешите установку", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
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
            CrashLogger.info("OTA", "Установка запущена (update.apk удалится при старте новой версии)")
        } catch (e: Exception) {
            CrashLogger.error("OTA", "Ошибка установки", e)
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateView(scrollToLast: Boolean = false) {
        if (allChannels.isEmpty()) return
        b.progress.visibility = View.GONE
        b.error.visibility = View.GONE
        val favs = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        allChannels.forEach { it.isFavorite = favs.contains(it.url) }
        val sorted = allChannels.sortedByDescending { it.isFavorite }
        val displayed = if (showOnlyFavorites) sorted.filter { it.isFavorite } else sorted
        b.txtTitle.text = if (showOnlyFavorites) "Избранное (${displayed.size})" else "Все каналы (${displayed.size})"
        b.btnFavFilter.setTextColor(if (showOnlyFavorites) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#FFEB3B"))
        b.recycler.visibility = View.VISIBLE
        b.recycler.adapter = ChannelAdapter(displayed, { ch ->
            needScrollOnResume = true
            CrashLogger.info("UI", "Открытие: ${ch.name}")
            val intent = Intent(this@MainActivity, PlayerActivity::class.java)
            intent.putExtra("index", allChannels.indexOfFirst { it.url == ch.url })
            intent.putStringArrayListExtra("names", ArrayList(allChannels.map { it.name }))
            intent.putStringArrayListExtra("urls", ArrayList(allChannels.map { it.url }))
            startActivity(intent)
        }, { ch ->
            val currentFavs = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (currentFavs.contains(ch.url)) currentFavs.remove(ch.url) else currentFavs.add(ch.url)
            prefs.edit().putStringSet("favorites", currentFavs).apply()
            updateView(scrollToLast = false)
        })

        b.recycler.post {
            if (scrollToLast) scrollToLastChannel()
            else b.recycler.getChildAt(0)?.requestFocus()
        }
    }

    private fun readConfig(): Pair<List<Channel>, String> {
        val json = assets.open("vk_config.json").bufferedReader().use { it.readText() }
        val o = JSONObject(json); val manual = mutableListOf<Channel>()
        o.optJSONArray("channels")?.let { arr -> for (i in 0 until arr.length()) { val c = arr.getJSONObject(i); manual.add(Channel(c.optString("name"), c.optString("url"), c.optString("logo"), c.optString("group", "VK Video"))) } }
        val cats = o.optJSONArray("categories")
        return manual to (if (cats != null && cats.length() > 0) cats.getString(0) else "")
    }
}
