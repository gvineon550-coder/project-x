package com.tvplayer.app
import android.content.Intent
import android.os.Bundle
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

class MainActivity : AppCompatActivity(), ImageLoaderFactory {
    private lateinit var b: ActivityMainBinding
    private var allChannels: List<Channel> = emptyList()
    private var showOnlyFavorites = false
    private val prefs by lazy { getSharedPreferences("tvplayer", MODE_PRIVATE) }
    private var backPressedTime: Long = 0
    private var lastRefreshTime: Long = 0

    override fun newImageLoader() = ImageLoader.Builder(this)
        .okHttpClient(OkHttpClient.Builder().dns(DohProvider.createWithFallback(this)).build())
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(10L * 1024L * 1024L).build() }
        .build()

    override fun onCreate(s: Bundle?) {
        installSplashScreen()
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        b.recycler.layoutManager = LinearLayoutManager(this)

        b.btnRefresh.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastRefreshTime > 15000) {
                lastRefreshTime = now
                loadFromNetwork(true)
            } else {
                Toast.makeText(this, "Подождите перед повторным обновлением", Toast.LENGTH_SHORT).show()
            }
        }
        b.btnFavFilter.setOnClickListener { showOnlyFavorites = !showOnlyFavorites; updateView(false) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnCheckUpdates.setOnClickListener { manualCheckForUpdates() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (System.currentTimeMillis() - backPressedTime < 3000) { finishAffinity(); android.os.Process.killProcess(android.os.Process.myPid()) }
                else { Toast.makeText(this@MainActivity, "Ещё раз для выхода", Toast.LENGTH_SHORT).show(); backPressedTime = System.currentTimeMillis() }
            }
        })
        loadInitial()
    }

    private fun loadInitial() {
        val cached = ChannelCache.load(this)
        if (!cached.isNullOrEmpty()) { allChannels = cached; updateView(true); if (!ChannelCache.isFresh(this)) loadFromNetwork(false) }
        else { b.progress.visibility = View.VISIBLE; loadFromNetwork(false) }
    }

    private fun loadFromNetwork(force: Boolean) {
        if (!NetworkUtils.isOnline(this)) { if (allChannels.isEmpty()) { b.progress.visibility = View.GONE; b.error.text = "Нет интернета"; b.error.visibility = View.VISIBLE }; return }
        lifecycleScope.launch {
            try {
                val (manual, cat) = readConfig()
                val fresh = loadChannels(this@MainActivity, cat, manual)
                b.progress.visibility = View.GONE
                if (fresh.isNotEmpty()) { allChannels = fresh; ChannelCache.save(this@MainActivity, fresh); updateView(force) }
            } catch (_: Exception) { b.progress.visibility = View.GONE }
        }
    }

    private fun manualCheckForUpdates() {
        lifecycleScope.launch {
            val info = UpdateChecker.checkForUpdate(this@MainActivity)
            if (info == null) Toast.makeText(this@MainActivity, "✅ Актуальная версия", Toast.LENGTH_SHORT).show()
            else UpdateDialog.show(this@MainActivity, info.versionCode, info.changelog) { startDownload(info.downloadUrl) }
        }
    }

    private fun startDownload(url: String) {
        lifecycleScope.launch {
            val file = UpdateChecker.downloadApk(this@MainActivity, url) ?: return@launch
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun updateView(scrollToLast: Boolean) {
        if (allChannels.isEmpty()) return
        b.progress.visibility = View.GONE; b.error.visibility = View.GONE
        val favs = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        allChannels.forEach { it.isFavorite = favs.contains(it.url) }
        val displayed = (if (showOnlyFavorites) allChannels.filter { it.isFavorite } else allChannels).sortedByDescending { it.isFavorite }
        b.txtTitle.text = if (showOnlyFavorites) "Избранное (${displayed.size})" else "Все каналы (${displayed.size})"
        b.recycler.adapter = ChannelAdapter(displayed, { ch ->
            val intent = Intent(this, PlayerActivity::class.java).putExtra("index", allChannels.indexOfFirst { it.url == ch.url })
                .putStringArrayListExtra("names", ArrayList(allChannels.map { it.name })).putStringArrayListExtra("urls", ArrayList(allChannels.map { it.url }))
            startActivity(intent)
        }, { ch ->
            val cur = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (cur.contains(ch.url)) cur.remove(ch.url) else cur.add(ch.url)
            prefs.edit().putStringSet("favorites", cur).apply(); updateView(false)
        })
    }

    private fun readConfig(): Pair<List<Channel>, String> {
        val json = assets.open("vk_config.json").bufferedReader().use { it.readText() }
        val o = JSONObject(json); val manual = mutableListOf<Channel>()
        o.optJSONArray("channels")?.let { arr -> for (i in 0 until arr.length()) { val c = arr.getJSONObject(i); manual.add(Channel(c.optString("name"), c.optString("url"), c.optString("logo"), c.optString("group", "VK Video"))) } }
        val cats = o.optJSONArray("categories")
        return manual to (if (cats != null && cats.length() > 0) cats.getString(0) else "")
    }
}
