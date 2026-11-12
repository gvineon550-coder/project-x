package com.tvplayer.app
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
object StreamResolver {
    suspend fun resolve(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val m = Python.getInstance().getModule("resolver")
            val r = m.callAttr("resolve", url).toString()
            if (r.startsWith("ERROR:")) null else r
        } catch (_: Exception) { null }
    }
}
