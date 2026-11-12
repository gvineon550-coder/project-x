package com.tvplayer.app
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvplayer.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.json.JSONObject
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var allChannels: List<Channel> = emptyList()
    private var showOnlyFavorites = false
    private val prefs by lazy { getSharedPreferences("tvplayer", MODE_PRIVATE) }
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.btnRefresh.setOnClickListener { load() }
        b.btnExit.setOnClickListener { exitApp() }
        b.btnFavFilter.setOnClickListener { showOnlyFavorites = !showOnlyFavorites; updateView() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exitApp() }
        })
        load()
    }
    private fun exitApp() { finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid()) }
    private fun load() {
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
            if (allChannels.isEmpty()) { b.error.text = getString(R.string.error_load); b.error.visibility = View.VISIBLE; return@launch }
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
        b.recycler.post { b.recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }
    private fun readConfig(): Pair<List<Channel>, String> {
        val json = assets.open("vk_config.json").bufferedReader().use { it.readText() }
        val o = JSONObject(json); val manual = mutableListOf<Channel>()
        o.optJSONArray("channels")?.let { arr -> for (i in 0 until arr.length()) { val c = arr.getJSONObject(i); manual.add(Channel(c.optString("name"), c.optString("url"), c.optString("logo"), c.optString("group", "VK Video"))) } }
        val cats = o.optJSONArray("categories")
        return manual to (if (cats != null && cats.length() > 0) cats.getString(0) else "")
    }
}
