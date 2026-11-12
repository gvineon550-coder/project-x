package com.tvplayer.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.tvplayer.app.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        
        // Включаем полноэкранный режим (скрываем системные панели)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val url = intent.getStringExtra("url").orEmpty()
        b.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val direct = StreamResolver.resolve(url)
            if (direct.startsWith("ERROR:")) {
                b.progress.visibility = View.GONE
                b.error.text = direct
                b.error.visibility = View.VISIBLE
                return@launch
            }
            play(direct)
        }
    }

    private fun play(m3u8: String) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to "https://vk.com/", "Origin" to "https://vk.com"))

        trackSelector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .build().apply {
                setMediaSource(HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(m3u8)))
                playWhenReady = true
                prepare()
                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        updateQualityButton(tracks)
                    }
                })
            }
        b.playerView.player = player
        b.progress.visibility = View.GONE
        b.btnQuality.setOnClickListener { showQualityDialog() }
    }

    private fun updateQualityButton(tracks: Tracks) {
        val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        val selectedIndex = (0 until videoGroup.length).firstOrNull { videoGroup.isTrackSelected(it) } ?: return
        val format = videoGroup.getTrackFormat(selectedIndex)
        b.btnQuality.text = if (format.height > 0) "${format.height}p" else "Auto"
    }

    private fun showQualityDialog() {
        val p = player ?: return
        val tracks = p.currentTracks
        val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        val qualities = mutableListOf<String>()
        qualities.add("Авто (Auto)")
        val formats = mutableListOf<androidx.media3.common.Format>()
        for (i in 0 until videoGroup.length) {
            val format = videoGroup.getTrackFormat(i)
            val text = if (format.height > 0) "${format.height}p (${format.bitrate / 1000} kbps)" else "Track $i"
            qualities.add(text)
            formats.add(format)
        }
        AlertDialog.Builder(this).setTitle("Качество видео").setItems(qualities.toTypedArray()) { _, which ->
            if (which == 0) {
                trackSelector.parameters = trackSelector.buildUponParameters()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .build()
            } else {
                trackSelector.parameters = trackSelector.buildUponParameters()
                    .setOverrideForType(TrackSelectionOverride(videoGroup.getMediaTrackGroup(), which - 1))
                    .build()
            }
        }.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        // ВАЖНО: здесь НЕТ killProcess, чтобы приложение не закрывалось при нажатии "Назад"
    }
}
