package com.tvplayer.app
import android.app.AlertDialog
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
        b.btnRefresh.setOnClickListener { load() }
        b.btnFavFilter.setOnClickListener { showOnlyFavorites = !showOnlyFavorites; updateView() }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnFavFilter.nextFocusDownId = R.id.recycler
        b.btnRefresh.nextFocusDownId = R.id.recycler
        b.btnSettings.nextFocusDownId = R.id.recycler

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (System.currentTimeMillis() - backPressedTime < 2000) {
                    exitApp()
                } else {
                    Toast.makeText(this@MainActivity, "Нажмите «Назад» ещё раз для выхода", Toast.LENGTH_SHORT).show()
                    backPressedTime = System.currentTimeMillis()
                }
            }
        })

        load()
        checkForUpdates()
    }

    private fun autoCleanCache() {
        var totalSize = 0L
        cacheDir.walkTopDown().forEach { if (it.isFile) totalSize += it.length() }
        if (totalSize > 15 * 1024 * 1024) {
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private fun exitApp() {
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            val info = UpdateChecker.checkForUpdate(this@MainActivity) ?: return@launch
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Доступно обновление v${info.versionCode}")
                .setMessage(info.changelog.take(400))
                .setPositiveButton("Скачать и установить") { _, _ -> startUpdateDownload(info.downloadUrl) }
                .setNegativeButton("Позже", null)
                .show()
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

    private fun load() {
        if (!NetworkUtils.isOnline(this)) {
            b.progress.visibility = View.GONE; b.recycler.visibility = View.GONE
            b.error.text = "Нет подключения к интернету."
            b.error.visibility = View.VISIBLE; return
        }
        b.progress.visibility = View.VISIBLE; b.error.visibility = View.GONE; b.recycler.visibility = View.GONE
        lifecycleScope.launch {
            val (manual, cat) = readConfig()
            val manualWithLogos = manual.map { ch ->
                if (ch.logo.isBlank()) {
                    val thumb = StreamResolver.getThumbnail(ch.url)
                    if (thumb.isNotBlank()) ch.copy(logo = thumb) else ch
                } else ch
            }
            allChannels = loadChannels(cat, manualWithLogos)
            b.progress.visibility = View.GONE
            if (allChannels.isEmpty()) {
                b.error.text = "Не удалось загрузить список."
                b.error.visibility = View.VISIBLE; return@launch
            }
            updateView()
        }
    }

    private fun updateView() {
        val favs = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        allChannels.forEach { it.isFavorite = favs.contains(it.url) }
        val sorted = allChannels.sortedByDescending { it.isFavorite }
        val displayed = if (showOnlyFavorites) sorted.filter { it.isFavorite } else sorted
        b.txtTitle.text = if (showOnlyFavorites) "Избранное (${displayed.size})" else "Все каналы (${displayed.size})"
        b.btnFavFilter.setTextColor(if (showOnlyFavorites) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#FFEB3B"))
        b.recycler.visibility = View.VISIBLE
        b.recycler.adapter = ChannelAdapter(displayed, { ch ->
            val intent = Intent(this@MainActivity, PlayerActivity::class.java)
            intent.putExtra("index", allChannels.indexOfFirst { it.url == ch.url })
            intent.putStringArrayListExtra("names", ArrayList(allChannels.map { it.name }))
            intent.putStringArrayListExtra("urls", ArrayList(allChannels.map { it.url }))
            startActivity(intent)
        }, { ch ->
            val currentFavs = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (currentFavs.contains(ch.url)) currentFavs.remove(ch.url) else currentFavs.add(ch.url)
            prefs.edit().putStringSet("favorites", currentFavs).apply()
            updateView()
        })
        b.recycler.post { b.recycler.getChildAt(0)?.requestFocus() }
    }

    private fun readConfig(): Pair<List<Channel>, String> {
        val json = assets.open("vk_config.json").bufferedReader().use { it.readText() }
        val o = JSONObject(json); val manual = mutableListOf<Channel>()
        o.optJSONArray("channels")?.let { arr -> for (i in 0 until arr.length()) { val c = arr.getJSONObject(i); manual.add(Channel(c.optString("name"), c.optString("url"), c.optString("logo"), c.optString("group", "VK Video"))) } }
        val cats = o.optJSONArray("categories")
        return manual to (if (cats != null && cats.length() > 0) cats.getString(0) else "")
    }
}
