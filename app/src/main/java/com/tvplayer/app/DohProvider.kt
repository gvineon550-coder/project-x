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

    fun createWithFallback(context: Context): Dns {
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
            CrashLogger.error("DoH", "Не удалось создать DoH — системный DNS", e)
            null
        }

        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (doh != null) {
                    try {
                        val result = doh.lookup(hostname)
                        if (result.isNotEmpty()) return result
                        CrashLogger.info("DoH", "Пустой ответ для $hostname — системный DNS")
                    } catch (e: Exception) {
                        CrashLogger.info("DoH", "Ошибка DoH для $hostname: ${e.message} — системный DNS")
                    }
                }
                return try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: UnknownHostException) {
                    CrashLogger.error("DoH", "Системный DNS не смог $hostname", e)
                    throw e
                }
            }
        }
    }

    fun testDoh(context: Context): Boolean {
        return try {
            val dns = createWithFallback(context)
            val result = dns.lookup("dns.adguard-dns.com")
            val ok = result.isNotEmpty()
            CrashLogger.info("DoH", if (ok) "✅ DoH работает" else "⚠️ DoH пустой результат")
            ok
        } catch (e: Exception) {
            CrashLogger.error("DoH", "❌ DoH тест провален", e)
            false
        }
    }
}
