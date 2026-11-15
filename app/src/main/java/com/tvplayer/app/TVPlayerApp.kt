package com.tvplayer.app
import com.chaquo.python.Python
import com.chaquo.python.android.PyApplication

class TVPlayerApp : PyApplication() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
        Thread {
            try {
                DohProvider.testDoh(this)
            } catch (e: Exception) {
                CrashLogger.error("DoH", "Ошибка pre-flight проверки DoH", e)
            }
            try {
                val m = Python.getInstance().getModule("resolver")
                val health = m.callAttr("doh_health").toString()
                CrashLogger.info("DoH", "Python DoH health: $health")
            } catch (e: Exception) {
                CrashLogger.error("DoH", "Не удалось получить Python DoH health", e)
            }
        }.apply { name = "DohPreflight"; isDaemon = true }.start()
    }
}
