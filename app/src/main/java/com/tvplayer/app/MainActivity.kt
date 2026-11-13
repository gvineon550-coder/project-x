package com.tvplayer.app
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity(), ImageLoaderFactory {
    private lateinit var b: ActivityMainBinding
    private var allChannels: List<Channel> = emptyList()
    private var showOnlyFavorites = false
    private val prefs by lazy { getSharedPreferences("tvplayer", MODE_PRIVATE) }
    private var backPressedTime: Long = 0
    private var needScrollOnResume = false

    companion object {
        // ⚡ Живёт в памяти JVM-процесса, не сбрасывается при пересоздании Activity.
        // Гарантирует: 1 проверка OTA на запуск приложения.
        @Volatile private var otaCheckedThisSession = false
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(10L * 1024L * 1024L).build() }
            .build()
    }

    override fun onCreate(s: Bundle?) {
        installSplashScreen()
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        b.recycler.layoutManager = LinearLayoutManager(this)
        autoCleanCache()
        b.btnRefresh.setOnClickListener { CrashLogger.info("UI", "Нажата кнопка Обновить"); loadFromNetwork(forceRefresh = true) }
        b.btnFavFilter.setOnClickListener { showOnlyFavorites = !showOnlyFavorites; updateView(scrollToLast = false) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnFavFilter.nextFocusDownId = R.id.recycler
        b.btnRefresh.nextFocusDownId = R.id.recycler
        b.btnSettings.nextFocusDownId = R.id.recycler
        b.error.setOnClickListener { showLogDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (System.currentTimeMillis() - backPressedTime < 3000) {
                    CrashLogger.info("Lifecycle", "Полный выход из приложения (двойной BACK)")
                    exitApp()
                } else {
                    Toast.makeText(this@MainActivity, "Нажмите «Назад» ещё раз для выхода", Toast.LENGTH_SHORT).show()
                    backPressedTime = System.currentTimeMillis()
                }
            }
        })

        loadInitial()
        checkForUpdates()
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
            CrashLogger.info("MainActivity", "Нет интернета — загрузка отменена")
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
                val fresh = loadChannels(cat, manualWithLogos)
                b.progress.visibility = View.GONE
                if (fresh.isNotEmpty()) {
                    val changed = fresh.size != allChannels.size || fresh.zip(allChannels).any { it.first.url != it.second.url }
                    allChannels = fresh
                    ChannelCache.save(this@MainActivity, fresh)
                    if (changed || forceRefresh || b.recycler.adapter == null) {
                        updateView(scrollToLast = forceRefresh)
                    }
                } else {
                    val msg = "Не удалось загрузить каналы\n\nВозможно:\n• VK API вернул пустой список\n• Категория изменилась\n\nНажмите для подробностей."
                    b.error.text = msg
                    b.error.visibility = View.VISIBLE
                    CrashLogger.error("MainActivity", "Пустой список каналов после загрузки")
                }
            } catch (e: Exception) {
                b.progress.visibility = View.GONE
                val msg = "Ошибка загрузки:\n${e.javaClass.simpleName}: ${e.message}\n\nНажмите для подробностей."
                b.error.text = msg
                b.error.visibility = View.VISIBLE
                CrashLogger.error("MainActivity", "Исключение при загрузке", e)
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

    private fun autoCleanCache() {
        var totalSize = 0L
        cacheDir.walkTopDown().forEach { if (it.isFile) totalSize += it.length() }
        if (totalSize > 15 * 1024 * 1024) {
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            CrashLogger.info("MainActivity", "Автоочистка кэша картинок")
        }
    }

    private fun exitApp() {
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ═══ OTA-ЗАЩИТА (план А): @Volatile флаг в companion object ═══
    private fun checkForUpdates() {
        // Уровень 1: сессионный флаг — НЕ сбрасывается при пересоздании Activity
        if (otaCheckedThisSession) {
            CrashLogger.info("OTA", "Пропуск (уже проверяли в этой сессии)")
            return
        }

        val currentVersion = BuildConfig.VERSION_CODE
        val lastVersion = prefs.getInt("last_ota_version", 0)
        val lastCheck = prefs.getLong("last_ota_check", 0L)
        val sinceLast = System.currentTimeMillis() - lastCheck

        CrashLogger.info("OTA", "Debug: current=v$currentVersion, lastChecked=v$lastVersion, sinceLast=${sinceLast / 60000}мин")

        // Уровень 2: prefs — 6 часов между разными запусками приложения
        if (lastCheck != 0L && lastVersion == currentVersion && sinceLast < 6 * 60 * 60 * 1000L) {
            otaCheckedThisSession = true
            CrashLogger.info("OTA", "Пропуск (v$currentVersion, ${sinceLast / 60000}мин назад)")
            return
        }

        // Оба уровня пройдены — запускаем и ставим флаг СРАЗУ
        otaCheckedThisSession = true
        prefs.edit()
            .putInt("last_ota_version", currentVersion)
            .putLong("last_ota_check", System.currentTimeMillis())
            .commit()
        CrashLogger.info("OTA", "Запуск проверки (сессия+prefs)")

        lifecycleScope.launch {
            val info = UpdateChecker.checkForUpdate(this@MainActivity) ?: return@launch
            UpdateDialog.show(
                this@MainActivity,
                info.versionCode,
                info.changelog,
            ) { startUpdateDownload(info.downloadUrl) }
        }
    }

    private fun startUpdateDownload(url: String) {
        Toast.makeText(this, "Загрузка обновления...", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val file = UpdateChecker.downloadApk(this@MainActivity, url)
            if (file == null) {
                Toast.makeText(this@MainActivity, "Не удалось скачать обновление", Toast.LENGTH_LONG).show()
                return@launch
            }
            installApk(file)
        }
    }

    private fun installApk(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Разрешите установку из этого приложения", Toast.LENGTH_LONG).show()
            CrashLogger.info("OTA", "Нет разрешения на установку — открываем настройки")
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (e: Exception) {
                CrashLogger.error("OTA", "Не удалось открыть настройки", e)
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            CrashLogger.info("OTA", "Запущена установка APK")
        } catch (e: Exception) {
            CrashLogger.error("OTA", "Ошибка установки APK", e)
            Toast.makeText(this, "Ошибка установки: ${e.message}", Toast.LENGTH_LONG).show()
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
            CrashLogger.info("UI", "Открытие канала: ${ch.name}")
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
