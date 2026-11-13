package com.tvplayer.app
import com.chaquo.python.android.PyApplication

class TVPlayerApp : PyApplication() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
