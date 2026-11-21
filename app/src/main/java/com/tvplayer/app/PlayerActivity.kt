package com.tvplayer.app
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvplayer.app.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

class PlayerActivity : BaseActivity() {
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
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    private val handler = Handler(Looper.getMainLooper())
    private var lastPlayerPosition = -1L
    private var sessionStartTime = 0L
    private var debugUpdater: Runnable? = null

    private val hideControlsRunnable = Runnable {
        b.controlsOverlay.visibility = View.GONE
        b.rightControls.visibility = View.GONE
        b.btnSleepTimer.visibility = View.GONE
        b.leftControls.visibility = View.GONE
        b.btnBug.visibility = View.GONE
    }
    private val hideChannelOverlayRunnable = Runnable { b.channelNameOverlay.visibility = View.GONE }

    private val showBufferingRunnable = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            b.bufferingText.visibility = View.VISIBLE
        }
    }
    private val hideBufferingRunnable = Runnable {
        b.bufferingText.visibility = View.GONE
    }

    private val stallChecker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying) {
                lastPlayerPosition = -1L
                handler.postDelayed(this, 30_000)
                return
            }
            val pos = p.currentPosition
            if (lastPlayerPosition == pos && pos > 0) {
                CrashLogger.info("Player", "Стоп-кадр на ${pos}мс — перерезолв")
                urls.getOrNull(currentIndex)?.let { StreamCache.invalidate(this@PlayerActivity, it) }
                handler.removeCallbacks(this)
                handleError("Поток замер")
                return
            }
            lastPlayerPosition = pos
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityPlayerBinding.inflate(layoutInflater); setContentView(b.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        b.touchArea.isFocusable = false
        b.touchArea.isFocusableInTouchMode = false

        setupFocusHighlight()

        names = intent.getStringArrayListExtra("names") ?: emptyList()
        urls = intent.getStringArrayListExtra("urls") ?: emptyList()
        currentIndex = intent.getIntExtra("index", 0)
        sessionStartTime = System.currentTimeMillis()
        startPlayback()

        b.btnBack.setOnClickListener { finish() }
        b.btnExitApp.setOnClickListener { exitApp() }
        b.btnRetry.setOnClickListener { b.errorLayout.visibility = View.GONE; retryCount = 0; startPlayback() }
        b.btnAspect.setOnClickListener { toggleAspect(); resetHideTimeout() }
        b.btnShowLog.setOnClickListener { showLogDialog() }
        b.btnBug.setOnClickListener { showDebugPanel() }

        b.btnDebugClose.setOnClickListener { hideDebugPanel() }
        b.btnDebugRefresh.setOnClickListener {
            b.debugText.text = buildDebugText()
            Toast.makeText(this, "Обновлено", Toast.LENGTH_SHORT).show()
        }
        b.btnDebugCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("debug", b.debugText.text))
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
        if (b.debugPanel.visibility == View.VISIBLE) hideDebugPanel()
    }

    private fun currentClock(): String {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        return String.format("%02d:%02d", h, m)
    }

    private fun showDebugPanel() {
        b.debugPanel.visibility = View.VISIBLE
        b.controlsOverlay.visibility = View.GONE
        b.rightControls.visibility = View.GONE
        b.btnSleepTimer.visibility = View.GONE
        b.leftControls.visibility = View.GONE
        b.btnBug.visibility = View.GONE
        b.debugText.text = buildDebugText()
        b.btnDebugClose.requestFocus()
        startDebugUpdater()
        CrashLogger.info("Player", "Открыта debug-панель")
    }

    private fun hideDebugPanel() {
        b.debugPanel.visibility = View.GONE
        stopDebugUpdater()
        showControls()
        CrashLogger.info("Player", "Закрыта debug-панель")
    }

    private fun startDebugUpdater() {
        stopDebugUpdater()
        debugUpdater = object : Runnable {
            override fun run() {
                if (b.debugPanel.visibility != View.VISIBLE) return
                b.debugText.text = buildDebugText()
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(debugUpdater!!, 1000)
    }

    private fun stopDebugUpdater() {
        debugUpdater?.let { handler.removeCallbacks(it) }
        debugUpdater = null
    }

    private fun buildDebugText(): String {
        val sb = StringBuilder()
        val ch = names.getOrNull(currentIndex) ?: "?"
        val chUrl = urls.getOrNull(currentIndex) ?: "?"
        val cached = StreamCache.get(this, chUrl)
        val cacheStatus = if (cached != null) "HIT" else "MISS"

        sb.appendLine("📺 #${currentIndex + 1}: $ch")
        sb.appendLine("  url: ${chUrl.take(50)}")
        sb.appendLine("  cache: $cacheStatus")
        sb.appendLine()

        val p = player
        if (p != null) {
            sb.appendLine("🎬 ПЛЕЕР")
            sb.appendLine("  state: ${playerStateName(p.playbackState)}  playing: ${if (p.isPlaying) "▶" else "⏸"}")
            val bufDelta = (p.bufferedPosition - p.currentPosition) / 1000.0
            sb.appendLine("  pos: ${formatMs(p.currentPosition)}  buffer: +${"%.1f".format(bufDelta)}с")
            try {
                val tracks = p.currentTracks
                val vg = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                if (vg != null) {
                    val idx = (0 until vg.length).firstOrNull { vg.isTrackSelected(it) }
                    if (idx != null) {
                        val fmt = vg.getTrackFormat(idx)
                        val fps = if (fmt.frameRate > 0) fmt.frameRate.toInt() else 0
                        sb.appendLine("  ${fmt.width}×${fmt.height} @${fps}fps · ${fmt.bitrate/1000} kbps")
                        sb.appendLine("  video: ${fmt.codecs ?: "?"}")
                    }
                }
            } catch (_: Exception) {}
            val session = if (sessionStartTime > 0) formatDuration(System.currentTimeMillis() - sessionStartTime) else "?"
            sb.appendLine("  retry: $retryCount/$MAX_RETRIES · session: $session")
            sb.appendLine()
        }

        sb.appendLine("🌐 СЕТЬ: online=${NetworkUtils.isOnline(this)}")
        sb.appendLine()

        sb.appendLine("🛡 DoH")
        sb.appendLine("  enabled: ${DohSettings.isEnabled(this)}")
        try {
            val m = com.chaquo.python.Python.getInstance().getModule("resolver")
            val health = m.callAttr("doh_health").toString()
            val resolved = Regex("'doh_resolved':\\s*(\\d+)").find(health)?.groupValues?.get(1) ?: "0"
            val fallback = Regex("'system_fallback':\\s*(\\d+)").find(health)?.groupValues?.get(1) ?: "0"
            val cooldown = Regex("'cooldown_active':\\s*(True|False)").find(health)?.groupValues?.get(1) == "True"
            val cacheSize = Regex("'cache_size':\\s*(\\d+)").find(health)?.groupValues?.get(1) ?: "0"
            sb.appendLine("  resolved: $resolved · fb: $fallback · cache: $cacheSize")
            sb.appendLine("  circuit: ${if (cooldown) "COOLDOWN" else "OK"}")
        } catch (_: Exception) { sb.appendLine("  python: error") }
        sb.appendLine()

        sb.appendLine("💾 КЭШ")
        val streamPrefs = getSharedPreferences("stream_cache", MODE_PRIVATE)
        sb.appendLine("  stream: ${streamPrefs.all.size}/120")
        val thumbPrefs = getSharedPreferences("thumb_cache", MODE_PRIVATE)
        sb.appendLine("  thumb: ${thumbPrefs.all.size}/30")
        val coilDisk = File(cacheDir, "image_cache").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        sb.appendLine("  coil: ${formatBytes(coilDisk)} / 5 МБ")
        val logFile = File(filesDir, "app.log")
        sb.appendLine("  log: ${formatBytes(if (logFile.exists()) logFile.length() else 0L)} / 200 КБ")
        val totalFiles = filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        sb.appendLine("  files: ${formatBytes(totalFiles)} / 30 МБ")
        sb.appendLine()

        sb.appendLine("📦 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("  ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("  Android ${android.os.Build.VERSION.RELEASE}")

        return sb.toString()
    }

    private fun playerStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "?"
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return String.format("%02d:%02d:%02d", h, m, sec)
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%dч %02dм %02dс", h, m, sec) else String.format("%02dм %02dс", m, sec)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> String.format("%.1f МБ", bytes / 1024f / 1024f)
    }

    private fun showLogDialog() {
        val log = CrashLogger.readAll(this)
        AlertDialog.Builder(this)
            .setTitle("Лог приложения")
            .setMessage(log.takeLast(3000))
            .setPositiveButton("Скопировать") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("log", log))
                Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun setupFocusHighlight() {
        listOf(b.btnQuality, b.btnSleepTimer, b.btnAspect, b.btnBack, b.btnExitApp).forEach { tv ->
            tv.setOnFocusChangeListener { _, hasFocus ->
                tv.setTextColor(if (hasFocus) Color.BLACK else Color.WHITE)
            }
        }
    }

    private fun toggleAspect() {
        currentResizeMode = if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        b.playerView.resizeMode = currentResizeMode
        b.btnAspect.text = if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "Заполнить" else "Вписать"
        CrashLogger.info("Player", "Формат: ${b.btnAspect.text}")
    }

    override fun exitApp() {
        stopDebugUpdater()
        handler.removeCallbacksAndMessages(null)
        sleepTimer?.cancel()
        player?.release()
        player = null
        super.exitApp()
    }

    private fun handleMenuNavigation(keyCode: Int): Boolean {
        val cur = currentFocus ?: run { b.btnPlayPause.requestFocus(); return true }
        val nextId: Int? = when (cur.id) {
            R.id.btnPlayPause -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> R.id.btnSleepTimer
                KeyEvent.KEYCODE_DPAD_LEFT -> R.id.btnBack
                KeyEvent.KEYCODE_DPAD_RIGHT -> R.id.btnQuality
                KeyEvent.KEYCODE_DPAD_DOWN -> R.id.btnQuality
                else -> null
            }
            R.id.btnSleepTimer -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> R.id.btnPlayPause
                KeyEvent.KEYCODE_DPAD_LEFT -> R.id.btnExitApp
                KeyEvent.KEYCODE_DPAD_RIGHT -> R.id.btnQuality
                else -> null
            }
            R.id.btnBack -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> R.id.btnExitApp
                KeyEvent.KEYCODE_DPAD_DOWN -> R.id.btnPlayPause
                else -> null
            }
            R.id.btnExitApp -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> R.id.btnBack
                KeyEvent.KEYCODE_DPAD_RIGHT -> R.id.btnSleepTimer
                KeyEvent.KEYCODE_DPAD_DOWN -> R.id.btnPlayPause
                else -> null
            }
            R.id.btnQuality -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> R.id.btnSleepTimer
                KeyEvent.KEYCODE_DPAD_DOWN -> R.id.btnAspect
                KeyEvent.KEYCODE_DPAD_LEFT -> R.id.btnPlayPause
                else -> null
            }
            R.id.btnAspect -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> R.id.btnQuality
                KeyEvent.KEYCODE_DPAD_LEFT -> R.id.btnPlayPause
                KeyEvent.KEYCODE_DPAD_DOWN -> R.id.btnPlayPause
                else -> null
            }
            else -> null
        }
        if (nextId != null) { findViewById<View>(nextId)?.requestFocus(); return true }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            onUserInteraction()
        }
        if (b.debugPanel.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideDebugPanel()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (b.channelListPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                b.channelListPanel.visibility = View.GONE
                showControls()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (b.controlsOverlay.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> { hideControlsRunnable.run(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        (currentFocus as? View)?.performClick(); resetHideTimeout(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val handled = handleMenuNavigation(event.keyCode)
                        if (handled) { resetHideTimeout(); return true }
                        return true
                    }
                }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { switchChannel(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchChannel(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> { showChannelList(); return true }
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun resetHideTimeout() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun showChannelList() {
        val channels = names.mapIndexed { i, n -> Channel(n, urls.getOrNull(i) ?: "", "") }
        b.channelListPanel.visibility = View.VISIBLE
        b.controlsOverlay.visibility = View.GONE
        b.rightControls.visibility = View.GONE
        b.btnSleepTimer.visibility = View.GONE
        b.leftControls.visibility = View.GONE
        b.btnBug.visibility = View.GONE
        b.channelListRecycler.layoutManager = LinearLayoutManager(this)
        b.channelListRecycler.adapter = ChannelListAdapter(channels) { ch ->
            val idx = urls.indexOf(ch.url)
            if (idx >= 0) { currentIndex = idx; retryCount = 0; startPlayback() }
            b.channelListPanel.visibility = View.GONE
        }
        b.channelListRecycler.scrollToPosition(currentIndex)
        b.channelListRecycler.post { b.channelListRecycler.findViewHolderForAdapterPosition(currentIndex)?.itemView?.requestFocus() }
    }

    private fun switchChannel(direction: Int) {
        val newIndex = currentIndex + direction
        if (newIndex in urls.indices) { currentIndex = newIndex; retryCount = 0; startPlayback() }
    }

    private fun startPlayback() {
        if (b.debugPanel.visibility == View.VISIBLE) hideDebugPanel()
        getSharedPreferences("tvplayer", MODE_PRIVATE).edit().putInt("last_channel", currentIndex).apply()
        val chNum = currentIndex + 1
        val chName = names.getOrNull(currentIndex) ?: "Канал"
        CrashLogger.info("Player", "Открытие #$chNum: $chName")
        b.channelNameOverlay.text = "$chNum. $chName  ·  ${currentClock()}"
        b.channelNameOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideChannelOverlayRunnable)
        handler.postDelayed(hideChannelOverlayRunnable, 3000)
        b.errorLayout.visibility = View.GONE
        b.bufferingText.visibility = View.GONE
        val channelUrl = urls.getOrNull(currentIndex) ?: return
        val cached = StreamCache.get(this, channelUrl)
        if (cached != null) { CrashLogger.info("Player", "Кэш ссылки"); play(cached); return }
        if (!NetworkUtils.isOnline(this)) {
            b.progress.visibility = View.GONE
            b.errorText.text = "Нет подключения к интернету.\n\nНажмите «Повторить»."
            b.errorLayout.visibility = View.VISIBLE
            CrashLogger.info("Player", "Нет интернета")
            return
        }
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val direct = StreamResolver.resolve(channelUrl)
            if (direct.startsWith("ERROR")) {
                val allowRetry = shouldRetry(direct)
                handleError(direct, allowRetry)
                return@launch
            }
            StreamCache.put(this@PlayerActivity, channelUrl, direct)
            play(direct)
        }
    }

    private fun shouldRetry(errorText: String): Boolean {
        return errorText.contains("Timeout", ignoreCase = true) ||
               errorText.contains("ConnectionError", ignoreCase = true) ||
               errorText.contains("NetworkError", ignoreCase = true) ||
               errorText.contains("HTTPError", ignoreCase = true) ||
               errorText.contains("IncompleteRead", ignoreCase = true) ||
               errorText.contains("SSLError", ignoreCase = true)
    }

    private fun buildHttpDataSource(): OkHttpDataSource.Factory {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(DohProvider.createWithFallback(this))
            .build()
        return OkHttpDataSource.Factory(okHttp)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to "https://vk.com/", "Origin" to "https://vk.com"))
    }

    private fun play(m3u8: String) {
        player?.release()
        handler.removeCallbacks(stallChecker)
        handler.removeCallbacks(showBufferingRunnable)
        handler.removeCallbacks(hideBufferingRunnable)
        lastPlayerPosition = -1L
        val dataSource = buildHttpDataSource()
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(10000, 30000, 1500, 3000).build()
        trackSelector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this).setTrackSelector(trackSelector).setLoadControl(loadControl).build().apply {
            setMediaSource(HlsMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(m3u8)))
            playWhenReady = true; prepare()
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            handler.removeCallbacks(hideBufferingRunnable)
                            handler.postDelayed(showBufferingRunnable, 500)
                        }
                        Player.STATE_READY, Player.STATE_ENDED, Player.STATE_IDLE -> {
                            handler.removeCallbacks(showBufferingRunnable)
                            b.bufferingText.visibility = View.GONE
                        }
                    }
                }
                override fun onTracksChanged(tracks: Tracks) {
                    updateQualityButton(tracks); retryCount = 0; b.progress.visibility = View.GONE
                    CrashLogger.info("Player", "Воспроизведение началось")
                }
                override fun onPlayerError(error: PlaybackException) {
                    super.onPlayerError(error)
                    CrashLogger.error("Player", "Ошибка: ${error.errorCodeName} (${error.errorCode})")
                    b.bufferingText.visibility = View.GONE
                    urls.getOrNull(currentIndex)?.let { StreamCache.invalidate(this@PlayerActivity, it) }
                    handleError("Ошибка плеера: ${error.errorCodeName}")
                }
            })
        }
        b.playerView.player = player
        b.progress.visibility = View.GONE
        b.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        showControls()
        handler.postDelayed(stallChecker, 30_000)
        b.btnQuality.setOnClickListener { showQualityDialog(); resetHideTimeout() }
        b.btnSleepTimer.setOnClickListener { showSleepTimerDialog(); resetHideTimeout() }
        b.btnPlayPause.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) { p.pause(); b.btnPlayPause.setImageResource(android.R.drawable.ic_media_play) }
                else { p.play(); b.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause) }
            }
            resetHideTimeout()
        }
        b.touchArea.setOnClickListener {
            if (b.controlsOverlay.visibility == View.VISIBLE) hideControlsRunnable.run() else showControls()
        }
    }

    private fun updateQualityButton(tracks: Tracks) {
        val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        val selectedIndex = (0 until videoGroup.length).firstOrNull { videoGroup.isTrackSelected(it) } ?: return
        val format = videoGroup.getTrackFormat(selectedIndex)
        val height = if (format.height > 0) "${format.height}p" else "Auto"
        val bitrate = if (format.bitrate > 0) " ${format.bitrate / 1000}k" else ""
        b.btnQuality.text = height + bitrate
        CrashLogger.info("Player", "Качество: $height${bitrate}")
    }

    private fun showQualityDialog() {
        val p = player ?: return
        val tracks = p.currentTracks
        val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        val qualities = mutableListOf<String>(); qualities.add("Авто (Auto)")
        var selectedTrackIndex = -1
        for (i in 0 until videoGroup.length) { if (videoGroup.isTrackSelected(i)) { selectedTrackIndex = i; break } }
        for (i in 0 until videoGroup.length) {
            val format = videoGroup.getTrackFormat(i)
            val prefix = if (i == selectedTrackIndex) "▶ " else "   "
            val text = if (format.height > 0) "${prefix}${format.height}p (${format.bitrate / 1000} kbps)" else "${prefix}Track $i"
            qualities.add(text)
        }
        AlertDialog.Builder(this).setTitle("Качество видео").setItems(qualities.toTypedArray()) { _, which ->
            if (which == 0) trackSelector.parameters = trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO).setAllowVideoMixedMimeTypeAdaptiveness(true).build()
            else trackSelector.parameters = trackSelector.buildUponParameters().setOverrideForType(TrackSelectionOverride(videoGroup.getMediaTrackGroup(), which - 1)).build()
        }.show()
    }

    private fun handleError(errorText: String? = null, allowRetry: Boolean = true) {
        handler.removeCallbacks(stallChecker)
        handler.removeCallbacks(showBufferingRunnable)
        b.bufferingText.visibility = View.GONE
        if (allowRetry && retryCount < MAX_RETRIES) {
            retryCount++
            val delay = RETRY_DELAY * (1L shl (retryCount - 1))
            b.progress.visibility = View.VISIBLE
            CrashLogger.info("Player", "Попытка $retryCount/$MAX_RETRIES через ${delay/1000}с")
            handler.postDelayed({ startPlayback() }, delay)
        } else {
            b.progress.visibility = View.GONE
            val details = errorText ?: "Неизвестная ошибка"
            val attempts = if (allowRetry) MAX_RETRIES else 0
            b.errorText.text = "Не удалось воспроизвести канал\n\n$details\n\nПопыток: $attempts"
            b.errorLayout.visibility = View.VISIBLE
            b.controlsOverlay.visibility = View.GONE
            b.rightControls.visibility = View.GONE
            b.btnSleepTimer.visibility = View.GONE
            b.leftControls.visibility = View.GONE
            b.btnBug.visibility = View.GONE
            CrashLogger.error("Player", "Не удалось: $details")
        }
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("Выключить", "5 минут", "10 минут", "15 минут", "30 минут", "45 минут", "1 час", "2 часа", "3 часа", "4 часа", "5 часов")
        val minutes = intArrayOf(0, 5, 10, 15, 30, 45, 60, 120, 180, 240, 300)
        AlertDialog.Builder(this).setTitle("Таймер сна").setItems(options) { _, which ->
            val mins = minutes[which]
            if (mins == 0) cancelSleepTimer() else startSleepTimer(mins)
        }.show()
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        CrashLogger.info("Player", "Сон: $minutes мин")
        val millis = minutes * 60 * 1000L
        sleepTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val m = millisUntilFinished / 1000 / 60; val s = (millisUntilFinished / 1000) % 60
                b.btnSleepTimer.text = String.format("Сон: %02d:%02d", m, s); b.btnSleepTimer.setTextColor(Color.parseColor("#FFEB3B"))
            }
            override fun onFinish() {
                CrashLogger.info("Player", "Таймер сработал — выключение")
                exitApp()
            }
        }.start()
        resetHideTimeout()
    }

    private fun cancelSleepTimer() { sleepTimer?.cancel(); sleepTimer = null; b.btnSleepTimer.text = "Сон: Выкл"; b.btnSleepTimer.setTextColor(Color.WHITE) }

    private fun showControls() {
        b.controlsOverlay.visibility = View.VISIBLE
        b.rightControls.visibility = View.VISIBLE
        b.btnSleepTimer.visibility = View.VISIBLE
        b.leftControls.visibility = View.VISIBLE
        b.btnBug.visibility = View.VISIBLE
        b.btnPlayPause.post { b.btnPlayPause.requestFocus() }
        resetHideTimeout()
    }

    override fun onDestroy() {
        super.onDestroy()
        CrashLogger.info("Player", "Закрытие")
        stopDebugUpdater()
        handler.removeCallbacksAndMessages(null); sleepTimer?.cancel(); player?.release(); player = null
    }
}
