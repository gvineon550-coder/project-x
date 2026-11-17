package com.tvplayer.app
import android.content.Context
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object DohProvider {
    private val PROVIDERS = listOf(
        "AdGuard" to "https://dns.adguard-dns.com/dns-query",
        "AdGuard Raw" to "https://unfiltered.adguard-dns.com/dns-query",
        "Quad9" to "https://dns.quad9.net/dns-query"
    )
    private const val TEST_DOMAIN = "wikipedia.org"

    @Volatile private var currentProviderIndex = 0
    @Volatile var activeStatusLabel: String = "AdGuard"
        private set

    private val cooldownUntil = LongArray(PROVIDERS.size) { 0L }
    private const val COOLDOWN_MS = 10 * 60 * 1000L

    @Volatile private var cached: Dns? = null
    @Volatile private var cachedContext: Context? = null

    fun createWithFallback(context: Context): Dns {
        val appCtx = context.applicationContext
        cached?.let { if (cachedContext === appCtx) return it }
        synchronized(this) {
            cached?.let { if (cachedContext === appCtx) return it }
            val created = build(appCtx)
            cached = created
            cachedContext = appCtx
            return created
        }
    }

    fun invalidate() {
        synchronized(this) { cached = null; cachedContext = null }
    }

    private fun build(context: Context): Dns {
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!DohSettings.isEnabled(context)) {
                    activeStatusLabel = "System DNS"
                    return Dns.SYSTEM.lookup(hostname)
                }
                val now = System.currentTimeMillis()
                for (i in PROVIDERS.indices) {
                    val index = (currentProviderIndex + i) % PROVIDERS.size
                    if (now < cooldownUntil[index]) continue
                    val (name, url) = PROVIDERS[index]
                    try {
                        val client = OkHttpClient.Builder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(1500, TimeUnit.MILLISECONDS).build()
                        val doh = DnsOverHttps.Builder().client(client).url(url.toHttpUrl()).includeIPv6(false).build()
                        val result = doh.lookup(hostname)
                        if (result.isNotEmpty()) {
                            if (index != currentProviderIndex) currentProviderIndex = index
                            activeStatusLabel = name
                            return result
                        }
                    } catch (e: Exception) {
                        cooldownUntil[index] = now + COOLDOWN_MS
                    }
                }
                activeStatusLabel = "System (Fallback)"
                return try { Dns.SYSTEM.lookup(hostname) } catch (e: UnknownHostException) { throw e }
            }
        }
    }

    fun testDoh(context: Context): Boolean {
        if (!DohSettings.isEnabled(context.applicationContext)) return false
        return try {
            val client = OkHttpClient.Builder().build()
            val doh = DnsOverHttps.Builder().client(client).url(PROVIDERS[0].second.toHttpUrl()).includeIPv6(false).build()
            doh.lookup(TEST_DOMAIN).isNotEmpty()
        } catch (_: Exception) { false }
    }

    fun runFullDiagnostics(context: Context): String {
        val enabled = DohSettings.isEnabled(context)
        return if (enabled) "🟢 DoH: ВКЛ\nАктивен: $activeStatusLabel" else "🔴 DoH: ВЫКЛ"
    }
}
