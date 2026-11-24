package com.tvplayer.app
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private var inactivityRunnable: Runnable? = null

    companion object {
        const val INACTIVITY_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        resetInactivityTimer()
    }

    override fun onResume() {
        super.onResume()
        resetInactivityTimer()
        // CacheManager.maybeCleanup(this) <-- ПЕРЕНЕСЕНО В MainActivity.onCreate
    }

    override fun onPause() {
        super.onPause()
        inactivityRunnable?.let { inactivityHandler.removeCallbacks(it) }
        inactivityRunnable = null
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetInactivityTimer()
    }

    private fun resetInactivityTimer() {
        inactivityRunnable?.let { inactivityHandler.removeCallbacks(it) }
        inactivityRunnable = Runnable {
            CrashLogger.info("Lifecycle", "Автовыключение: 4ч без активности")
            exitApp()
        }
        inactivityHandler.postDelayed(inactivityRunnable!!, INACTIVITY_TIMEOUT_MS)
    }

    override fun onDestroy() {
        inactivityRunnable?.let { inactivityHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    protected open fun exitApp() {
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
