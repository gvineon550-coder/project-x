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
import java.util.concurrent.TimeUnit

object DohProvider {
    private const val DOH_URL = "https://dns.adguard-dns.com/dns-query"
    private const val TEST_DOMAIN = "wikipedia.org"

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
        synchronized(this) {
            cached = null
            cachedContext = null
        }
        CrashLogger.info("DoH", "Кэш DohProvider сброшен")
    }

    private fun build(context: Context): Dns {
        val httpClient = OkHttpClient.Builder().build()
        val doh = try {
            val cache = Cache(File(context.cacheDir, "doh_cache"), 256L * 1024)
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
                if (!DohSettings.isEnabled(context) || doh == null) {
                    return Dns.SYSTEM.lookup(hostname)
                }
                try {
                    val result = doh.lookup(hostname)
                    if (result.isNotEmpty()) return result
                    CrashLogger.info("DoH", "Пустой ответ для $hostname — фолбэк на системный DNS")
                } catch (e: Exception) {
                    CrashLogger.info("DoH", "Ошибка DoH для $hostname: ${e.message} — фолбэк")
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
        if (!DohSettings.isEnabled(context.applicationContext)) {
            CrashLogger.info("DoH", "Pre-flight пропущен (DoH выключен)")
            return false
        }
        return try {
            val client = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
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
                CrashLogger.info("DoH", "⚠️ DoH AdGuard вернул пустой ответ для $TEST_DOMAIN")
                false
            }
        } catch (e: Exception) {
            CrashLogger.error("DoH", "❌ DoH AdGuard не работает: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    data class TestResult(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val latencyMs: Long = 0L,
        val error: String? = null
    )

    private fun isSuspiciousIp(ip: String): String? = when {
        ip == "0.0.0.0" || ip == "127.0.0.1" -> "AdGuard заблокировал (блок-страница)"
        ip.startsWith("8.6.") -> "AdGuard перенаправил на блок-страницу"
        else -> null
    }

    private fun tryResolveDoH(host: String): TestResult {
        val started = System.currentTimeMillis()
        return try {
            val client = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            val doh = DnsOverHttps.Builder()
                .client(client)
                .url(DOH_URL.toHttpUrl())
                .includeIPv6(false)
                .build()
            val result = doh.lookup(host)
            val elapsed = System.currentTimeMillis() - started
            if (result.isNotEmpty()) {
                val ip = result.first().hostAddress
                val suspicious = isSuspiciousIp(ip)
                if (suspicious != null) {
                    TestResult(name = host, ok = false, detail = "$host → $ip", latencyMs = elapsed, error = suspicious)
                } else {
                    TestResult(name = host, ok = true, detail = "$host → $ip", latencyMs = elapsed)
                }
            } else {
                TestResult(name = host, ok = false, detail = "пустой ответ", latencyMs = elapsed, error = "NXDOMAIN или блокировка")
            }
        } catch (e: Exception) {
            TestResult(name = host, ok = false, detail = "ошибка", latencyMs = System.currentTimeMillis() - started, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun trySystemResolve(host: String): TestResult {
        val started = System.currentTimeMillis()
        return try {
            val result = Dns.SYSTEM.lookup(host)
            val elapsed = System.currentTimeMillis() - started
            if (result.isNotEmpty()) {
                TestResult(name = host, ok = true, detail = "$host → ${result.first().hostAddress}", latencyMs = elapsed)
            } else {
                TestResult(name = host, ok = false, detail = "пустой ответ", latencyMs = elapsed)
            }
        } catch (e: Exception) {
            TestResult(name = host, ok = false, detail = "ошибка", latencyMs = System.currentTimeMillis() - started, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun tryPythonHealth(): TestResult {
        val started = System.currentTimeMillis()
        return try {
            val m = com.chaquo.python.Python.getInstance().getModule("resolver")
            val health = m.callAttr("doh_health").toString()
            val elapsed = System.currentTimeMillis() - started
            val available = Regex("'available':\\s*(True|False)").find(health)?.groupValues?.get(1) == "True"
            val enabled = Regex("'enabled':\\s*(True|False)").find(health)?.groupValues?.get(1) == "True"
            val cooldown = Regex("'cooldown_active':\\s*(True|False)").find(health)?.groupValues?.get(1) == "True"
            val fails = Regex("'consecutive_fails':\\s*(\\d+)").find(health)?.groupValues?.get(1) ?: "0"
            val cacheSize = Regex("'cache_size':\\s*(\\d+)").find(health)?.groupValues?.get(1) ?: "0"
            val dohResolved = Regex("'doh_resolved':\\s*(\\d+)").find(health)?.groupValues?.get(1) ?: "0"
            val fallback = Regex("'system_fallback':\\s*(\\d+)").find(health)?.groupValues?.get(1) ?: "0"
            val slowCount = Regex("'slow_count':\\s*(\\d+)").find(health)?.groupValues?.get(1) ?: "0"
            when {
                !enabled -> TestResult(name = "Python DoH", ok = false, detail = "выключен переключателем", latencyMs = elapsed)
                !available -> TestResult(name = "Python DoH", ok = false, detail = "dnspython не загружен", latencyMs = elapsed, error = "Python-DoH недоступен")
                cooldown -> TestResult(name = "Python DoH", ok = false, detail = "circuit breaker активен", latencyMs = elapsed, error = "ошибок: $fails, медленных: $slowCount")
                else -> TestResult(name = "Python DoH", ok = true, detail = "DoH: $dohResolved · фолбэк: $fallback · кэш: $cacheSize", latencyMs = elapsed)
            }
        } catch (e: Exception) {
            TestResult(name = "Python DoH", ok = false, detail = "не удалось получить статус", latencyMs = System.currentTimeMillis() - started, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun formatResult(title: String, res: TestResult): String {
        val icon = if (res.ok) "✅" else "❌"
        val sb = StringBuilder()
        sb.append("$icon $title")
        if (res.ok && res.latencyMs > 0) {
            sb.append(" — ${res.latencyMs} мс")
        } else if (!res.ok) {
            sb.append(" — не работает")
        }
        sb.append("\n")
        sb.append("   ${res.detail}")
        if (res.error != null) {
            sb.append("\n   ⚠ ${res.error}")
        }
        sb.append("\n\n")
        return sb.toString()
    }

    fun runFullDiagnostics(context: Context): String {
        val sb = StringBuilder()
        val enabled = DohSettings.isEnabled(context)
        sb.append(if (enabled) "🟢 AdGuard DoH: ВКЛ\n\n" else "🔴 AdGuard DoH: ВЫКЛ\n\n")
        if (!enabled) {
            sb.append("Переключатель выключен — все запросы идут через системный DNS.\n")
            sb.append("Включите AdGuard DoH, чтобы выполнить проверку.\n\n")
            CrashLogger.info("DoH", "Диагностика пропущена (DoH выключен)")
            return sb.toString()
        }

        var okCount = 0
        var totalCount = 0

        totalCount++
        val dohTest = tryResolveDoH(TEST_DOMAIN)
        if (dohTest.ok) okCount++
        sb.append(formatResult("AdGuard DoH (Kotlin)", dohTest))

        totalCount++
        val pyTest = tryPythonHealth()
        if (pyTest.ok) okCount++
        sb.append(formatResult("AdGuard DoH (Python)", pyTest))

        totalCount++
        val sysTest = trySystemResolve(TEST_DOMAIN)
        if (sysTest.ok) okCount++
        sb.append(formatResult("Системный DNS (фолбэк)", sysTest))

        totalCount++
        val vkTest = tryResolveDoH("api.live.vkvideo.ru")
        if (vkTest.ok) okCount++
        sb.append(formatResult("VK API через DoH", vkTest))

        totalCount++
        val ghTest = tryResolveDoH("api.github.com")
        if (ghTest.ok) okCount++
        sb.append(formatResult("GitHub через DoH", ghTest))

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("Итог: $okCount/$totalCount ")
        if (okCount == totalCount) sb.append("✅\n\n🎉 Все запросы идут через защищённый DNS!")
        else if (okCount > 0) sb.append("⚠️\n\nЧасть запросов идёт через фолбэк. Проверьте соединение.")
        else sb.append("❌\n\nDoH не работает. Все запросы через системный DNS.")

        CrashLogger.info("DoH", "Диагностика: $okCount/$totalCount")
        return sb.toString()
    }
}
