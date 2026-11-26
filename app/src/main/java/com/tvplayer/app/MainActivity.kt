package com.tvplayer.app
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.tvplayer.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity(), ImageLoaderFactory {
    private lateinit var b: ActivityMainBinding
    private var allChannels: List<Channel> = emptyList()
    private var showOnlyFavorites = false
    private val prefs by lazy { getSharedPreferences("tvplayer", MODE_PRIVATE) }
    private var backPressedTime: Long = 0
    private var needScrollOnResume = false
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            val cal = Calendar.getInstance()
            b.txtClock.text = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun newImageLoader(): ImageLoader {
        val dohOkHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(DohProvider.createWithFallback(this))
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(dohOkHttp)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(3L * 1024L * 1024L) // Уменьшено до 3 МБ
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(4 * 1024 * 1024) // Уменьшено до 4 МБ
                    .build()
            }
            .build()
    }

    override fun onCreate(s: Bundle?) {
        installSplashScreen()
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        b.recycler.layoutManager = LinearLayoutManager(this)

        // Очистка только 1 раз при старте главного экрана
        CacheManager.maybeCleanup(this)

        b.btnRefresh.setOnClickListener {
            CrashLogger.info("UI", "Кнопка Обновить")
            b.progress.visibility = View.VISIBLE
            b.errorLayout.visibility = View.GONE
            loadFromNetwork(forceRefresh = true)
        }
        b.btnFavFilter.setOnClickListener { showOnlyFavorites = !showOnlyFavorites; updateView(scrollToLast = false) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnCheckUpdates.setOnClickListener { manualCheckForUpdates() }
        b.btnRetryMain.setOnClickListener {
            b.errorLayout.visibility = View.GONE
            b.progress.visibility = View.VISIBLE
            loadFromNetwork(forceRefresh = true)
        }

        b.btnFavFilter.nextFocusDownId = R.id.recycler
        b.btnRefresh.nextFocusDownId = R.id.recycler
        b.btnCheckUpdates.nextFocusDownId = R.id.recycler
        b.btnSettings.nextFocusDownId = R.id.recycler

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
        clockHandler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (needScrollOnResume && allChannels.isNotEmpty()) {
            needScrollOnResume = false
            b.recycler.postDelayed({ scrollToLastChannel() }, 200)
        }
    }

    override fun onDestroy() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onDestroy()
    }

    private fun humanError(code: String, detail: String): String {
        return when {
            code.contains("NO_NETWORK") -> "📡 Нет подключения к интернету"
            code.contains("TIMEOUT") -> "⏱ Сервер VK не отвечает"
            code.contains("HTTP_403") -> "🚫 Канал недоступен (VK: 403)"
            code.contains("HTTP_404") -> "🚫 Канал не найден (VK: 404)"
            code.contains("NO_M3U8") -> "🔍 Не удалось найти поток"
            code.contains("PARSE_ERROR") -> "📄 VK вернул некорректный ответ"
            code.contains("VK_OFFLINE") -> "📺 Канал сейчас не транслируется"
            else -> detail
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
                CrashLogger.info("MainActivity", "Кэш свежий (<24ч), VK не дёргаем")
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
            b.progress.visibility = View.GONE
            if (allChannels.isEmpty()) {
                b.recycler.visibility = View.GONE
                b.error.text = humanError("NO_NETWORK", "Нет подключения к интернету.")
                b.errorLayout.visibility = View.VISIBLE
            }
            return
        }
        lifecycleScope.launch {
            try {
                val (manual, cat) = readConfig()
                val manualWithLogos = manual.map { ch ->
                    if (ch.logo.isBlank()) {
                        val cached = ThumbCache.get(this@MainActivity, ch.url)
                        if (cached != null) ch.copy(logo = cached)
                        else {
                            val thumb = StreamResolver.getThumbnail(ch.url)
                            if (thumb.isNotBlank()) {
                                ThumbCache.put(this@MainActivity, ch.url, thumb)
                                ch.copy(logo = thumb)
                            } else ch
                        }
                    } else ch
                }
                val fresh = if (cat.isBlank() && manualWithLogos.isNotEmpty()) {
                    CrashLogger.info("MainActivity", "categoryId пустой — только ручные каналы")
                    manualWithLogos
                } else {
                    loadChannels(this@MainActivity, cat, manualWithLogos)
                }
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
                    b.error.text = "Не удалось загрузить каналы."
                    b.errorLayout.visibility = View.VISIBLE
                    CrashLogger.error("MainActivity", "Пустой список каналов")
                }
            } catch (e: Exception) {
                b.progress.visibility = View.GONE
                b.error.text = "Ошибка загрузки:\n${e.javaClass.simpleName}: ${e.message}"
                b.errorLayout.visibility = View.VISIBLE
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
        b.errorLayout.visibility = View.GONE
        val favs = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        allChannels.forEach { it.isFavorite = favs.contains(it.url) }
        val sorted = allChannels.sortedByDescending { it.isFavorite }
        val displayed = if (showOnlyFavorites) sorted.filter { it.isFavorite } else sorted
        b.txtTitle.text = if (showOnlyFavorites) "Избранное (${displayed.size})" else "Все каналы (${displayed.size})"
        b.btnFavFilter.text = if (showOnlyFavorites) "★ Избранное" else "☆ Избранное"
        b.btnFavFilter.setTextColor(if (showOnlyFavorites) Color.parseColor("#FFEB3B") else Color.parseColor("#FF8A8D93"))
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
        return try {
            val json = assets.open("vk_config.json").bufferedReader().use { it.readText() }
            val o = JSONObject(json); val manual = mutableListOf<Channel>()
            o.optJSONArray("channels")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    manual.add(Channel(c.optString("name"), c.optString("url"), c.optString("logo"), c.optString("group", "VK Video")))
                }
            }
            val cats = o.optJSONArray("categories")
            manual to (if (cats != null && cats.length() > 0) cats.getString(0) else "")
        } catch (e: Exception) {
            CrashLogger.error("MainActivity", "Ошибка чтения vk_config.json", e)
            emptyList<Channel>() to ""
        }
    }
}
