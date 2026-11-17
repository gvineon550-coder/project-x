package com.tvplayer.app
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.Coil
import com.tvplayer.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivitySettingsBinding.inflate(layoutInflater); setContentView(b.root)
        b.txtVersion.text = "TV Player v${BuildConfig.VERSION_CODE}"
        updateDohToggleUI()
        updateCacheStats()

        b.btnBack.setOnClickListener { finish() }
        b.btnToggleDoh.setOnClickListener {
            val newState = !DohSettings.isEnabled(this)
            DohSettings.setEnabled(this, newState)
            updateDohToggleUI()
            try { Coil.reset() } catch (_: Exception) {}
        }
        b.btnCheckDoh.setOnClickListener {
            lifecycleScope.launch {
                val report = withContext(Dispatchers.IO) { DohProvider.runFullDiagnostics(this@SettingsActivity) }
                b.txtDohResult.text = report
            }
        }
        b.btnClearCache.setOnClickListener {
            StreamCache.clearAll(this)
            Toast.makeText(this, "Кэш ссылок очищен", Toast.LENGTH_SHORT).show()
            updateCacheStats()
        }
        b.btnFullReset.setOnClickListener {
            StreamCache.clearAll(this)
            ChannelCache.clear(this)
            CrashLogger.clear(this)
            getSharedPreferences("tvplayer", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "Полный сброс выполнен", Toast.LENGTH_SHORT).show()
            updateCacheStats()
        }
        b.btnOpenLog.setOnClickListener {
            b.txtAbout.text = CrashLogger.readAll(this)
        }
        b.btnShareLog.setOnClickListener {
            CrashLogger.share(this)
        }
        b.btnExitApp.setOnClickListener {
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun updateDohToggleUI() {
        val enabled = DohSettings.isEnabled(this)
        b.btnToggleDoh.text = if (enabled) "🟢 DoH: ВКЛ" else "🔴 DoH: ВЫКЛ"
        b.btnToggleDoh.setTextColor(if (enabled) Color.parseColor("#FF4ADE80") else Color.parseColor("#FFFF6B6B"))
    }

    private fun updateCacheStats() {
        val streamCacheSize = getSharedPreferences("stream_cache", MODE_PRIVATE).all.size
        b.txtCacheStats.text = "Записей в StreamCache: $streamCacheSize / 50"
    }
}
