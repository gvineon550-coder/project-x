package com.tvplayer.app
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.tvplayer.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.json.JSONObject
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        b.recycler.layoutManager = GridLayoutManager(this, 3)
        b.btnRefresh.setOnClickListener { load() }
        b.btnExit.setOnClickListener { exitApp() }
        load()
    }
    private fun exitApp() {
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
    private fun load() {
        b.progress.visibility = View.VISIBLE
        b.error.visibility = View.GONE
        b.recycler.visibility = View.GONE
        lifecycleScope.launch {
            val (manual, cat) = readConfig()
            val list = loadChannels(cat, manual)
            b.progress.visibility = View.GONE
            if (list.isEmpty()) {
                b.error.text = getString(R.string.error_load)
                b.error.visibility = View.VISIBLE; return@launch
            }
            b.recycler.visibility = View.VISIBLE
            b.recycler.adapter = ChannelAdapter(list) { ch ->
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java)
                    .putExtra("url", ch.url).putExtra("name", ch.name))
            }
        }
    }
    private fun readConfig(): Pair<List<Channel>, String> {
        val json = assets.open("vk_config.json").bufferedReader().use { it.readText() }
        val o = JSONObject(json); val manual = mutableListOf<Channel>()
        o.optJSONArray("channels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                manual.add(Channel(c.optString("name"), c.optString("url"),
                    c.optString("logo"), c.optString("group", "VK Video")))
            }
        }
        val cats = o.optJSONArray("categories")
        return manual to (if (cats != null && cats.length() > 0) cats.getString(0) else "")
    }
}
