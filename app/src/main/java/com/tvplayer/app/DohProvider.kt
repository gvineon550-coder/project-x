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
    private const val TEST_DOMAIN = "example.com"

    @Volatile private var cached: Dns? = null

    fun createWithFallback(context: Context): Dns {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val created = build(context.applicationContext)
            cached = created
            return created
        }
    }

    private fun build(context: Context): Dns {
        val httpClient = OkHttpClient.Builder().build()
        val doh = try {
            val cache = Cache(File(context.cacheDir, "doh_cache"), 10L * 1024 * 1024)
            val bootstrapClient = httpClient.newBuilder().cache(cache).build()
            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(DOH_URL.toHttpUrl())
                .includeIPv6(false)
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
                        if (result.isNotEmpty()) return result
                        CrashLogger.info("DoH", "Пустой ответ для $hostname — фолбэк на системный DNS")
                    } catch (e: Exception) {
                        CrashLogger.info("DoH", "Ошибка DoH для $hostname: ${e.message} — фолбэк на системный DNS")
                    }
                }
                return try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: UnknownHostException) {
                    CrashLogger.error("DoH", "Системный DNS тоже не смог резолвить $hostname", e)
                    throw e
                }
            }
        }
    }

    fun testDoh(context: Context): Boolean {
        return try {
            val client = OkHttpClient.Builder().build()
            val doh = DnsOverHttps.Builder()
                .client(client)
                .url(DOH_URL.toHttpUrl())
                .includeIPv6(false)
                .build()
            val started = System.currentTimeMillis()
            val result = doh.lookup(TEST_DOMAIN)
            val elapsed = System.currentTimeMillis() - started
            if (result.isNotEmpty()) {
                CrashLogger.info("DoH", "✅ DoH AdGuard работает ($TEST_DOMAIN → ${result.first().hostAddress}, ${elapsed}мс)")
                true
            } else {
                CrashLogger.info("DoH", "⚠️ DoH AdGuard вернул пустой ответ для $TEST_DOMAIN (${elapsed}мс)")
                false
            }
        } catch (e: Exception) {
            CrashLogger.error("DoH", "❌ DoH AdGuard не работает: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }
}
