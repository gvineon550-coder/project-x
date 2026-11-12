package com.tvplayer.app
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.tvplayer.app.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private var sleepTimer: CountDownTimer? = null
    private var names: List<String> = emptyList()
    private var urls: List<String> = emptyList()
    private var currentIndex: Int = 0
    private var retryCount = 0
    private val MAX_RETRIES = 5
    private val RETRY_DELAY = 2000L
    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        b.controlsOverlay.visibility = View.GONE
        b.btnQuality.visibility = View.GONE
        b.btnSleepTimer.visibility = View.GONE
        b.leftControls.visibility = View.GONE
    }
    private val hideChannelOverlayRunnable = Runnable { b.channelNameOverlay.visibility = View.GONE }
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityPlayerBinding.inflate(layoutInflater); setContentView(b.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        names = intent.getStringArrayListExtra("names") ?: emptyList()
        urls = intent.getStringArrayListExtra("urls") ?: emptyList()
        currentIndex = intent.getIntExtra("index", 0)
        startPlayback()
        b.btnBack.setOnClickListener { finish() }
        b.btnExitApp.setOnClickListener { exitApp() }
        b.btnRetry.setOnClickListener { b.errorLayout.visibility = View.GONE; retryCount = 0; startPlayback() }
    }
    // Полное закрытие приложения
    private fun exitApp() {
        handler.removeCallbacksAndMessages(null)
        sleepTimer?.cancel()
        player?.release()
        player = null
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (b.controlsOverlay.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> { switchChannel(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> { switchChannel(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showControls(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    private fun switchChannel(direction: Int) {
        val newIndex = currentIndex + direction
        if (newIndex in urls.indices) { currentIndex = newIndex; startPlayback() }
    }
    private fun startPlayback() {
        getSharedPreferences("tvplayer", MODE_PRIVATE).edit().putInt("last_channel", currentIndex).apply()
        val chNum = currentIndex + 1
        val chName = names.getOrNull(currentIndex) ?: "Канал"
        b.channelNameOverlay.text = "$chNum. $chName"
        b.channelNameOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideChannelOverlayRunnable)
        handler.postDelayed(hideChannelOverlayRunnable, 3000)
        b.progress.visibility = View.VISIBLE
        b.errorLayout.visibility = View.GONE
        retryCount = 0
        val url = urls.getOrNull(currentIndex) ?: return
        lifecycleScope.launch {
            val direct = StreamResolver.resolve(url)
            if (direct.startsWith("ERROR:")) { handleError(); return@launch }
            play(direct)
        }
    }
    private fun play(m3u8: String) {
        player?.release()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to "https://vk.com/", "Origin" to "https://vk.com"))
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(10000, 30000, 1500, 3000).build()
        trackSelector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this).setTrackSelector(trackSelector).setLoadControl(loadControl).build().apply {
            setMediaSource(HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(m3u8)))
            playWhenReady = true; prepare()
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) { updateQualityButton(tracks); retryCount = 0; b.progress.visibility = View.GONE }
                override fun onPlayerError(error: PlaybackException) { super.onPlayerError(error); handleError() }
            })
        }
        b.playerView.player = player
        b.progress.visibility = View.GONE
        b.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        showControls()
        b.btnQuality.setOnClickListener { showQualityDialog(); showControls() }
        b.btnSleepTimer.setOnClickListener { showSleepTimerDialog(); showControls() }
        b.btnPlayPause.setOnClickListener {
            player?.let { p -> if (p.isPlaying) { p.pause(); b.btnPlayPause.setImageResource(android.R.drawable.ic_media_play) } else { p.play(); b.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause) } }
            showControls()
        }
        b.touchArea.setOnClickListener { if (b.controlsOverlay.visibility == View.VISIBLE) hideControlsRunnable.run() else showControls() }
    }
    private fun updateQualityButton(tracks: Tracks) {
        val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        val selectedIndex = (0 until videoGroup.length).firstOrNull { videoGroup.isTrackSelected(it) } ?: return
        val format = videoGroup.getTrackFormat(selectedIndex)
        val height = if (format.height > 0) "${format.height}p" else "Auto"
        val bitrate = if (format.bitrate > 0) " ${format.bitrate / 1000}k" else ""
        b.btnQuality.text = height + bitrate
    }
    private fun showQualityDialog() {
        val p = player ?: return
        val tracks = p.currentTracks
        val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        val qualities = mutableListOf<String>(); qualities.add("Авто (Auto)")
        val formats = mutableListOf<androidx.media3.common.Format>()
        var selectedTrackIndex = -1
        for (i in 0 until videoGroup.length) { if (videoGroup.isTrackSelected(i)) { selectedTrackIndex = i; break } }
        for (i in 0 until videoGroup.length) {
            val format = videoGroup.getTrackFormat(i)
            val prefix = if (i == selectedTrackIndex) "▶ " else "   "
            val text = if (format.height > 0) "${prefix}${format.height}p (${format.bitrate / 1000} kbps)" else "${prefix}Track $i"
            qualities.add(text); formats.add(format)
        }
        AlertDialog.Builder(this).setTitle("Качество видео").setItems(qualities.toTypedArray()) { _, which ->
            if (which == 0) trackSelector.parameters = trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO).setAllowVideoMixedMimeTypeAdaptiveness(true).build()
            else trackSelector.parameters = trackSelector.buildUponParameters().setOverrideForType(TrackSelectionOverride(videoGroup.getMediaTrackGroup(), which - 1)).build()
        }.show()
    }
    private fun handleError() {
        if (retryCount < MAX_RETRIES) {
            retryCount++; b.progress.visibility = View.VISIBLE; handler.postDelayed({ startPlayback() }, RETRY_DELAY)
        } else {
            b.progress.visibility = View.GONE
            b.errorText.text = "Не удалось подключиться к потоку.\nСделано попыток: $MAX_RETRIES"
            b.errorLayout.visibility = View.VISIBLE; b.controlsOverlay.visibility = View.GONE
            b.btnQuality.visibility = View.GONE; b.btnSleepTimer.visibility = View.GONE; b.leftControls.visibility = View.GONE
        }
    }
    private fun showSleepTimerDialog() {
        val options = arrayOf("Выключить", "5 минут", "10 минут", "15 минут", "30 минут", "45 минут", "1 час", "2 часа", "3 часа", "4 часа", "5 часов")
        val minutes = intArrayOf(0, 5, 10, 15, 30, 45, 60, 120, 180, 240, 300)
        AlertDialog.Builder(this).setTitle("Таймер сна").setItems(options) { _, which -> val mins = minutes[which]; if (mins == 0) cancelSleepTimer() else startSleepTimer(mins) }.show()
    }
    private fun startSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        val millis = minutes * 60 * 1000L
        sleepTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val m = millisUntilFinished / 1000 / 60; val s = (millisUntilFinished / 1000) % 60
                b.btnSleepTimer.text = String.format("Сон: %02d:%02d", m, s); b.btnSleepTimer.setTextColor(Color.parseColor("#FFEB3B"))
            }
            override fun onFinish() { exitApp() }
        }.start()
        showControls()
    }
    private fun cancelSleepTimer() { sleepTimer?.cancel(); sleepTimer = null; b.btnSleepTimer.text = "Сон: Выкл"; b.btnSleepTimer.setTextColor(Color.WHITE) }
    private fun showControls() {
        b.controlsOverlay.visibility = View.VISIBLE
        b.btnQuality.visibility = View.VISIBLE
        b.btnSleepTimer.visibility = View.VISIBLE
        b.leftControls.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsRunnable); handler.postDelayed(hideControlsRunnable, 4000)
    }
    override fun onDestroy() {
        super.onDestroy(); handler.removeCallbacksAndMessages(null); sleepTimer?.cancel(); player?.release(); player = null
    }
}
