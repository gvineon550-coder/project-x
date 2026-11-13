package com.tvplayer.app
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
object StreamResolver {
    suspend fun resolve(url: String): String = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        CrashLogger.info("StreamResolver", "→ Резолв: $url")
        try {
            val m = Python.getInstance().getModule("resolver")
            val result = m.callAttr("resolve", url).toString()
            val elapsed = System.currentTimeMillis() - start
            if (result.startsWith("ERROR")) {
                CrashLogger.error("StreamResolver", "✗ Резолв провален за ${elapsed}мс: $result")
            } else {
                CrashLogger.info("StreamResolver", "✓ Резолв за ${elapsed}мс → ${result.take(80)}...")
            }
            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            CrashLogger.error("StreamResolver", "✗ Исключение при резолве за ${elapsed}мс", e)
            "ERROR[${e.javaClass.simpleName}]: ${e.message ?: "unknown"}"
        }
    }
    suspend fun getThumbnail(url: String): String = withContext(Dispatchers.IO) {
        try {
            val m = Python.getInstance().getModule("resolver")
            m.callAttr("get_thumbnail", url).toString()
        } catch (_: Exception) { "" }
    }
}
