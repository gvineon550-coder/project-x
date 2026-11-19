package com.tvplayer.app
import com.chaquo.python.Python
import com.chaquo.python.android.PyApplication
import java.util.UUID

class TVPlayerApp : PyApplication() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)

        val prefs = getSharedPreferences("tvplayer", MODE_PRIVATE)
        if (!prefs.contains("vk_from_id")) {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString("vk_from_id", newId).apply()
            CrashLogger.info("VkApi", "Сгенерирован X-From-Id: $newId")
        }

        Thread {
            val desired = DohSettings.isEnabled(this)

            var synced = false
            for (attempt in 0 until 3) {
                try {
                    val m = Python.getInstance().getModule("resolver")
                    m.callAttr("set_doh_enabled", desired)
                    val health = m.callAttr("doh_health").toString()
                    val pyEnabled = Regex("'enabled':\\s*(True|False)").find(health)?.groupValues?.get(1) == "True"
                    if (pyEnabled == desired) {
                        synced = true
                        CrashLogger.info("DoH", "Python синхронизирован при старте: enabled=$desired")
                        break
                    }
                    CrashLogger.info("DoH", "Python-флаг не совпал (попытка ${attempt + 1})")
                } catch (e: Exception) {
                    CrashLogger.error("DoH", "Ошибка синхронизации при старте (попытка ${attempt + 1})", e)
                }
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            }
            if (!synced) CrashLogger.error("DoH", "❌ Python-флаг НЕ синхронизирован при старте")

            try {
                val m = Python.getInstance().getModule("resolver")
                CrashLogger.info("DoH", "Python DoH health: ${m.callAttr("doh_health")}")
            } catch (e: Exception) {
                CrashLogger.error("DoH", "Не удалось получить Python DoH health", e)
            }

            if (desired) {
                try { DohProvider.testDoh(this) }
                catch (e: Exception) { CrashLogger.error("DoH", "Ошибка pre-flight", e) }
            } else {
                CrashLogger.info("DoH", "Pre-flight пропущен (DoH выключен)")
            }
        }.apply { name = "DohPreflight"; isDaemon = true }.start()
    }
}
