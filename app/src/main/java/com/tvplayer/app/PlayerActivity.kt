package com.tvplayer.app
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.tvplayer.app.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityPlayerBinding.inflate(layoutInflater); setContentView(b.root)
        val url = intent.getStringExtra("url").orEmpty()
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val direct = StreamResolver.resolve(url)
            if (direct == null) {
                b.progress.visibility = View.GONE
                b.error.text = getString(R.string.error_resolve)
                b.error.visibility = View.VISIBLE; return@launch
            }
            play(direct)
        }
    }
    private fun play(m3u8: String) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to "https://vk.com/", "Origin" to "https://vk.com"))
        player = ExoPlayer.Builder(this).build().apply {
            setMediaSource(HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(m3u8)))
            playWhenReady = true; prepare()
        }
        b.playerView.player = player; b.progress.visibility = View.GONE
    }
    override fun onDestroy() {
        super.onDestroy(); player?.release(); player = null
        finishAndRemoveTask()
    }
}
