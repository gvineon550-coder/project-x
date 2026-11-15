package com.tvplayer.app
import android.content.Context
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException

object DohProvider {
    private const val DOH_URL = "https://dns.adguard-dns.com/dns-query"

    /**
     * Возвращает кастомный Dns с фолбэком на Dns.SYSTEM.
     * Если DoH недоступен/не ответил — происходит автоматический
     * откат на системный DNS (без DoH).
     */
    fun createWithFallback(context: Context): Dns {
        val httpClient = OkHttpClient.Builder().build()
        val doh = try {
            // Bootstrap-клиент с кэшем (10 МБ) — чтобы DoH-запросы кэшировались
            val cache = Cache(File(context.cacheDir, "doh_cache"), 10L * 1024 * 1024)
            val bootstrapClient = httpClient.newBuilder().cache(cache).build()

            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(DOH_URL.toHttpUrl())
                .includeIPv6(false) // IPv4 безопаснее и стабильнее на Android TV
                .build()
        } catch (e: Exception) {
            CrashLogger.error("DoH", "Не удалось создать DoH — используем системный DNS", e)
            null
        }

        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (doh != null) {
                    try {
                        val result = doh.lookup(hostname)
                        if (result.isNotEmpty()) {
                            // DoH сработал
                            return result
                        }
                        // Пустой результат — AdGuard заблокировал или не ответил
                        CrashLogger.info("DoH", "Пустой ответ для $hostname — фолбэк на системный DNS")
                    } catch (e: Exception) {
                        // Ошибка DoH (таймаут, нет сети, сертификат и т.д.)
                        CrashLogger.info("DoH", "Ошибка DoH для $hostname: ${e.message} — фолбэк на системный DNS")
                    }
                }
                // ФОЛБЭК: системный DNS, если DoH нет или он не сработал
                return try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: UnknownHostException) {
                    CrashLogger.error("DoH", "Системный DNS тоже не смог резолвить $hostname", e)
                    throw e
                }
            }
        }
    }

    /**
     * Проверка работоспособности DoH.
     * Возвращает true, если AdGuard DNS резолвит домен (любой),
     * и false — если DoH не отвечает.
     */
    fun testDoh(context: Context): Boolean {
        return try {
            val dns = createWithFallback(context)
            // Пробуем резолвить заведомо существующий домен через DoH
            val result = dns.lookup("dns.adguard-dns.com")
            val ok = result.isNotEmpty()
            CrashLogger.info("DoH", if (ok) "✅ DoH работает" else "⚠️ DoH вернул пустой результат")
            ok
        } catch (e: Exception) {
            CrashLogger.error("DoH", "❌ DoH тест провален", e)
            false
        }
    }
}
