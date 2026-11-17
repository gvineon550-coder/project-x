package com.tvplayer.app
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.tvplayer.app.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var names: List<String> = emptyList()
    private var urls: List<String> = emptyList()
    private var currentIndex: Int = 0
    private val handler = Handler(Looper.getMainLooper())

    private var sleepTimerActive = false
    private val sleepRunnable = Runnable { finish() }
    private val SLEEP_TIMEOUT_MS = 4 * 60 * 60 * 1000L

    private val inactivityRunnable = Runnable {
        CrashLogger.info("Player", "Автовыключение по режиму сна (4 часа бездействия)")
        finish()
    }

    private val hideControls = Runnable {
        b.controlsOverlay.visibility = View.GONE
        b.rightControls.visibility = View.GONE
        b.btnSleepTimer.visibility = View.GONE
        b.btnDnsStatus.visibility = View.GONE
        b.txtReconnectStatus.visibility = View.GONE
        b.leftControls.visibility = View.GONE
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.playbackState == Player.STATE_BUFFERING && p.playWhenReady) {
                CrashLogger.info("Watchdog", "Обнаружено зависание на буферизации, перезапуск потока...")
                b.txtReconnectStatus.visibility = View.VISIBLE
                handler.postDelayed({ b.txtReconnectStatus.visibility = View.GONE }, 3000)
                startPlayback()
            }
            handler.postDelayed(this, 10000)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityPlayerBinding.inflate(layoutInflater); setContentView(b.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        names = intent.getStringArrayListExtra("names") ?: emptyList()
        urls = intent.getStringArrayListExtra("urls") ?: emptyList()
        currentIndex = intent.getIntExtra("index", 0)

        startPlayback()
        b.btnBack.setOnClickListener { finish() }
        b.btnSleepTimer.setOnClickListener { toggleSleepTimer() }
        b.btnQuality.setOnClickListener { cycleQuality() }

        resetInactivityTimer()
        handler.postDelayed(watchdogRunnable, 10000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetInactivityTimer()
        if (b.controlsOverlay.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            hideControls.run(); return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { switchChannel(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchChannel(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showControls(); return true }
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(inactivityRunnable)
        handler.postDelayed(inactivityRunnable, SLEEP_TIMEOUT_MS)
    }

    private fun toggleSleepTimer() {
        sleepTimerActive = !sleepTimerActive
        if (sleepTimerActive) {
            handler.postDelayed(sleepRunnable, SLEEP_TIMEOUT_MS)
            b.btnSleepTimer.text = "Сон: 4ч"
        } else {
            handler.removeCallbacks(sleepRunnable)
            b.btnSleepTimer.text = "Сон: Выкл"
        }
    }

    private fun cycleQuality() {
        val p = player ?: return
        val currentParams = p.trackSelectionParameters
        val currentMaxH = currentParams.maxVideoHeight
        
        val newMaxH = when (currentMaxH) {
            Int.MAX_VALUE -> 720
            720 -> 480
            else -> Int.MAX_VALUE
        }
        
        p.trackSelectionParameters = currentParams.buildUpon()
            .setMaxVideoHeight(newMaxH)
            .build()
        
        b.btnQuality.text = if (newMaxH == Int.MAX_VALUE) "Качество: Auto" else "Лимит: ${newMaxH}p"
        showControls()
    }

    private fun switchChannel(dir: Int) {
        val ni = currentIndex + dir
        if (ni in urls.indices) { currentIndex = ni; startPlayback() }
    }

    private fun startPlayback() {
        b.errorLayout.visibility = View.GONE
        val url = urls.getOrNull(currentIndex) ?: return
        val cached = StreamCache.get(this, url)
        if (cached != null) {
            play(cached)
            prefetchNext()
            return
        }
        lifecycleScope.launch {
            val direct = StreamResolver.resolve(url)
            if (direct.startsWith("ERROR")) {
                b.errorText.text = direct
                b.errorLayout.visibility = View.VISIBLE
                return@launch
            }
            StreamCache.put(this@PlayerActivity, url, direct)
            play(direct)
            prefetchNext()
        }
    }

    private fun prefetchNext() {
        val nextIndex = currentIndex + 1
        if (nextIndex in urls.indices) {
            val nextUrl = urls[nextIndex]
            if (StreamCache.get(this, nextUrl) == null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val resolved = StreamResolver.resolve(nextUrl)
                    if (!resolved.startsWith("ERROR")) {
                        StreamCache.put(this@PlayerActivity, nextUrl, resolved)
                    }
                }
            }
        }
    }

    private fun play(m3u8: String) {
        player?.release()
        val okHttp = OkHttpClient.Builder().dns(DohProvider.createWithFallback(this)).build()
        player = ExoPlayer.Builder(this).build().apply {
            setMediaSource(HlsMediaSource.Factory(OkHttpDataSource.Factory(okHttp)).createMediaSource(MediaItem.fromUri(m3u8)))
            playWhenReady = true; prepare()
        }
        b.playerView.player = player
        showControls()
    }

    private fun showControls() {
        b.controlsOverlay.visibility = View.VISIBLE
        b.rightControls.visibility = View.VISIBLE
        b.btnSleepTimer.visibility = View.VISIBLE
        b.btnDnsStatus.visibility = View.VISIBLE
        b.btnDnsStatus.text = "DNS: ${DohProvider.activeStatusLabel}"
        b.leftControls.visibility = View.VISIBLE
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 5000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null); player?.release(); player = null
    }
}
