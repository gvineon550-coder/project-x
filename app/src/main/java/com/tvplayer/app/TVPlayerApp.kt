package com.tvplayer.app
import com.chaquo.python.Python
import com.chaquo.python.android.PyApplication

class TVPlayerApp : PyApplication() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
        Thread {
            val desired = DohSettings.isEnabled(this)
            try { Python.getInstance().getModule("resolver").callAttr("set_doh_enabled", desired) } catch (_: Exception) {}
        }.apply { name = "DohPreflight"; isDaemon = true }.start()
    }
}
